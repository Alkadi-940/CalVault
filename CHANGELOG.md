# Changes

## 0.2.0 — Android preview

### Vault and files

- Folder-first home with explicit folder creation, rename, empty folders, folder search, counts, and sizes. All/Photos/Videos/Files filters now live inside each folder.
- File search, grid/list switching, sorting by date/name/size, multi-selection, rename/move, details, and memory-only image thumbnails.
- Recycle bin for files and entire folders, including empty folders. Restore individually, selected, or all; permanently delete selected items or empty the bin with confirmation. No automatic expiry or background purge.
- Multi-file import for photos, videos, and arbitrary documents. Imports now attempt a move: write encrypted data, authenticate and reread both copies, then request removal from the source provider.
- Android gallery removal consent, batching where supported, pending-move badges/results, and retry controls. Denied/read-only sources keep a verified vault copy and are explicitly reported as unfinished, rather than falsely reported hidden.
- Unhide to the original supported album/folder or a selected destination folder. Verify the destination before removing the vault object; avoid overwriting name collisions. Older imports may need the user to choose the original folder again.
- Removed export-as-copy from the app.
- Encryption and Hidden labels, with per-import choice and Always ask / Always Encryption / Always Hidden defaults. **Both labels keep the same authenticated file encryption**, as requested.

### Locking and continuity

- Independent settings for locking on app switching and screen-off. Default remains locked on leaving; disabling it keeps an existing session open during app switching. A restarted process still needs the PIN.
- Encrypted last-location bookmarks restore folder, filter, search, selection, scroll position, viewer, video position/queue, slideshow context, image zoom/pan, and settings section/scroll after authentication.
- Remember import destination/mode and outstanding unhide context across a system-picker process recreation. System removal receipts retain encrypted context until the matching result is processed.
- Rotation keeps the existing player and vault session. Media pauses while backgrounded; screen capture protection stays enabled.
- Fingerprint unlock was removed completely at the user's request: no permission, prompt, wrapper, shortcut, or setting remains.

### Media and appearance

- Videos in a folder form a playlist, with next/previous track controls, repeat-current, and shuffle that visits each track before repeating the cycle.
- Double-tap right/left to seek, with independently configurable forward/back intervals of 1–30 seconds. No timed forward/back buttons.
- Timeline seeking, playback errors, fullscreen controls, and automatic landscape video presentation.
- Folder image slideshow, pause/resume, next/previous, shuffle without repeats within a cycle, adjustable 1–60 second interval, and Fade/Slide/None transitions.
- System, Light, Dark, and AMOLED themes; ten accent presets and custom RGB color, with text contrast adjustment. Shared accents apply to calculator and vault controls.

### Recovery and migration

- Authenticated encrypted per-file recovery records and preserved catalog snapshots enable non-destructive automatic retrieval on unlock and a manual scan.
- Missing files are identified without blocking access to healthy files. Recovery does not erase unidentified blobs, partial files, or damaged catalog snapshots.
- Retrieve missing/damaged files from a matching encrypted backup while keeping healthy files; permanent-deletion markers prevent automatic resurrection.
- Step-by-step device migration instructions in Settings, with backup creation and verification.
- Backup version 2 preserves folders, bin metadata, mode labels, and encrypted browsing/media context. Version 1 vaults and backups remain readable; existing file ciphertext is not rewritten during upgrade.
- Android directory durability checks before original removal, stronger move status reporting, and broader core/platform integration tests.

### Limits

Android and storage providers control deletion and folder access; a read-only/cloud provider cannot be forced to remove an original. Confirm outstanding moves in Storage settings. Retrieval requires intact encrypted data/recovery records or a matching backup. Permanent deletion removes CalVault's owned object/recovery copies on this device; it cannot erase external copies, backups, or guarantee forensic erasure of flash cells. PIN/key entry and an interrupted transfer are never blindly replayed after restart.
