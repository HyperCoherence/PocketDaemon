# Release Checklist

Before making the repository public:

- [ ] Revoke any API key that was ever committed.
- [ ] Rewrite git history to remove keys, personal configs, APKs, Magisk zips,
      and extracted firmware.
- [ ] Run a secret scan on current files and full history.
- [ ] Confirm `git status` is clean from the intended publish root.
- [ ] Enable GitHub private vulnerability reporting before making the repo public.
- [ ] Run `flutter analyze`.
- [ ] Run `flutter build apk --release`.
- [ ] Run `go test ./...` in `tools/pocketdaemonctl`.
- [ ] Build `tools/pocketdaemonctl` for supported host platforms.
- [ ] Sign official release artifacts with a maintainer-controlled key.
- [ ] Generate APK/Magisk zip/checksums under `releases/` and attach them to a
      release instead of committing them.
- [ ] Update version numbers in `pubspec.yaml` and `magisk/module.prop`.
- [ ] Review permission changes and root scripts.
- [ ] Verify README, install docs, privacy docs, and threat model are current.
