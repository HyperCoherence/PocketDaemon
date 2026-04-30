# Privacy

PocketDaemon stores user data locally on the device under `/sdcard/PocketDaemon/`.
Depending on enabled features, that directory can contain:

- API configuration
- Call and chat transcripts
- Long-term memory files
- Notes and scheduled task results
- Call/audio recordings
- Photos captured by the agent
- Skill files and cached skill fetches

Network requests are made to the configured model provider and to URLs specified
by skills. When Gemini features are enabled, prompts, audio, transcripts, image
attachments, tool results, and memory context may be sent to Google's Gemini API.

Users are responsible for complying with local call-recording, telephony,
privacy, and consent laws.

Before publishing logs, screenshots, bug reports, recordings, or memory files,
remove names, phone numbers, locations, API keys, transcripts, and other personal
data.
