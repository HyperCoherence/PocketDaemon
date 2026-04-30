# PocketDaemon Features

## AI Phone Receptionist

Answers incoming calls autonomously using Gemini Live real-time voice streaming. The agent speaks naturally over WebSocket-streamed PCM audio (16 kHz capture, 24 kHz playback), handles the conversation, and either resolves the caller intent or takes a message. No human interaction required.

## Real-Time Voice Conversation

Full duplex voice chat outside of phone calls. Push-to-talk or continuous conversation mode with a dedicated foreground service for mic access. Audio-reactive orb visualization reflects agent state (idle, listening, thinking, speaking). Sessions persist via Gemini Live session handles for seamless resume.

## Caller Routing and Trust Tiers

Incoming calls route through two distinct agent profiles based on caller number. Unknown callers get a restricted agent (hang up, take message, use skills). Trusted contacts configured with name, relationship, and custom prompt get an expanded agent with memory search, location sharing, notes access, and skill execution.

## Barge-In and Take-Over

Barge-in: the agent detects when the caller starts speaking and flushes its playback queue immediately. Human take-over at any time. Tap to stop the agent mid-call, unmute yourself, resume as a normal phone call with optional recording.

## Outbound Calling from Chat

The voice agent places outbound calls on your behalf. Audio bridges seamlessly between the chat session and the active telecom call. The agent handles the conversation, hangs up when done, and returns to chat context.

## Persistent Memory System

Long-term memory built from session transcripts. MEMORY.md stores extracted facts and summaries. SOUL.md defines agent identity, personality, and behavioral constraints. INDEX.md provides retrieval structure. Memory extraction runs post-session via Gemini Pro, with compaction to prevent unbounded growth.

## Scheduled Tasks

Time-triggered tasks via AlarmManager with exact alarms. When fired, the task prompt runs through Gemini Pro, and the result is saved as a note with a notification.

## Google Search Grounding

Toggleable per agent tier. When enabled, the Gemini Live session gets real-time Google Search access mid-conversation.

## Expert Advisor

Mid-conversation escalation to a separate Gemini Pro instance. The voice agent invokes ask_expert to query a more capable model, then incorporates the answer back into the live session.

## Text Chat

REST-based multi-turn text chat with Gemini Pro. Supports image attachments via camera or gallery. Same tool ecosystem as voice. Full conversation history with thinking indicators.

## Skills System

Extensible skill modules stored as markdown with YAML frontmatter under /sdcard/PocketDaemon/skills/. Skills define fetch URLs, network patterns, and auto-inject rules. HTTP execution via OkHttp with response caching.

## Call Recording

Three independent modes: agent-handled calls, agent voice conversations, and human phone calls. Fallback source selection across VOICE_CALL, VOICE_DOWNLINK, VOICE_COMMUNICATION, and MIC. Per-mode toggles in settings.

## Camera Integration

Take photos on command via Camera2 API. Photos sent into active voice sessions or text chats as inline image data for visual context.

## Notes and Notifications

Structured note system on external storage. Notes created from caller messages, scheduled task results, or direct agent output. Each note pushes an Android notification that deep-links to the note detail.

## Location Sharing

GPS location available to trusted callers and chat sessions. Root-level settings enforcement ensures GPS stays enabled. Background location access. Google Maps and navigation integration.

## YouTube Budget Control

Configurable daily screen time budget for YouTube. Root shell loop tracks remaining time. Force-stops YouTube when budget expires. Optional launcher activity disable.

## Contact Management

Add contacts through the agent via Android Contacts batch API. Trusted contacts list with per-contact phone number, display name, relationship, and custom agent prompt.

## Session Logging and History

Every interaction logged as structured transcripts. History tab with browsable session list, metadata, and full transcript playback. Debug log access.

## Privileged System App via Magisk

Installs as a privileged system app via Magisk module. Grants MODIFY_PHONE_STATE, CONTROL_INCALL_EXPERIENCE, CAPTURE_AUDIO_OUTPUT, and MANAGE_OWN_CALLS. Boot script auto-grants all runtime permissions.

## Default Dialer and Assistant Role

Registers as InCallService and optionally as system assistant (long-press power). Both set via root commands without user prompts.

## Sideload Configuration

Drop pocketdaemon_config.json into /sdcard/Download/ for auto-import on launch. API key, model, system prompt, all settings. File consumed after merge. Zero-touch provisioning.

## Premium Glass UI

Dark interface with glass morphism, Syne typography, gradient backgrounds, audio-reactive orb, spring-curve animations, frosted glass navigation.

## Configurable Agent Personality

Editable SOUL.md with variable substitution. Separate CALL_PROMPT.md override. Per-agent tool toggles control capabilities per tier.

## Offline-First Architecture

All data on /sdcard/PocketDaemon/. Config, memory, notes, logs, skills, recordings, scheduled tasks. Human-readable markdown and JSON. Only network dependency is Gemini API.
