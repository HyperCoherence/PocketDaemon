# PocketDaemon Features

## AI Phone Receptionist

Answers incoming calls autonomously using provider-neutral realtime voice streaming. Gemini Live (default model `gemini-3.8-live`) and xAI Voice Agent are first-class voice providers. The agent speaks naturally over WebSocket-streamed PCM audio (16 kHz capture, 24 kHz playback), handles the conversation, and either resolves the caller intent or takes a message. No human interaction required.

## Real-Time Voice Conversation

Full duplex voice chat outside of phone calls. Push-to-talk or continuous conversation mode with a dedicated foreground service for mic access. Audio-reactive orb visualization reflects agent state (idle, listening, thinking, speaking). Gemini sessions persist via Live session handles for seamless resume.

## Non-Blocking Tools and Thinking

Slow tools (expert consult, skill fetches, GPS fixes, camera timers) are declared as non-blocking Live API function calls, so the Gemini agent keeps talking while they run and picks the result up once it is idle instead of going silent. Gemini live thinking is off by default. A thinking level (`minimal`, `low`, `medium`, `high`) can be enabled in settings for models that accept one, such as `gemini-3.8-live-extended-thinking`; `gemini-3.8-live` reasons on its own and ignores the setting. On `gemini-3.8-live` the end of an agent turn is taken from the Live API interaction status (`IN_PROGRESS` while reasoning or waiting on a tool, `REQUIRES_ACTION` once idle) rather than `turnComplete`, so push-to-talk turns and idle timers wait for the model to actually finish, and the chat chip shows Thinking while the status is in progress.

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

## Search Grounding

Toggleable per agent tier. Gemini sessions get Google Search access; xAI sessions get web search and X search access mid-conversation.

## Expert Advisor

Mid-conversation escalation to a separate, stronger model. The voice agent invokes ask_expert to query the expert role's configured model (Gemini, xAI, or Claude), then incorporates the answer back into the live session.

## Ask Fable

"Let me ask Fable about this." The ask_fable tool sends the question, recent conversation, and memory context to Claude Fable 5.1 with server-side web search and fetch, so the agent gets careful reasoning and current, sourced facts. Fable replies with a spoken summary; long answers and their sources are saved as a note. A rolling per-day thread lets follow-up questions build on earlier ones. Quick mode answers fast; research mode raises effort for thorough work. Requires an Anthropic API key. Available to the chat agent and scheduled tasks.

## Provider-Neutral Reasoning

Text chat, the expert advisor, scheduled tasks, and memory extraction run on a reasoning client that speaks Gemini, xAI (Grok), or Claude. Each role has its own provider and model in config, and a role whose provider has no key falls back to any provider that does, so an xAI-only or Claude-only setup still gets working chat and memory.

## Text Chat

REST-based multi-turn text chat on the chat role's model (Gemini, xAI, or Claude). Supports image attachments via camera or gallery. Same tool ecosystem as voice. Full conversation history with thinking indicators.

## Skills System

Extensible skill modules stored as markdown with YAML frontmatter under /sdcard/PocketDaemon/skills/. Skills define fetch URLs, network patterns, and auto-inject rules. HTTP execution via OkHttp with response caching.

## Call Recording

Three independent modes: agent-handled calls, agent voice conversations, and human phone calls. Fallback source selection across VOICE_CALL, VOICE_DOWNLINK, VOICE_COMMUNICATION, and MIC. Per-mode toggles in settings.

## Camera Integration

Take photos on command via Camera2 API, or show things to the agent yourself: a camera button beside the talk orb opens an in-app viewfinder while the voice session keeps running, and the shutter sends the photo straight into the live session as a realtime image frame, so you keep talking about what the agent now sees. Gallery images can be shared the same way, and the live transcript shows a thumbnail. Agent-triggered photos are sent into active voice sessions or text chats as inline image data for visual context.

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

## USB Installer and Configuration

`tools/pocketdaemonctl` provides `doctor`, `install`, `setup`, `verify`, `backup`,
and `restore`. The setup command opens a localhost browser editor over USB for
provider selection, API keys, identity, prompts, trusted contacts, skills,
scheduled tasks, and advanced memory files.

## Sideload Configuration

Drop pocketdaemon_config.json into /sdcard/Download/ for auto-import on launch. Provider schema, API keys, model, voice, system prompt, and supported settings are merged. File consumed after merge. Zero-touch provisioning remains available.

## Premium Glass UI

Dark interface with glass morphism, Syne typography, gradient backgrounds, audio-reactive orb, spring-curve animations, frosted glass navigation.

## Configurable Agent Personality

Editable SOUL.md with variable substitution. Separate CALL_PROMPT.md override. Per-agent tool toggles control capabilities per tier.

## Offline-First Architecture

All data on /sdcard/PocketDaemon/. Config, memory, notes, logs, skills, recordings, scheduled tasks. Human-readable markdown and JSON. Realtime voice can use Gemini or xAI; chat, expert, scheduler, and memory roles can use Gemini, xAI, or Claude.
