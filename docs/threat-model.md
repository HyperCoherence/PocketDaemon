# Threat Model

PocketDaemon runs on a rooted device and can operate as a privileged system app.
The main security goal is to prevent untrusted callers, prompts, local files, or
skills from causing unintended phone actions or data exposure.

## Assets

- Phone calls and call audio
- Contacts and trusted contact rules
- SMS capability
- Location
- Camera
- Notes, memory, logs, recordings, and photos
- API keys and model prompts

## Trust Boundaries

- Unknown caller audio enters the model as untrusted input.
- Trusted caller prompts grant broader tool access and must be reviewed.
- Skill files under `/sdcard/PocketDaemon/skills/` can inject instructions and
  fetch data.
- `pocketdaemon_config.json` sideloaded through `/sdcard/Download/` updates app
  configuration.
- Model responses can request tools.

## Current Mitigations

- Tool availability is split by agent tier.
- Runtime tool toggles can disable capabilities.
- Sideload config import ignores runtime-only and unsupported keys.
- SMS tool requires confirmation before sending.
- Contact dialing requires unambiguous matches.

## Open Risks

- Prompt injection from callers, skills, notes, memory, and fetched live data.
- Local files on shared external storage can be edited by other apps or users.
- Root scripts grant broad permissions automatically.
- Legal compliance for call recording varies by jurisdiction.
- Release signing and update authenticity must be handled by maintainers.

## Recommended Release Bar

- Add tests for config import allowlisting and tool confirmation flows.
- Add a visible onboarding warning for privileged permissions and recording.
- Prefer HTTPS for skill fetches.
- Keep official release artifacts signed and checksummed.
