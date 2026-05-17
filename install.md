# Installation

PocketDaemon currently targets rooted Android devices with Magisk. The Magisk
module installs the Flutter APK as a privileged system app and grants the
permissions needed for telephony, audio, contacts, location, notifications, and
other agent tools.

## Prerequisites

- Rooted Android device with Magisk installed
- Flutter SDK on the build machine
- Android SDK platform tools and a working `adb`
- Gemini API key, xAI API key, or both
- Go 1.22+ for the USB installer/configuration tool

## Build

From the repository root:

```powershell
flutter pub get
flutter build apk --release
powershell -File build_magisk.ps1
```

This creates versioned release artifacts under `releases/`:

- `PocketDaemon-<version>-<versionCode>.apk`
- `PocketDaemon-<version>-<versionCode>-magisk.zip`
- `SHA256SUMS.txt`

Keep `releases/` out of git. Attach these files to a GitHub Release later; do
not commit binary release artifacts to the repository.

## Install and Configure with USB

From the repository root:

```powershell
cd tools/pocketdaemonctl
go run . doctor
go run . install
```

`install` checks ADB authorization, root, Magisk, pushes the module zip to
`/sdcard/Download/`, attempts Magisk command-line installation, waits for reboot,
then launches the setup flow. If Magisk command-line installation is unavailable,
install the pushed zip manually from the Magisk app and reboot.

Run setup directly any time:

```powershell
go run . setup
```

The setup command opens a localhost browser editor, pulls `/sdcard/PocketDaemon/`
over USB, and writes changes back to the phone. It manages the voice provider,
Gemini/xAI API keys, model, voice, owner identity, `CALL_PROMPT.md`,
`memory/SOUL.md`, trusted contacts, skills, scheduled tasks, and advanced memory
files.

Non-interactive setup is supported:

```powershell
go run . setup --provider xai --api-key "$env:XAI_API_KEY" --model grok-voice-think-fast-1.0 --voice eve --apply
```

## Manual Sideload Configuration

Copy `pocketdaemon_config.example.json` to `pocketdaemon_config.json`, fill in your
local values, then push it to the device:

```powershell
adb push pocketdaemon_config.json /sdcard/Download/
```

The app imports the supported keys on launch and deletes the sideload file after
the merge.

## Backup and Restore

```powershell
cd tools/pocketdaemonctl
go run . backup --out pocketdaemon-backup.zip
go run . restore pocketdaemon-backup.zip
```

## Update

Rebuild the APK and Magisk zip, flash the new module in Magisk, and reboot.

## Uninstall

Disable or remove the module in Magisk, reboot, then remove `/sdcard/PocketDaemon/`
manually if you also want to delete local memory, notes, recordings, photos, and
configuration.
