---
name: pocketdaemon-install
description: >-
  Build, deploy, install, configure, verify, back up, restore, and troubleshoot
  PocketDaemon on rooted Android devices with Magisk. Use when asked to build
  release artifacts, install or update the app, set up a device, root a Pixel,
  configure providers, run pocketdaemonctl, or diagnose ADB/Magisk issues.
---

# PocketDaemon Install and Deploy

Use this skill from the repository root unless a command explicitly changes
directories. PocketDaemon is experimental software for rooted Android devices;
installation touches telephony, audio capture, contacts, location, SMS, local
memory, recordings, and API keys.

## Privacy and Safety Rules

- Do not commit or publish API keys, `pocketdaemon_config.json`, local backups,
  logs, recordings, photos, memory, notes, contacts, device serials, downloaded
  firmware, APKs, Magisk zips, or extracted images.
- Use placeholders such as `<factory_zip_url>`, `<serial>`, `<api_key>`, and
  `<module_zip>` in public instructions.
- Before sharing command output, redact API keys, phone numbers, contacts,
  device serials, personal names, location data, and long opaque tokens.
- Prefer environment variables for keys. Do not paste real keys into commands,
  docs, commits, issues, or PRs.
- Fresh bootloader unlocks wipe user data. Do not continue unless the user has
  explicitly accepted the wipe and backup risk.

## Current Status

`tools/pocketdaemonctl` is present but should be treated as untested and
experimental until it has passed an end-to-end device install. Run `doctor`
first, keep exact errors for maintainers, and fall back to the manual Magisk
install path when the tool fails.

The current build script writes versioned artifacts under `releases/`.
`pocketdaemonctl install` still searches older unversioned zip locations by
default, so pass `--module-zip` explicitly when using it.

## Project Identifiers

- App name: `PocketDaemon`
- Android package: `com.pocketdaemon.pocket_daemon`
- Magisk module ID: `pocketdaemon`
- Runtime data root: `/sdcard/PocketDaemon/`
- Version source: `pubspec.yaml` (`version: <semver>+<versionCode>`)
- Build host in this repo: Windows / PowerShell
- Magisk privileged app path: `magisk/system/priv-app/PocketDaemon/PocketDaemon.apk`
- Privileged permission whitelist:
  `magisk/system/etc/permissions/privapp-permissions-pocketdaemon.xml`

## Supported Devices

Any Android device can be considered only if it supports OEM bootloader
unlocking and Magisk. Devices launched with Android 13 or newer generally place
the generic ramdisk in `init_boot.img`; many older devices use `boot.img`.
Confirm against the factory image contents and Magisk's install guidance for
the specific device. Non-Pixel devices need device-specific rooting
instructions.

Use a current Magisk release that is compatible with the target Android version.
Do not reuse old patched boot or init_boot images across builds.

## Device Discovery

Always check both ADB and fastboot. After a bootloader unlock, failed boot, or
manual reboot, the phone may be in fastboot instead of Android.

```powershell
adb devices
fastboot devices
```

If `fastboot devices` returns a serial, the phone is in fastboot mode. Use
`fastboot reboot`, wait for Android to boot, then re-check `adb devices`.

When Android is booted and USB debugging is authorized, collect public device
facts needed for factory image selection:

```powershell
adb shell getprop ro.product.model
adb shell getprop ro.build.display.id
adb shell getprop ro.product.device
```

## Pixel Rooting Reference

For Pixel factory images, use only Google's official factory image page:

```text
https://developers.google.com/android/images#<codename>
```

The build ID must match the phone's build number exactly. Do not use mirror
sites, unofficial firmware archives, or beta/QPR pages unless the user is
intentionally installing that exact build.

The Google page may require a browser to render download links and accept the
terms. If using a browser, locate the row matching the phone codename and build
ID, then use the direct `dl.google.com` factory image URL from that row.

For large downloads on Windows, use `curl.exe`, not PowerShell's
`Invoke-WebRequest`. Use `7z` for nested factory image archives when
`Expand-Archive` fails.

```powershell
curl.exe -L -o factory.zip "<factory_zip_url>"
Expand-Archive factory.zip -DestinationPath factory_extract -Force
7z e "factory_extract\*\image-*.zip" init_boot.img -oimage_extract -y
```

For devices that use `init_boot.img`, patch `image_extract\init_boot.img` in
Magisk. For devices that use `boot.img`, extract and patch `boot.img` instead.

## Fresh Device Setup

Only do this once per device. It wipes data and can leave the device unbootable
if the wrong image is flashed.

1. Enable Developer Options: Settings > About phone > tap Build number 7 times.
2. Enable USB debugging and OEM unlocking.
3. Unlock the bootloader:

```powershell
adb reboot bootloader
fastboot flashing unlock
```

Confirm the unlock on the device. After the wipe, complete Android setup and
re-enable USB debugging.

4. Download the official factory image matching the exact build ID.
5. Extract the correct `init_boot.img` or `boot.img`.
6. Push the image to the phone:

```powershell
adb push image_extract\init_boot.img /sdcard/Download/
```

7. Install Magisk, patch the image in the Magisk app, then pull the patched file:

```powershell
adb pull /sdcard/Download/magisk_patched-*.img .
```

8. Flash the patched image:

```powershell
adb reboot bootloader
fastboot flash init_boot magisk_patched-*.img
fastboot reboot
```

For devices that use `boot.img`, replace `init_boot` with `boot`.

9. Open Magisk and confirm root status before installing PocketDaemon.

## Build Release Artifacts

From the repository root:

```powershell
flutter pub get
flutter build apk --release
powershell -File build_magisk.ps1
```

Expected outputs:

- `releases/PocketDaemon-<version>-<versionCode>.apk`
- `releases/PocketDaemon-<version>-<versionCode>-magisk.zip`
- `releases/SHA256SUMS.txt`

Do not commit `releases/`, APKs, zips, or generated Magisk app payloads.

## Install or Update an Already Rooted Device

First check connectivity and root:

```powershell
adb devices
adb shell su -c id
```

If using the experimental USB tool, pass the versioned Magisk zip explicitly:

```powershell
cd tools/pocketdaemonctl
go run . doctor
go run . install --module-zip ..\..\releases\PocketDaemon-<version>-<versionCode>-magisk.zip
```

Because `pocketdaemonctl` is untested, be ready to use the manual path:

```powershell
adb push releases\PocketDaemon-<version>-<versionCode>-magisk.zip /sdcard/Download/
```

Then open Magisk on the phone:

```text
Modules > Install from storage > PocketDaemon-<version>-<versionCode>-magisk.zip > reboot
```

If Magisk command-line module installation is available, it may work, but do not
assume it works on every device or Magisk version:

```powershell
adb shell su -c "magisk --install-module /sdcard/Download/PocketDaemon-<version>-<versionCode>-magisk.zip"
adb reboot
```

## Configure PocketDaemon

The app stores runtime data under `/sdcard/PocketDaemon/`.

The experimental USB setup tool can be tried after install:

```powershell
cd tools/pocketdaemonctl
go run . setup
```

It opens a localhost editor for provider keys, voice settings, owner identity,
`CALL_PROMPT.md`, `memory/SOUL.md`, trusted contacts, scheduled tasks, memory
files, and skills.

For non-interactive setup, use environment variables and avoid echoing keys:

```powershell
$env:XAI_API_KEY = "<api_key>"
go run . setup --provider xai --api-key "$env:XAI_API_KEY" --model grok-voice-think-fast-1.0 --voice eve --apply
```

Manual sideload configuration is also supported:

```powershell
Copy-Item pocketdaemon_config.example.json pocketdaemon_config.json
# Edit pocketdaemon_config.json locally without committing it.
adb push pocketdaemon_config.json /sdcard/Download/
```

The app imports supported values on launch and deletes the sideload file after a
successful merge.

## Verify

Manual verification commands:

```powershell
adb shell pm path com.pocketdaemon.pocket_daemon
adb shell pm list packages com.pocketdaemon.pocket_daemon
adb shell dumpsys package com.pocketdaemon.pocket_daemon | findstr "granted=true grantedPermissions"
adb shell su -c "ls /data/adb/modules/pocketdaemon/"
adb shell test -d /sdcard/PocketDaemon && echo present
```

The experimental tool also has a verify command:

```powershell
cd tools/pocketdaemonctl
go run . verify
```

## Backup and Restore

Treat `pocketdaemonctl backup` and `restore` as experimental until tested:

```powershell
cd tools/pocketdaemonctl
go run . backup --out pocketdaemon-backup.zip
go run . restore pocketdaemon-backup.zip
```

Manual backup fallback:

```powershell
adb pull /sdcard/PocketDaemon PocketDaemon-backup
```

Backups may contain API keys, contacts, memory, notes, logs, recordings, photos,
skills, and personal configuration. Do not commit or share them.

## Troubleshooting

| Symptom | Action |
| --- | --- |
| `adb devices` is empty | Check `fastboot devices`; the phone may be in fastboot. Also check cable, USB mode, and debugging prompt. |
| `adb devices` shows unauthorized | Revoke USB authorizations on the phone, reconnect, and accept the prompt. |
| Multiple devices are connected | Pass `-s <serial>` to ADB or `--serial <serial>` to `pocketdaemonctl`. Redact serials in public logs. |
| `flutter build` fails | Fix source or dependency errors, then rebuild before packaging. |
| `build_magisk.ps1` says APK not found | Run `flutter build apk --release` first. |
| `pocketdaemonctl install` cannot find a module zip | Pass `--module-zip ..\..\releases\PocketDaemon-<version>-<versionCode>-magisk.zip`. |
| Root is unavailable through ADB | Confirm Magisk root is installed and ADB shell has root access, or install the module manually in Magisk. |
| Magisk CLI install fails | Use Magisk app > Modules > Install from storage, then reboot. |
| App is missing after reboot | Re-flash the module, reboot again, then verify `/data/adb/modules/pocketdaemon/`. |
| `pm path` is empty after a flash and logcat says `System package ... no longer exists; its data will be wiped` | The APK in the zip was unsigned (no `android/key.properties` and no debug fallback). Check with `apksigner verify --print-certs`; every shipped module is signed with the Android debug key, so re-sign or rebuild, repackage, and re-flash. |
| Permissions are not granted | Run `adb shell su -c "sh /data/adb/modules/pocketdaemon/service.sh"` and restart the app. |
| Magisk reports abnormal state | Re-check that the patched image matches the exact device build. |
| Bootloop after patching | Boot fastboot, flash the stock matching image back to `init_boot` or `boot`, then re-check Magisk/device compatibility. |

## Reference Docs

- `README.md` for project overview and requirements.
- `install.md` for the public install guide.
- `SECURITY.md`, `PRIVACY.md`, and `docs/threat-model.md` before installation.
- `tools/pocketdaemonctl/` for the experimental USB installer/configuration tool.
