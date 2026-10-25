# Security and data-format notes

Status: implemented preview design, including the v0.2.1 folder update; not an independently audited product.

## Protection boundary

CalVault encrypts file contents and sensitive catalog metadata in private app storage. The calculator appearance provides discretion. Other ordinary apps cannot access app-private storage under normal Android isolation. Object counts, encrypted lengths, and filesystem timing are not padded or concealed.

An attacker controlling the OS, a rooted device, the unlocked application process, a malicious keyboard/accessibility service with granted access, or an external camera is outside the protection boundary. `FLAG_SECURE` remains set in production for all app screens, including the calculator; it cannot prevent every capture mechanism or OEM defect.

## Keys

- Random 256-bit vault master key.
- Domain-separated HKDF-SHA256 subkeys for catalog encryption, object-key wrapping, per-file recovery records, and saved navigation.
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
- `catalog.enc`: encrypted catalog containing names, MIME types, folders, sizes, timestamps, mode labels, source locations/removal status, recycle-bin membership, encrypted object keys, and SHA-256 digests. Version 3 adds nested folder paths and deleted-subtree membership; versions 1/2 remain readable and a version 1 snapshot is preserved during migration.
- `catalog.previous`, `catalog.v1.snapshot`, and damaged catalog snapshots: encrypted recovery evidence, retained rather than discarded by repair.
- `navigation.enc`: separately authenticated encrypted browsing/media/picker context. No saved PIN or recovery-key entry. Bounded to 2 MiB of plaintext to accommodate large selections/playlists.
- `objects/<random-UUID>.cvf`: authenticated encrypted file streams. `.part` files contain encrypted staging data only.
- `objects/<random-UUID>.meta`: independently authenticated encrypted per-file recovery metadata and wrapped key. `.gone` and `.foldergone` markers suppress automatic recreation of explicitly removed objects/folders.

Associated data binds ciphertext to its format context, vault ID, object ID, and purpose. Sensitive filenames and folder names are not included in public associated data.

Nested folders are logical metadata paths with a separator forbidden in legacy flat names, never raw filesystem paths. Folder names are bounded to 120 characters per component and 32 levels. Renaming a parent rewrites active descendant metadata; recycling a parent groups the subtree and preserves empty descendants. Blob paths remain random UUIDs and ciphertext is not rewritten by folder migration. Recovery records use version 3 for new writes and still read version 2 records.

Import writes encrypted staging data and computes a source digest, then completely decrypts/authenticates and compares the staged file before promotion and catalog commit. Both Encryption and Hidden labels use the same authenticated encryption. Adding files authorizes a move: a source must still match the digest and a complete authenticated vault copy must exist before deletion is attempted. Android directory entries are synced before source removal. Gallery deletion can require Android consent; read-only/denied providers leave the item pending with its verified vault copy retained. Providers cannot offer an atomic compare-and-delete operation, so the change check is best effort.

Catalog and envelope writes sync their temporary file before atomic replacement. Catalog commit makes a file visible. Repair reconnects authenticated records and complete validated ciphertext without deleting unknown blobs, partial files, or catalog evidence. Repair cannot reconstruct overwritten bytes or unauthenticated metadata. Exhaustive storage/power-loss fault testing remains release work.

Unhide releases plaintext to the original supported location or an explicitly selected destination. The output is closed, reopened, and digest/size checked before the vault file is removed. Newly created failed output is rolled back where the provider permits; a provider that refuses cleanup can retain a partial output. Existing destination files are not overwritten. The app has no export-as-copy flow.

Ordinary deletion moves catalog entries into a manual recycle bin. Explicit permanent deletion/Empty bin deletes CalVault's owned object, staging, and recovery copies and writes a tombstone; unhide does the same only after destination verification. This does not affect external originals or existing backups, and is not a forensic secure-wipe guarantee for flash storage. Repair and unlock never automatically empty the bin.

## Portable archives

`.calvault` archives consist of a bounded binary public header followed by a Tink authenticated encrypted stream. Version 2 is written; versions 1 and 2 are read. The header contains the portable recovery envelope, vault ID, and a fresh backup salt, bound as associated data to archive encryption. It never contains the device wrapper, a PIN verifier, the PIN, the device key, or the recovery secret.

The backup key is derived from the master key, fresh salt, and a separate backup purpose. The payload contains the encrypted catalog, encrypted navigation (version 2), and an ordered manifest of random object IDs, ciphertext lengths, and object ciphertexts. Catalog digests bind imported content and lengths. Restore regenerates recovery records. URI grants are device-specific and do not migrate.

Restore rejects unrecognized formats, duplicate/invalid IDs, count mismatches, oversized fields, unsafe paths, truncated streams, damaged tags, incorrect keys, and trailing bytes. Files are staged as ciphertext and completely verified before committing a new vault. Full restore requires an uninitialized vault. A separate retrieval operation accepts only a backup matching the current vault/master, preserves healthy files and damaged prior ciphertext, and retrieves missing/damaged objects. Tombstones block automatic recreation of permanently removed objects. New PIN/device wrappers are created after successful full-restore recovery authentication.

Backups are verified by reopening the selected destination. A destination that cannot be read back cannot produce a verified success. Old backups remain decryptable by their matching historical recovery key. There is no remote revocation or guaranteed physical erasure on flash storage.

## Lifecycle and platform controls

Background and screen-off locking default on and can be configured independently. An enabled lock invalidates the session, cancels sensitive work, clears file state, and closes media access. Rotation retains the session/player; background media pauses even with locking disabled. Process recreation always starts locked. A generation check prevents a PIN derivation started before a lock from publishing a later session. PIN/recovery-key fields are never saved. Nonsecret browsing/media context and pending removal-receipt/picker context are saved only in authenticated encrypted navigation. Destructive results require their matching live operation or returned Android result; no blind replay occurs on startup.

Both Android backup rule formats exclude the app's data. Sensitive storage also uses the always-excluded no-backup directory. The manifest requests no Internet, broad media-library, All files access, camera, or microphone permission.

Screenshots in the automated test suite use synthetic files and temporarily clear `FLAG_SECURE` inside test code only, then restore and assert the flag. Production code has no screenshot-protection toggle.
