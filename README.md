# PocketDaemon

![PocketDaemon: talk to your phone agent](docs/social/pocketdaemon-hero.png)

PocketDaemon is an experimental Android-native AI agent that can answer and place
phone calls, run live voice/chat sessions, use phone tools, keep local memory,
take notes, schedule tasks, and run extensible skills.

The project is built as a Flutter app with Android/Kotlin services and an
optional Magisk module that installs the app as a privileged system app. That
privileged path is what enables call handling, telephony audio, call recording,
contacts, SMS, location, and assistant-role integration.

## Status

This is research software for rooted Android devices. It is not ready for
general consumer installation, app-store distribution, or use where missed
calls, unwanted SMS, location exposure, or recording-law compliance would create
material risk.

## Features

- AI receptionist for incoming calls
- Live full-duplex voice conversation
- Text chat with image input
- Trusted caller tiers
- Outbound calls from chat
- Notes, notifications, recordings, and session history
- Local memory stored under `/sdcard/PocketDaemon/`
- Scheduled tasks
- Skills loaded from Markdown files
- Provider-selectable realtime voice through Gemini or xAI
- Text chat, expert advisor, scheduled tasks, and memory on Gemini, xAI, or Claude
- "Ask Fable": consult Claude Fable 5.1 with live web search from a conversation
- Optional provider search grounding through Gemini or xAI voice sessions

See [FEATURES.md](FEATURES.md) for the full current feature list.

## Requirements

- Rooted Android device with Magisk
- Flutter SDK
- Android SDK / platform tools
- A Gemini API key, xAI API key, or both; an Anthropic API key for Claude and the Ask Fable tool
- A device and jurisdiction where call handling/recording behavior is lawful

## Build

```powershell
flutter pub get
flutter build apk --release
powershell -File build_magisk.ps1
```

The release APK, Magisk zip, and SHA-256 checksums are generated locally under
`releases/` with versioned filenames. Keep that directory out of git; when you
create a public release later, attach those generated files to the release. Do
not commit generated APKs, zips, extracted firmware, local configs, recordings,
notes, or skills.

## Configuration

The app stores runtime data under `/sdcard/PocketDaemon/`. For provisioning, copy
`pocketdaemon_config.example.json` to `pocketdaemon_config.json`, fill in local
values, and place it in `/sdcard/Download/` before launching the app. The app
imports supported keys and deletes the sideload file after a successful merge.

The USB tool is intended to be the easier first-run path, but it is currently
experimental and has not yet been validated end to end on a device:

```powershell
cd tools/pocketdaemonctl
go run . doctor
go run . install --module-zip ..\..\releases\PocketDaemon-<version>-<versionCode>-magisk.zip
go run . setup
```

`setup` opens a localhost browser editor for the voice provider, API keys,
identity, `CALL_PROMPT.md`, `memory/SOUL.md`, trusted contacts, skills, scheduled
tasks, and advanced memory files. Non-interactive setup is also available:

```powershell
go run . setup --provider xai --api-key "$env:XAI_API_KEY" --apply
```

Never commit real API keys or personal config exports.

## Security And Privacy

This app can access sensitive phone capabilities when installed as a privileged
system app. Review [SECURITY.md](SECURITY.md), [PRIVACY.md](PRIVACY.md), and
[docs/threat-model.md](docs/threat-model.md) before publishing or installing.

## License

MIT. See [LICENSE](LICENSE).
