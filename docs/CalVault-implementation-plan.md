# CalVault: Android product and implementation plan

Status: initial planning snapshot. Implementation has since started; see ../README.md and VERIFICATION.md for current status.
Date: 2026-10-25

## Decision and intended outcome

Build a native Android app that opens as a polished, functioning calculator and unlocks a private encrypted file vault through a numeric PIN. The owner can import photos, videos, and arbitrary files; view supported media inside the app; and recover a manual encrypted backup on another device.

Confirmed by the owner:

- Vault files stay on the device. No accounts or cloud synchronization.
- Manual encrypted backup and restore are part of version 1.
- A separately saved recovery key can recover access after a forgotten PIN.

Recommended defaults, which remain adjustable during implementation:

- Kotlin, Jetpack Compose, and Android 10+ (API 29).
- A 6–12 digit PIN, entered as digits followed by a deliberate long press on `=`. A normal tap on `=` performs calculation. Preserve leading zeros as input text.
- Launcher icon and label present as Calculator; onboarding and the store listing explain CalVault's real functionality.
- No Internet permission, ads, analytics SDK, or remote backend in version 1.
- Lock immediately when backgrounded or the screen turns off; return to the calculator on reopening.

Success means someone casually using the calculator sees a credible calculator, while the owner can reliably retrieve and recover their encrypted files. Discretion is a UI property; encryption and correct key handling provide data protection.

## Repository and development evidence

The workspace was empty when inspected. No existing source, build configuration, or applicable AGENTS.md was found in the workspace or checked ancestors. There is no existing implementation to preserve or audit.

Git and Java 21 are available on PATH. `adb` and `gradle` were not found on PATH; an Android SDK and Android Studio were not found at the checked default locations. This does not establish that they are absent elsewhere. Locate or install the Android toolchain before the first build; use a project Gradle wrapper rather than require a global Gradle installation.

## Product review

The strongest first release is a trustworthy private vault with a calculator entrance. Three risks take priority over adding features: losing originals during import, losing the only decryption key, and exposing plaintext through previews or temporary files.

The promise must be precise:

- Android settings, permission screens, installation records, storage usage, and the store listing can reveal the app. Do not promise an undetectable installation.
- Import creates an encrypted copy. The original remains visible until removal succeeds. Other copies, gallery trash, and cloud backups may remain outside CalVault's control.
- Recovery restores access to surviving encrypted data. The recovery key alone cannot recreate deleted files. After uninstall, app-data clearing, device loss, or hardware failure, a saved backup is required.
- Protection targets casual inspection, other ordinary apps, and copied ciphertext. A rooted or compromised OS, an attacker controlling the unlocked process, external cameras, and previously exported copies are outside the guarantee.

Document the calculator entrance and vault functionality in the Play listing and review instructions. Google Play requires transparent, documented functionality; store acceptance remains a release review item, not a guarantee. [Google Play behavior transparency](https://support.google.com/googleplay/android-developer/answer/17006354?hl=en-GB&rd=3).

### Version 1 scope

| Area | Included behavior |
| --- | --- |
| Calculator | Addition, subtraction, multiplication, division, percent, decimal input, sign change, clear, backspace, operator precedence, and safe error handling |
| Setup | Create and confirm PIN; explain original-file handling; generate and confirm a saved recovery key; teach unlock gesture |
| Vault | Photos, Videos, and Files views; folders; selection; filename search after unlock; rename; move; storage usage |
| Import | Multi-select media or files, progress, cancellation, per-file outcomes, and verified encrypted copies |
| Viewing | In-app photo viewer and video player with seeking; unsupported formats show file details and an explicit export action |
| Export | Save a decrypted copy to a user-selected destination; explain that it leaves vault protection |
| Deletion | Confirm removal from the vault; separately offer removal of imported originals when supported |
| Protection | PIN attempt delays, automatic locking, protected vault windows, no plaintext disk previews, encrypted sensitive metadata |
| Recovery | Reset PIN using recovery key on the existing vault; restore a backup on a clean installation and choose a new PIN |
| Backup | User-selected destination, encrypted versioned snapshot, verification, cancellation and failure handling |

Defer cloud sync, subscriptions, decoy vaults, intruder selfies, secret browser, background camera capture, app-icon switching, and biometric quick unlock. The first release should prove the data lifecycle before expanding access paths.

## Design review

Use a restrained calculator with large numerals, charcoal surfaces, neutral digit buttons, and one warm accent for operators. Support light and dark themes, readable contrast, scalable text, and touch targets of at least 48 dp. Avoid permission prompts until the associated action.

| Screen | Primary action and essential states |
| --- | --- |
| First-run setup | Create PIN → save recovery key → confirm selected key groups → practice unlock |
| Calculator | Ordinary calculation; PIN plus hold `=` unlocks; incorrect attempts keep the calculator visible |
| Vault home | Photos / Videos / Files; folder navigation; prominent Add button; useful empty state |
| Import review | Show per-item verified success, failure, or cancellation; only eligible successes can offer original removal |
| Viewer | Photo zoom or video playback; back; explicit export; immediate lock button |
| Security and backup | Change PIN, create backup, restore backup, explain recovery and app-data loss |
| Recovery | Accessible from a documented calculator menu; enter recovery key; set new PIN after successful authentication |

For accessibility, expose an equivalent documented unlock action through calculator overflow; TalkBack users should not depend on discovering a long press. The same attempt limits and authentication apply. Do not display the recovery secret in clipboard contents, notifications, logs, or unprotected screenshots.

Locking must clear the calculator entry, photo surfaces, player buffers, in-memory thumbnails, and sensitive navigation state. Never persist PIN entry or unlocked content through saved UI state. Set secure window flags before displaying setup secrets or vault content. Verify screenshots and Recents behavior on supported devices; platform protection is not an absolute capture guarantee. [Android secure activities](https://developer.android.com/security/fraud-prevention/activities).

## Engineering review

### Platform and structure

Choose native Kotlin and Compose because Android storage providers, lifecycle transitions, Keystore, and secure media playback are central to this app. Flutter is reasonable for a committed cross-platform roadmap, but would add native integration work here. A web wrapper is a poor fit for this vault's storage and key requirements.

Start with one application module and clear package boundaries: calculator, authentication, vault UI, media, storage, cryptography, and backup. Use ViewModels, coroutines/Flow, Room for the object catalog, DataStore only for nonsensitive preferences, and Media3 for playback. Avoid a backend and premature module proliferation.

Room stores opaque object IDs, operational state, ciphertext sizes, and encrypted metadata blobs. Names, folder names/membership, MIME types, source details, timestamps, and thumbnails are encrypted. Raw object counts, ciphertext lengths, and filesystem timing may remain observable; padding and traffic-analysis resistance are outside version 1.

Select compatible stable dependency and SDK versions during implementation, pin them, and commit the Gradle wrapper, version catalog, and verification configuration. Confirm the release's target-SDK requirement before publishing.

### Key hierarchy and authentication

Do not encrypt files directly with the numeric PIN or store a plain PIN/hash alongside an otherwise accessible master key.

1. Generate a random 256-bit vault master key using a cryptographic random generator. Derive separate metadata and key-wrapping subkeys with domain-separated HKDF contexts.
2. Generate independent per-object streaming keys. Wrap those keysets under the vault wrapping subkey; encrypt metadata with AES-256-GCM and fresh nonces. Authenticate format version, vault ID, object ID, and purpose as appropriate; sensitive names never belong in public associated data.
3. Derive a PIN wrapping key with Argon2id and a random salt. Encrypt the master key under it, then encrypt that entire wrapper with a device-specific Android Keystore key. Persist salts, bounded KDF parameters, and the nested ciphertext, but no separate offline-verifiable PIN record.
4. The device unwraps the outer layer; a successful PIN-derived unwrap authenticates the app PIN. A failed unwrap counts as an unsuccessful attempt and never releases content keys.
5. Generate an independent random 256-bit recovery secret, rendered in grouped text with a checksum. Derive a recovery wrapping key with HKDF and use it to wrap the same master key in a portable authenticated envelope. Store the envelope, never the recovery secret itself. Show the secret during setup and confirm selected groups before completing setup.
6. PIN changes only replace the PIN wrapper. Recovery on an existing installation creates a new PIN wrapper and, if necessary, a new device key after recovery authentication. Recovery must also be reachable when the device key is missing or invalidated.

Android Keystore protects device key material, with hardware backing where available. Its hardware authentication gates use device credentials/biometrics; they do not validate CalVault's custom numeric PIN. The nested wrapper adds device binding, but app-level delays are not hardware-enforced brute-force protection against a compromised process. [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [Android cryptography](https://developer.android.com/privacy-and-security/cryptography).

Use a maintained Argon2id implementation, confirm supported Android ABIs and test vectors, and benchmark on the minimum device tier. Start evaluation with the RFC's memory-constrained profile: 64 MiB, three passes, four lanes, 16-byte salt, and 32-byte output. Record chosen parameters in the envelope, bound them before allocation, and make no unmeasured unlock-speed claim. [RFC 9106](https://www.rfc-editor.org/rfc/rfc9106.html).

Persist attempt state: after five unsuccessful attempts, delay 30 seconds; increase subsequent delays up to 15 minutes. Restart and ordinary clock changes must not trivially reset an active delay. Never automatically erase files. A short numeric PIN has limited entropy, even with a KDF; the device wrapper and recovery secret are essential parts of the design.

On lock, cancel sensitive work, close decryption handles, and release keys and plaintext buffers with best-effort wiping. Managed runtimes cannot guarantee that every memory copy is erased. Do not persist decrypted master keys or unwrapped Tink keysets through convenience storage APIs.

### File encryption and safe import

Use a maintained streaming authenticated-encryption implementation, initially evaluate Tink's AES256_GCM_HKDF_1MB. This avoids loading full videos into memory and authenticates segments. Prove Android compatibility and authenticated random access for video seeking before committing the media integration. Never invent a chunked encryption format or reuse GCM nonces. [Tink Streaming AEAD](https://developers.google.com/tink/streaming-aead).

Store ciphertext in app-private internal storage with opaque filenames. Use Android Photo Picker for images/videos and the Storage Access Framework for other files; the minimum-version path must work when Photo Picker is unavailable. Avoid broad media-library and All files access permissions. [Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker), [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files), [Google Play file-access policy](https://support.google.com/googleplay/android-developer/answer/10467955?hl=en-en).

Import protocol:

1. Select readable streams and record temporary URI grants only as needed. Handle unknown sizes, revoked access, remote-provider failure, duplicates, and unsupported virtual documents.
2. Write encrypted staging objects with bounded buffers while calculating a digest of the imported bytes. No plaintext staging files.
3. Flush and close; fully decrypt and authenticate the staged object, checking digest and length against the imported stream.
4. Promote the object and commit its catalog row through a recoverable journal/state machine. Filesystem and database operations are not a single transaction: crash recovery must reconcile both orders of interruption.
5. Only mark a verified committed object as imported. Then offer optional original removal, with per-item results. Never delete an original after a failed or unverified import.

Photo Picker access does not imply delete permission or guarantee a corresponding writable MediaStore URI. Request MediaStore deletion consent where supported and authorized; on API 29 handle the applicable recoverable-security flow. For document providers, check deletion support and obtain the necessary grant. Otherwise instruct the user to remove the original in its source app. Denial or failure leaves the encrypted copy intact and reports that the original remains. [Android shared-media access and deletion](https://developer.android.com/training/data-storage/shared/media).

### Playback, lifecycle, and export

Feed the video player through an authenticated random-access data source; decode images through controlled streams and bounded image sampling. Never write plaintext media or thumbnails to disk caches. Arbitrary files can be stored and exported even if they cannot be previewed. Do not hand vault URIs to external viewers in version 1.

Treat screen-off, backgrounding, and process death as lock events. Rotation must preserve correct session state without persisting secrets. System picker excursions are explicit operations: cover and lock the vault, retain only the pending selection identity, and require reauthentication on return before reading or writing vault data. Cancel imports/backups safely on lock and restart incomplete items after reauthentication; do not silently keep an unlocked worker running.

Export requires an unlocked session and explicit destination selection. Mark success only after the destination closes successfully. Report partial writes and attempt cleanup when the provider supports deletion. The exported copy and any external destination backup are outside vault protection.

### Backup and recovery

Create a versioned `.calvault` backup from a consistent snapshot, blocking vault mutations during creation. Use a fresh backup salt and a domain-separated key derived from the master key to encrypt the complete backup payload. Include only the portable recovery envelope, public format/KDF identifiers, and required salts in the public header; authenticate that header. Never include the device key, PIN wrapper, PIN verifier, or recovery secret.

The encrypted payload contains the catalog, wrapped object keys, encrypted objects, and a manifest binding every object and length. A backup can be opened with its matching recovery secret without access to the original device. Exclude local filesystem/URI state and attempt counters from the portable payload.

Write through a user-selected document provider. Reopen and verify the completed archive before reporting a verified backup; if the destination cannot be reread, report it as unverified and ask the user to choose a verifiable destination. Interrupted or cancelled artifacts are incomplete and must never appear as a successful backup.

Restore authenticates the envelope and archive, applies strict bounds to lengths/counts/versions, verifies the full manifest and every object, and stages encrypted data before committing a new vault. Reject traversal paths, duplicate IDs, unsupported formats, truncation, and insufficient space. Version 1 restores into an empty installation/vault, with no automatic merge or overwrite of an existing populated vault. Choose a new PIN and create a fresh device wrapper after restoration.

Keep vault files, metadata, keys, and security settings out of automatic cloud backups and device transfers using both applicable generations of Android backup rules. Do not rely solely on `allowBackup=false`; transfer behavior varies by OS/device. Test the exclusions. Manual encrypted backup is the supported migration path. [Android backup behavior](https://developer.android.com/identity/data/autobackup), [Backup security recommendations](https://developer.android.com/privacy-and-security/risks/backup-best-practices).

The recovery key is a high-value secret: anyone holding it and matching ciphertext can decrypt that data. Save it separately from backups. Old backups remain recoverable with their historical recovery key; no remote revocation or secure flash erasure is promised. Recovery-key rotation is deferred until its rekeying and historical-backup semantics are designed.

## Developer-experience review and delivery sequence

| Phase | Work | Exit evidence |
| --- | --- | --- |
| 1: Foundation and security proof | Locate SDK; create Kotlin/Compose app and wrapper; build calculator; implement key-envelope prototype, streaming import, and seekable playback spike | Clean debug build; calculator tests; crypto vectors/tamper checks; large-video seek works without plaintext files |
| 2: Core vault | Setup/recovery key flow, PIN unlock, catalog, imports, photo/video viewing, folders, search, locking | Import/view/export round trips; original retained on failure; relock and process-death checks |
| 3: Data safety | Optional original removal, manual backup/restore, PIN recovery, storage/provider failures, crash reconciliation | Clean-device restore using recovery key; denied deletion and interrupted-write tests; failure leaves existing vault intact |
| 4: Release hardening | Accessibility, device matrix, backup exclusions, dependency/security review, privacy notice, truthful store assets and reviewer instructions | Signed release candidate; device evidence; documented limitations; all acceptance gates passed |

Keep setup instructions short and executable: required JDK/SDK versions, SDK location, device setup, and commands such as `./gradlew.bat assembleDebug`, `./gradlew.bat testDebugUnitTest`, and `./gradlew.bat lintDebug`. Add instrumented/device commands once the test configuration exists. Use synthetic test media and temporary vaults; never require a developer's personal files.

The first technical gate is a vertical slice: create vault → import a large video → seek in it → lock → unlock → export the original bytes. Add backup/clean-device recovery before allowing a release containing anyone's only copy of a file.

## Acceptance criteria and verification

1. Calculator handles precedence, negative and decimal input, percentage rules, repeated operators, overflow/input limits, and divide-by-zero without crashes. Document percent semantics and test them. Ordinary equals never opens a PIN-matching vault accidentally.
2. Wrong PIN, malformed input, process restart, and clock changes respect the attempt policy. Deep links, restored navigation, and exported components cannot bypass authentication.
3. Import/export round trips are byte-identical for empty files, images, documents, Unicode filenames, and a video of at least 2 GiB. Peak memory remains bounded independently of video size; measure and record it on a reference device.
4. Bit flips, swapped objects/headers, truncation, incorrect keys, and invalid metadata authentication fail safely. Authenticated streaming playback does not release unauthenticated segments; a full import/restore requires complete-stream verification.
5. Kill the process at staging, promotion, catalog commit, deletion consent, backup write, and restore commit. Originals and the existing vault survive every failed operation; incomplete objects are reconciled.
6. After import, locked app storage, database/WAL, caches, logs, and system backups contain no plaintext media, thumbnails, sensitive names, PIN, or recovery secret. Verify using known synthetic strings and files; document unavoidable size/count leakage.
7. Screen-off, Home, Recents, app switching, rotation, picker return, video pause/resume, and process recreation never reveal an unlocked frame or resume playback without authorization. Check screenshot and recording behavior on actual devices.
8. Restore a verified backup on a clean second installation with the recovery key and a new PIN, without the original Keystore key. A wrong key or damaged archive commits no vault. Existing-device PIN recovery also works after loss/invalidation of the device key.
9. Denied deletion, nondeletable/cloud providers, revoked grants, unknown stream sizes, cancellation, and storage exhaustion produce accurate per-file results; none falsely report that an original was hidden or removed.
10. Cover API 29 and the current release emulator plus at least two physical devices from different vendors before release. Test TalkBack, larger text, low-memory conditions, and both themes. Review the crypto/key lifecycle and portable format before production use.

## Completed verification and outstanding work

Executed for this planning pass: read the requested autoplan skill; inspect workspace and checked ancestor instructions; inspect available toolchain commands/default paths; review official Android, Google Play, Tink, and RFC sources.

No application code, builds, automated tests, emulators, or device checks have been executed. Those checks await implementation and a working Android SDK/device environment. Exact dependency versions, Android toolchain availability, Argon2 implementation compatibility, streaming playback integration, and device-specific protection behavior are implementation gates, not proven properties.

Artifact produced: this implementation plan. No unresolved product decision blocks starting the first phase; the defaults above make the next work concrete. Planning stops here under the requested autoplan workflow.
