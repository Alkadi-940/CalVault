# Security and data-format notes

Status: implemented alpha design; not an independently audited product.

## Protection boundary

CalVault encrypts file contents and sensitive catalog metadata in private app storage. The calculator appearance provides discretion. Other ordinary apps cannot access app-private storage under normal Android isolation. Object counts, encrypted lengths, and filesystem timing are not padded or concealed.

An attacker controlling the OS, a rooted device, the unlocked application process, a malicious keyboard/accessibility service with granted access, or an external camera is outside the protection boundary. `FLAG_SECURE` remains set in production for all app screens, including the calculator; it cannot prevent every capture mechanism or OEM defect.

## Keys

- Random 256-bit vault master key.
- Domain-separated HKDF-SHA256 subkeys for catalog encryption and object-key wrapping.
- Independent random 256-bit keys per file, wrapped with AES-256-GCM under the vault's wrapping subkey.
- Catalog encryption and software key envelopes use AES-256-GCM with fresh 96-bit nonces and 128-bit tags.
- File and archive streams use Google's Tink `AesGcmHkdfStreaming`, configured with SHA-256, a 256-bit derived key, 1 MiB segments, and zero initial offset. The authenticated channel supports random-access playback without plaintext staging files.
- PIN wrapping uses Argon2id version 19: 64 MiB memory, three iterations, four lanes, 16-byte random salt, and 32-byte output. Format version 1 fixes these parameters, so untrusted records cannot request arbitrary KDF allocations.
- The PIN-encrypted master-key envelope is itself encrypted with a device-specific Android Keystore AES-256-GCM key. No separate PIN verifier is stored. Android hardware authentication does not validate the app's custom PIN; hardware backing depends on the device.
- Recovery uses a random 256-bit secret rendered as nine groups of eight hexadecimal characters, including a four-byte SHA-256 checksum. A recovery subkey derived with HKDF wraps the master key independently of the device key. The secret is shown during setup and is not saved by the app.

A short numeric PIN has limited entropy. The Keystore layer adds device binding; software attempt delays are not hardware-enforced against a compromised app process. Session keys and working plaintext buffers are released/wiped on a best-effort basis. JVM/Android managed memory and crypto internals can retain copies until reclaimed.

## Files and commits

All vault files live under `Context.noBackupFilesDir/vault`:

- `envelope.bin`: format magic/version, random vault ID, fixed-length salt fields, device-wrapped PIN envelope, and recovery envelope.
- `catalog.enc`: encrypted catalog containing names, MIME types, logical folders, sizes, creation times, encrypted object keys, and content SHA-256 digests.
- `objects/<random-UUID>.cvf`: authenticated encrypted file streams. `.part` files contain encrypted staging data only.

Associated data binds ciphertext to its format context, vault ID, object ID, and purpose. Sensitive filenames and folder names are not included in public associated data.

Import writes encrypted staging data and computes a source digest, then completely decrypts/authenticates and compares the staged file before promoting it and committing the encrypted catalog. Original removal is separately requested by the owner. A source must still match the imported digest and a complete, authenticated vault copy must still exist before supported document-provider deletion is attempted. Providers cannot offer an atomic compare-and-delete operation, so this is a best-effort change check.

Catalog and envelope writes sync their temporary file before an atomic replacement. Catalog commit is the point that makes a file visible. Unreferenced encrypted objects and staging files are removed on unlock. Directory-entry durability through abrupt power failure and exhaustive storage-fault scenarios remain release verification work; process-safe design is not a claim of absolute power-loss immunity.

Exports release authenticated plaintext to an explicitly selected destination. A later corruption/error can leave a partial exported file if its provider refuses cleanup. Imports never remove originals automatically.

## Portable archives

`.calvault` version 1 consists of a bounded binary public header followed by a Tink authenticated encrypted stream. The header contains the portable recovery envelope, vault ID, and a fresh backup salt. It is bound as associated data to archive encryption. It never contains the device wrapper, a PIN verifier, the PIN, the device key, or the recovery secret.

The backup key is derived from the master key, fresh salt, and a separate backup purpose. The payload contains the encrypted catalog and an ordered manifest of random object IDs, ciphertext lengths, and object ciphertexts. The catalog's authenticated digests bind imported content and lengths.

Restore rejects unrecognized formats, duplicate/invalid IDs, count mismatches, oversized fields, unsafe paths, truncated streams, damaged tags, incorrect keys, and trailing bytes. Files are staged as ciphertext and completely verified before committing a new vault. Existing initialized vaults are never merged or overwritten by restore. New PIN/device wrappers are created after successful recovery authentication.

Backups are verified by reopening the selected destination. A destination that cannot be read back cannot produce a verified success. Old backups remain decryptable by their matching historical recovery key. There is no remote revocation or guaranteed physical erasure on flash storage.

## Lifecycle and platform controls

Backgrounding/screen-off invalidates the session, cancels sensitive operations, clears visible file state, and closes media access. A generation check prevents a PIN derivation started before a lock from publishing a new unlocked session afterward. UI secrets are never saved through Compose saved state or a navigation SavedStateHandle.

Both Android backup rule formats exclude the app's data. Sensitive storage also uses the always-excluded no-backup directory. The manifest requests no Internet, broad media-library, All files access, camera, or microphone permission.

Screenshots in the automated test suite use synthetic files and temporarily clear `FLAG_SECURE` inside test code only, then restore and assert the flag. Production code has no screenshot-protection toggle.
