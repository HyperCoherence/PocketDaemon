# Contributing

Contributions are welcome, but keep the privileged nature of the app in mind.

## Development

```powershell
flutter pub get
flutter analyze
flutter build apk --release
```

## Rules

- Do not commit API keys, local config, recordings, transcripts, memory files,
  notes, extracted firmware, generated APKs, or Magisk zips.
- Keep permission and root-script changes small and explicit.
- Update docs when changing install steps, permissions, tools, storage paths, or
  model/provider behavior.
- Add tests for shared parsing, config, memory, and tool behavior when practical.

## Release Artifacts

Release APKs and Magisk zips belong in GitHub Releases, not in git history.
