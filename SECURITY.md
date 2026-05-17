# Security Policy

PocketDaemon is experimental and has a high-risk permission profile when installed
through Magisk. Treat it like privileged system software.

## Supported Versions

Only the latest public `main` branch is expected to receive fixes.

## Reporting Vulnerabilities

Do not open public issues for secrets, bypasses, prompt-injection paths that can
send SMS or place calls, or bugs that expose recordings, contacts, location, or
memory files. Use GitHub private vulnerability reporting once the public
repository is created. Until that is enabled, do not publish the repository.

## Sensitive Surfaces

- Provider API keys for Gemini, xAI, or future model providers
- Call audio and recordings
- SMS and outbound calling tools
- Contacts and trusted-caller prompts
- Location data
- `/sdcard/PocketDaemon/` memory, notes, logs, recordings, photos, skills, and config
- Magisk boot scripts and privileged permission grants

## Maintainer Checklist

- Rotate any key that was ever committed.
- Scan current files and git history before publishing.
- Keep release APKs out of git; attach signed artifacts to releases.
- Document permission changes in release notes.
