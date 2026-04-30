# Installation

PocketDaemon currently targets rooted Android devices with Magisk. The Magisk
module installs the Flutter APK as a privileged system app and grants the
permissions needed for telephony, audio, contacts, location, notifications, and
other agent tools.

## Prerequisites

- Rooted Android device with Magisk installed
- Flutter SDK on the build machine
- Android SDK platform tools and a working `adb`
- Gemini API key

## Build

From the repository root:

```powershell
flutter pub get
flutter build apk --release
powershell -File build_magisk.ps1
```

This creates `PocketDaemon-magisk.zip` in the repository root.

## Configure

Copy `pocketdaemon_config.example.json` to `pocketdaemon_config.json`, fill in your
local values, then push it to the device:

```powershell
adb push pocketdaemon_config.json /sdcard/Download/
```

The app imports the supported keys on launch and deletes the sideload file after
the merge.

## Install

```powershell
adb push PocketDaemon-magisk.zip /sdcard/Download/
```

Then open Magisk, install the module from storage, and reboot.

## Update

Rebuild the APK and Magisk zip, flash the new module in Magisk, and reboot.

## Uninstall

Disable or remove the module in Magisk, reboot, then remove `/sdcard/PocketDaemon/`
manually if you also want to delete local memory, notes, recordings, photos, and
configuration.
