# CalVault

A native Android calculator with a private, encrypted file vault. **v0.2.1 is an Android preview** with nested folders, verified file moves, a recycle bin, media playlists/slideshows, themes, and recovery tools. See the [full change list](CHANGELOG.md).

Open a folder and tap the **New folder** icon to create a subfolder. Breadcrumbs and Back navigate the hierarchy; parent rename, recycle-bin restoration, and backups preserve the complete subtree.

Download [CalVault-v0.2.1.apk](https://github.com/Alkadi-940/CalVault/releases/download/v0.2.1/CalVault-v0.2.1.apk) from the [v0.2.1 release](https://github.com/Alkadi-940/CalVault/releases/tag/v0.2.1). Its checksum is in [SHA256SUMS.txt](artifacts/SHA256SUMS.txt). It is an optimized, non-debuggable build signed with the same local Android development key as v0.1.0 and v0.2.0, so it can upgrade those previews without uninstalling. Create a verified backup first. Production signing is not configured.

On an Android 10 or newer phone, download and open the APK. If Android prompts, allow your browser or file manager to install it. The launcher name is **Calculator**. Create a PIN, save your recovery key separately, then enter your PIN and **hold `=`** to unlock.

## What is implemented

- A working calculator with operator precedence, decimals, percent, backspace, and sign change.
- A 6–12 digit PIN entrance: enter the digits and **hold `=`**. An ordinary tap on `=` calculates. An accessible unlock action is available in the menu.
- First-run PIN and recovery-key setup, with confirmation of two recovery-key groups.
- Device-only encrypted photos, videos, and arbitrary files; folder-first browsing, per-folder filters, search, rename/move, multi-selection, sorting, and grid/list layouts.
- Multi-file imports request original removal after full verification, with Android gallery consent, pending status, and retry controls. Providers can deny deletion; unfinished moves remain clearly marked.
- Unhide to the original supported location or a selected folder, with verification before removal from the vault. No export-as-copy flow.
- Manual recycle bin with file/folder restoration, selected permanent deletion, and Empty bin confirmation.
- Encryption/Hidden import labels and configurable defaults. Both labels keep file contents encrypted.
- Folder video playlists, repeat/shuffle, configurable double-tap seeking, fullscreen/rotation, image zoom and folder slideshows with timing/transitions/shuffle.
- Customizable background/screen-off locking and encrypted context restoration after authentication, including media position and settings.
- System/Light/Dark/AMOLED themes with preset or custom accent colors.
- Non-destructive automatic/manual retrieval and retrieval of missing/damaged files from matching backups; device migration instructions in Settings.
- Manual encrypted `.calvault` backups with read-back verification and recovery on a fresh installation.
- PIN changes and recovery with a separately saved recovery key.
- Protected windows, persistent attempt delays, and Android backup/transfer exclusions. PIN and separately saved recovery key are the only unlock paths; fingerprint access is not included.
- No Internet permission, account, analytics, ads, or backend.

The launcher label is **Calculator**. The welcome screen and documentation explain the vault. Android settings and installation records can still reveal the app.

## Run and build

Requirements: JDK 21, Android SDK Platform 36, Build Tools 35.0.0, and Android 10/API 29 or later on a device or emulator.

Open this directory in Android Studio, or set your local SDK path in an untracked `local.properties` file:

```properties
sdk.dir=C:/path/to/Android/Sdk
```

Then use the checked-in Gradle wrapper:

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
.\gradlew.bat :core:test -PlargeFileTest=true
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:assembleRelease
```

The large-file test writes an encrypted 2 GiB synthetic file, verifies it completely, seeks near its end, and compares the exported bytes using SHA-256. It requires at least 3 GiB of free disk space. Normal test runs skip it unless the property is enabled.

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. The release task produces an **unsigned** optimized APK; production signing credentials are intentionally not included. Keep a stable signing key for upgrades when preparing distribution.

The local development tools downloaded for this workspace are under the ignored `.tools/` directory. Dependency versions are pinned in `gradle/libs.versions.toml`, artifact checksums are recorded in `gradle/verification-metadata.xml`, and the wrapper validates the Gradle distribution checksum. Review dependency changes deliberately; recorded checksums are not an independent security audit.

## Use it

1. Create a vault, choose a numeric PIN, and save the recovery key separately. Confirm the requested groups to finish setup.
2. Create a folder, tap **Hide files**, choose an import mode, then select multiple media/documents. Authenticate again if your lock setting requires it. Approve Android's original-removal request when shown; check any pending-move warning.
3. Open files inside their folder. Hold a file or use its menu to select it, rename/move, unhide, or move it to the recycle bin. Permanent deletion is available only inside the bin.
4. Use **Settings → Migration → Create encrypted backup**. Select a destination and keep the app open while the archive is written and reread for verification.
5. On a new installation, choose **Restore an encrypted backup**, select the archive, and enter its recovery key and a new PIN. This version restores only when no vault has been initialized.

By default, leaving for a system picker locks the vault; disable **Settings → Security → Lock when leaving the app** to keep the current session open while switching apps. A killed process always requires authentication. The encrypted bookmark restores browsing/media context after unlocking. Returned picker results can recover their saved destination/action; transfers interrupted by process death should be checked and unfinished files selected again.

Android controls original deletion and folder access. Read-only or unsupported providers can leave an original visible; CalVault does not claim the move succeeded in that case. The recovery key restores access to surviving ciphertext, not deleted bytes without a backup. Uninstalling or clearing app data removes the local vault. Unhidden files, external originals, and existing backups remain outside later vault deletion.

## Architecture

`core/` contains calculator parsing, cryptography, encrypted storage, backup, recovery, and attempt-delay logic. `app/` contains Compose screens, Android Keystore integration, lifecycle locking, file-picker integration, and a Media3 data source.

A small, atomically replaced **encrypted catalog** is the storage commit point instead of Room. Immutable encrypted blobs are promoted before the catalog update; authenticated per-file sidecars support non-destructive retrieval, and deletion markers suppress automatic resurrection. Sensitive catalog fields and navigation context are encrypted without a plaintext database or WAL. The catalog is capped at 10,000 items and 32 MiB.

See [security and format notes](docs/SECURITY.md), the [implementation plan](docs/CalVault-implementation-plan.md), and [verification evidence](docs/VERIFICATION.md).

## Remaining release gates

- Physical-device testing on multiple vendors, including backgrounding, recording/screenshot restrictions, backup exclusion behavior, storage exhaustion, and slow/cloud document providers.
- More complete fault injection around process termination and provider operations.
- Accessibility and layout checks across orientation, larger font sizes, and TalkBack.
- Independent review of the key lifecycle and portable archive format before production use.
- Production signing, dependency notices, privacy/store documentation review, and Play submission. No store publication has occurred.
