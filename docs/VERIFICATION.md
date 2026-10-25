# Verification record

Implementation verification on 2026-10-25. This record distinguishes executed checks from remaining release work.

## Subfolder update (v0.2.1)

- 38 core tests passed; the existing opt-in large streaming test was skipped because encryption/streaming code did not change. Five new hierarchy tests cover sibling names, the depth bound, parent rename, empty descendants, recursive bin/restore/permanent deletion, backup restore, and recovery of long nested paths. An independent version 2 fixture checks migration and literal slash names alongside version 1 compatibility tests.
- Android 10/API 29 and Android 16/API 36 passed actual nested-folder creation, immediate-child browsing, breadcrumbs/parent navigation, subtree rename, recycle-bin restoration including empty descendants, authenticated location resume, and landscape layout. Tests upgraded the retained synthetic v0.2.0 vaults.
- The Android 10 AOSP test image booted with an empty `dalvik.vm.heapgrowthlimit` (ART defaulted to 16 MiB). Its test runtime was configured to 256 MiB and restarted before the passing run; no app cryptography settings were weakened.
- Debug lint and the optimized release build passed. The signed local APK uses version code 4 and the existing preview signing identity. New backups preserve the hierarchy in catalog format 3 and need this app version or newer.
- The exact signed APK was installed and unlocked through the calculator on both emulators, restoring the nested folder and its file after R8 optimization.
- Evidence: `docs/evidence/subfolders/`; synthetic screenshots: `docs/screenshots/subfolders/`.

## v0.2.0 feature verification

- **33 core tests passed, zero failures/skips**, including the 2 GiB + 123 byte streaming regression. Added independent legacy v1 catalog/archive fixtures, folder/bin state, encrypted mode labels/source metadata bounds, authenticated recovery, missing-file handling, backup retrieval, and shuffle-cycle coverage.
- Existing synthetic three-file v0.1.0 vaults upgraded on Android 10/API 29 and Android 16/API 36 with real Android Keystore access. Files retained their exact encrypted blob bytes, and a legacy catalog snapshot was preserved. The legacy test is opt-in (`-e legacyFixture true`) and must run before clearing that fixture.
- Onboarding/PIN recovery/backup restore and encrypted-video playback/seek passed on both platforms.
- Both platforms passed actual MediaStore original-removal consent and unhide to the original album; multi-document moves with granted URI access; unhide/read-back verification; and an explicitly read-only source that stays pending with its original intact. A test-only DocumentsProvider and fixture-owning Activity are packaged only in the test APK.
- Both platforms passed folder/file recycle-bin restoration, confirmed permanent deletion, three-track shuffle uniqueness, repeat-current, double-tap seeking in both directions, player rotation, optional background locking, and encrypted folder/selection bookmark restoration.
- Both platforms passed folder slideshow timing/shuffle, image rotation, theme rendering, and the seven settings sections. Synthetic screenshots are in `docs/screenshots/v0.2.0/`.
- Both platforms restored the paused video's saved position after an actual app force-stop. Recreated models also resumed returned import/unhide picker results using encrypted saved action context.
- Fingerprint functionality, permission, UI, key wrappers, and tests were removed at the user's request. No biometric unlock path is present in this release.
- Debug lint reports no issues. Optimized release build and release vital lint pass. No new external dependency was added for these features.
- The exact signed v0.2.0 APK (version code 3) was installed and unlocked through calculator input on both API 29 and 36 after R8 optimization, restoring the saved video viewer. Its signature matches v0.1.0, ZIP alignment meets 16 KiB checks, and its manifest is non-debuggable with no Internet, biometric, or broad storage permission. The final checksum is recorded in `artifacts/SHA256SUMS.txt`.

Raw feature logs are in `docs/evidence/v0.2.0/`. The earlier v0.1.0 evidence below remains historical; its original-retention/export behavior was replaced by the v0.2.0 move/unhide flow.

For a fresh dedicated emulator, run the onboarding test first, then FeatureIntegrationTest and ViewerStateTest's photoSlideshowRotationAndAppearance method. For a real process restart, run prepareRestartBookmark with `-e restartPhase prepare`, force-stop the app, then run verifyRestartBookmark with `-e restartPhase verify`. Never clear a personal vault to run tests. The staged fixture tests use synthetic files only.

## Executed

- Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.2.21, JDK 21, compile/target SDK 36, minimum SDK 29.
- Official Gradle distribution SHA-256 validation; Android SDK/emulator/image archive hashes checked against official repository metadata.
- Debug APK and Android test APK build, plus a final offline build using the recorded dependency checksums.
- Optimized unsigned release APK build with R8 and release lint.
- Android debug lint: no issues found on the completed check.
- Calculator parsing/error tests; AES-GCM context/tampering checks; RFC 9106 Argon2id vector; recovery checksum and key-purpose separation.
- Encrypted import/export/seek, empty-file handling, Unicode names, rename/folder/deletion, failed-source cleanup, orphan reconciliation, wrong-key/corrupted backup rejection, independent-device backup restore, PIN recovery, source-removal revalidation, and locked-handle checks.
- A lock injected while the PIN-unwrapping path is running cannot publish an unlocked session; cancellation during setup key wrapping cannot initialize a vault afterward.
- Persistent attempt delays survive process reconstruction and reboot identity changes.
- 2 GiB + 123 byte deterministic source: encrypted import, full verification, seek near EOF, and SHA-256-matching export, with a 1 GiB JVM test-heap ceiling. This is a JVM streaming regression, not a claim about Android memory/performance for a 2 GiB video.
- Android 16/API 36 and Android 10/API 29 emulator flows: real Keystore setup, recovery-key confirmation, photo preview, calculator tap-versus-hold unlock, PIN recovery, backup verification, and playback/seek of an actual 3 MiB MP4 through the encrypted Media3 data source.
- Restore using a separate Android Keystore alias and byte-identical exported photo; actual activity backgrounding relocks the vault on both platforms.
- Landscape calculator checks assert that equals remains at least 48 dp tall. Dark theme inspected on API 36 and light theme on API 29. Android 10 keyboard transition regression corrected using explicit resize behavior and keyboard dismissal when leaving forms.
- **18 core tests passed, zero failures/skips** in the large-file-enabled run. Final end-to-end flow passed on both API 29 and API 36. Android debug lint reports no issues.
- The exact delivered optimized APK was installed, launched, and unlocked through calculator taps/hold after R8 optimization. APK Signature Scheme v3, 16 KiB ZIP alignment, a non-debuggable manifest, and absence of Internet permission were checked. Its SHA-256 is saved alongside the artifact.

The installable alpha uses the local development signing key, not a production distribution identity. Raw final run logs and the core summary are saved under `docs/evidence/`. Actual screenshots are under `docs/screenshots/qa/` (Android 16, dark) and `docs/screenshots/android10/` (Android 10, light).

## v0.1.0 packaging

The GitHub preview package sets Android version name `0.1.0` and version code `2`. Application functionality is unchanged from the tested alpha above. The optimized release build and debug lint were rerun offline. The signed APK's version fields, non-debuggable manifest, absence of Internet permission, APK signature, and 16 KiB ZIP alignment were verified. Its signing certificate matches the initial alpha so upgrades retain compatibility. The release checksum is in `artifacts/SHA256SUMS.txt`.

## How to reproduce

```powershell
.\gradlew.bat :core:test -PlargeFileTest=true :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease
.\gradlew.bat :app:connectedDebugAndroidTest
```

Use a dedicated emulator with no existing CalVault vault for the Android onboarding flow. The included MP4 is synthetic FFmpeg test pattern/audio and exists only in the test APK. Screenshots contain synthetic filenames and a generated solid-color image. Recovery secrets are not captured.

Gradle normally removes the test application/data after connected tests. To retain synthetic QA screenshots, install both APKs with adb and invoke `am instrument -w dev.calvault.test/androidx.test.runner.AndroidJUnitRunner` directly, then pull the target app's external `files/qa/` folder before uninstalling.

Raw local reports: `core/build/test-results/test/`, `app/build/reports/lint-results-debug.html`, and `app/build/reports/androidTests/connected/`. Reports and build tools are ignored build artifacts.

## Not yet release-verified

- Physical Android devices, vendor-specific Keystore/backup/capture behavior, StrongBox availability, and device-to-device migration exclusions.
- Exhaustive crash/low-storage/provider revocation injection, including power loss at filesystem commit points.
- Full media codec/format coverage and actual 2 GiB video playback/memory measurement on minimum-spec Android hardware.
- TalkBack, comprehensive large-font/window-size layouts beyond the tested calculator orientation, and real cloud/third-party document providers.
- Independent cryptographic review, store approval, production signing, and a published Play listing.
