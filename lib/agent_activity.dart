import 'dart:async';
import 'package:flutter/material.dart';
import 'models.dart';
import 'theme/tokens.dart';

/// What the voice agent is doing right now, as the home screen shows it.
enum AgentPhase {
  offline,
  standby,
  connecting,
  listening,
  muted,
  thinking,
  speaking,
  call,
}

extension AgentPhaseStyle on AgentPhase {
  Color get color => switch (this) {
    AgentPhase.offline => PremiumTokens.phaseOffline,
    AgentPhase.standby => PremiumTokens.phaseStandby,
    AgentPhase.connecting => PremiumTokens.phaseConnecting,
    AgentPhase.listening => PremiumTokens.phaseListening,
    AgentPhase.muted => PremiumTokens.phaseMuted,
    AgentPhase.thinking => PremiumTokens.phaseThinking,
    AgentPhase.speaking => PremiumTokens.phaseSpeaking,
    AgentPhase.call => PremiumTokens.phaseCall,
  };

  /// Second hue the orb swirls in alongside [color].
  Color get accent => switch (this) {
    AgentPhase.offline => PremiumTokens.phaseOffline,
    AgentPhase.standby => PremiumTokens.accentSecondary,
    AgentPhase.connecting => PremiumTokens.accentPrimary,
    AgentPhase.listening => const Color(0xFF22D3EE),
    AgentPhase.muted => const Color(0xFFF87171),
    AgentPhase.thinking => const Color(0xFFF472B6),
    AgentPhase.speaking => PremiumTokens.accentSecondary,
    AgentPhase.call => const Color(0xFFF97316),
  };

  String get label => switch (this) {
    AgentPhase.offline => 'Offline',
    AgentPhase.standby => 'Ready',
    AgentPhase.connecting => 'Connecting',
    AgentPhase.listening => 'Listening',
    AgentPhase.muted => 'Muted',
    AgentPhase.thinking => 'Thinking',
    AgentPhase.speaking => 'Speaking',
    AgentPhase.call => 'On a call',
  };

  bool get isLive => this != AgentPhase.offline && this != AgentPhase.standby;
}

/// The phase for a voice chat state; [speaking] comes from the agent's audio
/// level. A muted mic only replaces listening: the agent can still think and talk.
AgentPhase phaseFor(
  ChatState state, {
  required bool speaking,
  bool muted = false,
}) {
  switch (state) {
    case ChatState.idle:
      return AgentPhase.standby;
    case ChatState.connecting:
      return AgentPhase.connecting;
    case ChatState.recording:
      return AgentPhase.listening;
    case ChatState.waiting:
      return speaking ? AgentPhase.speaking : AgentPhase.thinking;
    case ChatState.conversing:
      if (speaking) return AgentPhase.speaking;
      return muted ? AgentPhase.muted : AgentPhase.listening;
  }
}

/// How a tool call is shown as a tag on the voice screen.
class ToolInfo {
  const ToolInfo(this.label, this.icon, this.color);

  final String label;
  final IconData icon;
  final Color color;

  static const _location = Color(0xFF34D399);
  static const _search = Color(0xFF38BDF8);
  static const _memory = Color(0xFFC084FC);
  static const _people = Color(0xFFF472B6);
  static const _phone = Color(0xFFFBBF24);
  static const _media = Color(0xFFFB7185);
  static const _time = Color(0xFF2DD4BF);
  static const _brain = Color(0xFFA78BFA);

  static const _known = <String, ToolInfo>{
    'get_location': ToolInfo('GPS', Icons.my_location_rounded, _location),
    'open_maps': ToolInfo('Maps', Icons.map_rounded, _location),
    'google_search': ToolInfo(
      'Web search',
      Icons.travel_explore_rounded,
      _search,
    ),
    'use_skill': ToolInfo('Skill', Icons.extension_rounded, _search),
    'search_memory': ToolInfo('Memory', Icons.psychology_rounded, _memory),
    'leave_message': ToolInfo(
      'Note saved',
      Icons.sticky_note_2_rounded,
      _memory,
    ),
    'get_notes': ToolInfo('Notes', Icons.notes_rounded, _memory),
    'search_contacts': ToolInfo('Contacts', Icons.contacts_rounded, _people),
    'add_contact': ToolInfo('New contact', Icons.person_add_rounded, _people),
    'dial_contact': ToolInfo('Dialing', Icons.call_rounded, _phone),
    'dial_number': ToolInfo('Dialing', Icons.call_rounded, _phone),
    'send_sms': ToolInfo('SMS', Icons.sms_rounded, _phone),
    'hangUp': ToolInfo('Hang up', Icons.call_end_rounded, _phone),
    'take_photo': ToolInfo('Camera', Icons.photo_camera_rounded, _media),
    'play_youtube': ToolInfo('YouTube', Icons.play_circle_rounded, _media),
    'schedule_task': ToolInfo('Scheduled', Icons.event_rounded, _time),
    'update_scheduled_task': ToolInfo(
      'Schedule',
      Icons.edit_calendar_rounded,
      _time,
    ),
    'cancel_scheduled_task': ToolInfo(
      'Unscheduled',
      Icons.event_busy_rounded,
      _time,
    ),
    'list_scheduled_tasks': ToolInfo(
      'Schedule',
      Icons.event_note_rounded,
      _time,
    ),
    'ask_expert': ToolInfo('Expert', Icons.school_rounded, _brain),
    'ask_fable': ToolInfo('Fable', Icons.auto_awesome_rounded, _brain),
    'end_session': ToolInfo('Goodbye', Icons.waving_hand_rounded, _brain),
  };

  static ToolInfo of(String name) =>
      _known[name] ??
      ToolInfo(
        name.replaceAll('_', ' ').trim(),
        Icons.bolt_rounded,
        PremiumTokens.accentPrimary,
      );
}

class ToolEvent {
  ToolEvent(this.id, this.name) : info = ToolInfo.of(name);

  final int id;
  final String name;
  final ToolInfo info;
}

/// Live voice telemetry from the native side: loudness and tool calls.
///
/// Kept out of the shell's setState so level updates (~15 Hz) only repaint the
/// orb instead of rebuilding every page.
class AgentActivity {
  /// Latest mic and agent loudness, 0..1.
  double mic = 0;
  double agent = 0;
  DateTime _levelsAt = DateTime.fromMillisecondsSinceEpoch(0);

  /// True while agent audio is playing. Playback metering runs up to a second
  /// ahead of the speaker (the AudioTrack buffer), so it holds past the last chunk.
  final speaking = ValueNotifier<bool>(false);

  final _tools = StreamController<ToolEvent>.broadcast();
  Stream<ToolEvent> get tools => _tools.stream;

  Timer? _speakHold;
  int _seq = 0;

  static const _speakThreshold = 0.12;
  static const _speakHoldTime = Duration(milliseconds: 1100);
  static const _staleAfter = Duration(milliseconds: 350);

  void onLevels(double mic, double agent) {
    this.mic = mic;
    this.agent = agent;
    _levelsAt = DateTime.now();
    if (agent > _speakThreshold) {
      speaking.value = true;
      _speakHold?.cancel();
      _speakHold = Timer(_speakHoldTime, () => speaking.value = false);
    }
  }

  /// Levels decay to zero once the native side stops reporting them.
  bool get levelsFresh => DateTime.now().difference(_levelsAt) < _staleAfter;

  void onTool(String name) => _tools.add(ToolEvent(_seq++, name));

  void reset() {
    mic = 0;
    agent = 0;
    _speakHold?.cancel();
    speaking.value = false;
  }

  void dispose() {
    _speakHold?.cancel();
    speaking.dispose();
    _tools.close();
  }
}
