# CalVault

A native Android calculator with a private, encrypted file vault. **v0.1.0 is an early Android preview**, intended for testing with copies of files while the remaining release checks are completed.

Download [CalVault-v0.1.0.apk](https://github.com/Alkadi-940/CalVault/releases/download/v0.1.0/CalVault-v0.1.0.apk) from the [v0.1.0 release](https://github.com/Alkadi-940/CalVault/releases/tag/v0.1.0). The APK is approximately 4 MB; its checksum is in [SHA256SUMS.txt](artifacts/SHA256SUMS.txt). It is an optimized, non-debuggable release build signed with this machine's local Android development key. Production signing is not configured. Back up your vault before replacing this preview with a differently signed production build, which Android cannot install as an ordinary update.

On an Android 10 or newer phone, download and open the APK. If Android prompts, allow your browser or file manager to install it. The launcher name is **Calculator**. Create a PIN, save your recovery key separately, then enter your PIN and **hold `=`** to unlock.

## What is implemented

- A working calculator with operator precedence, decimals, percent, backspace, and sign change.
- A 6–12 digit PIN entrance: enter the digits and **hold `=`**. An ordinary tap on `=` calculates. An accessible unlock action is available in the menu.
- First-run PIN and recovery-key setup, with confirmation of two recovery-key groups.
- Device-only encrypted photos, videos, and arbitrary files, with an encrypted catalog, folders, search, renaming, selection, and deletion.
- Verified imports using Android media/document pickers; originals are retained by default.
- Explicit original removal for writable document providers, after verifying both the encrypted copy and that the source is unchanged. Other providers, including photo-picker sources, may require manual removal in the gallery or source app.
- In-app sampled image previews with zoom and authenticated, seekable video playback. Unsupported formats can be exported.
- Explicit export of decrypted copies to a selected destination.
- Manual encrypted `.calvault` backups with read-back verification and recovery on a fresh installation.
- PIN changes and recovery with a separately saved recovery key.
- Automatic background/screen-off locking, protected windows, persistent attempt delays, and Android backup/transfer exclusions.
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
2. Tap **Add files**, choose media or documents, and unlock again after returning from the system picker. Import waits for authentication and reports which copies were verified.
3. Open files inside CalVault. Use the file menu to rename, move to a named folder, export, or delete. Hold a file or use **Select file** for selection and batch deletion.
4. Use **Security & backup → Create encrypted backup**. Select a destination, unlock again, and keep the app open while the archive is written and reread for verification.
5. On a new installation, choose **Restore an encrypted backup**, select the archive, and enter its recovery key and a new PIN. This version restores only when no vault has been initialized.

System picker excursions lock the vault. Imports, exports, and backup work proceed only after reauthentication. Leaving during a transfer cancels it safely; completed imports remain in the vault and unfinished selected items can be retried after unlocking. Process death forgets pending URI selections, so choose unfinished files again.

The recovery key restores access to surviving ciphertext. It cannot recreate deleted data without a backup. Uninstalling or clearing app data removes the local vault. Exported files and original copies are outside CalVault's encryption.

## Architecture

`core/` contains calculator parsing, cryptography, encrypted storage, backup, recovery, and attempt-delay logic. `app/` contains Compose screens, Android Keystore integration, lifecycle locking, file-picker integration, and a Media3 data source.

For this alpha, a small, atomically replaced **encrypted catalog** is the storage commit point instead of Room. Immutable encrypted blobs are promoted before the catalog update; deleted/uncommitted orphan blobs are reconciled on unlock. This keeps all sensitive catalog fields encrypted without a plaintext database or WAL. The catalog is capped at 10,000 items and 32 MiB; migrating to Room is an option if the collection/workload requires it.

See [security and format notes](docs/SECURITY.md), the [implementation plan](docs/CalVault-implementation-plan.md), and [verification evidence](docs/VERIFICATION.md).

## Remaining release gates

- Physical-device testing on multiple vendors, including backgrounding, recording/screenshot restrictions, backup exclusion behavior, storage exhaustion, and slow/cloud document providers.
- More complete fault injection around process termination and provider operations.
- Accessibility and layout checks across orientation, larger font sizes, and TalkBack.
- Independent review of the key lifecycle and portable archive format before production use.
- Production signing, dependency notices, privacy/store documentation review, and Play submission. No store publication has occurred.
