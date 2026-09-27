import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'models.dart';
import 'recording_events.dart';
import 'theme/tokens.dart';
import 'utils.dart';
import 'widgets/glass_nav_bar.dart';
import 'screens/home_page.dart';
import 'screens/chat_page.dart';
import 'screens/notes_page.dart';
import 'screens/history_page.dart';
import 'screens/settings/settings_page.dart';

class AppShell extends StatefulWidget {
  const AppShell({super.key});

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> with WidgetsBindingObserver {
  static const _control = MethodChannel('pocket_daemon/call_control');
  static const _events = EventChannel('pocket_daemon/call_events');

  int _tab = 0;

  bool _agentEnabled = false;
  bool _hasApiKey = false;
  Map<String, bool> _permissions = {};
  String _callStatus = '';
  bool _takenOver = false;
  final List<LogEntry> _log = [];
  List<Map<String, dynamic>> _notes = [];
  String _agentName = '';

  ChatState _chatState = ChatState.idle;
  ChatMode _chatMode = ChatMode.conversation;

  final List<TranscriptLine> _transcript = [];
  List<SessionSummary> _sessions = [];

  final List<ChatMessage> _chatMessages = [];
  bool _textChatActive = false;
  bool _textChatWaiting = false;
  StreamSubscription? _eventSub;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _eventSub = _events.receiveBroadcastStream().listen(_onEvent);
    _refreshState();
  }

  @override
  void dispose() {
    _eventSub?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refreshState();
  }

  Future<void> _refreshState() async {
    try {
      final perms = await _control.invokeMethod('getPermissionStatus');
      final status = await _control.invokeMethod('getStatus');
      final rawNotes = await _control.invokeMethod('getNotes');
      final config = await _control.invokeMethod('getConfig');

      setState(() {
        if (perms is Map) {
          _permissions = perms.map((k, v) => MapEntry(k.toString(), v == true));
        }
        if (status is Map) {
          _agentEnabled = status['agentEnabled'] == true;
          _hasApiKey = status['hasApiKey'] == true;
          final v = status['appVersion']?.toString() ?? '';
          if (v.isNotEmpty) kAppVersion = v;
          final mode = status['chatMode']?.toString() ?? 'conversation';
          _chatMode = mode == 'ptt' ? ChatMode.ptt : ChatMode.conversation;
        }
        if (rawNotes is List) {
          _notes = rawNotes
              .map((e) => Map<String, dynamic>.from(e as Map))
              .toList();
        }
        if (config is Map) {
          _agentName = config['agentName']?.toString().trim() ?? '';
        }
      });
    } catch (e) {
      _addLog('Init error: $e');
    }
    _loadSessions();
    _checkInitialNote();
  }

  Future<void> _checkInitialNote() async {
    try {
      final noteId = await _control.invokeMethod<String>('getInitialNote');
      if (noteId != null && noteId.isNotEmpty) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          _openNoteById(noteId);
        });
      }
    } catch (_) {}
  }

  Future<void> _loadSessions() async {
    try {
      final raw = await _control.invokeMethod('getSessionLogs');
      if (raw is List) {
        setState(() {
          _sessions = raw.map((e) {
            final m = Map<String, dynamic>.from(e as Map);
            final rawTranscript = m['transcript'] as List? ?? [];
            return SessionSummary(
              filename: m['filename']?.toString() ?? '',
              type: m['type']?.toString() ?? 'unknown',
              caller: m['caller']?.toString() ?? '',
              name: m['name']?.toString() ?? '',
              started: m['started']?.toString() ?? '',
              ended: m['ended']?.toString() ?? '',
              transcript: rawTranscript
                  .map(
                    (t) => Map<String, String>.from(
                      (t as Map).map(
                        (k, v) => MapEntry(k.toString(), v.toString()),
                      ),
                    ),
                  )
                  .toList(),
            );
          }).toList();
        });
      }
    } catch (_) {}
    _loadTodayHistory();
  }

  bool _chatHistoryLoaded = false;

  void _loadTodayHistory() {
    if (_chatHistoryLoaded || _chatMessages.isNotEmpty || _sessions.isEmpty) {
      return;
    }
    _chatHistoryLoaded = true;

    final now = DateTime.now();
    final todayPrefix =
        '${now.year}-${now.month.toString().padLeft(2, '0')}-${now.day.toString().padLeft(2, '0')}';

    final history = <ChatMessage>[];
    for (final session in _sessions.reversed) {
      if (!session.filename.startsWith(todayPrefix)) continue;
      for (final entry in session.transcript) {
        final speaker = entry['speaker'] ?? '';
        final text = entry['text'] ?? '';
        final time = entry['time'] ?? '';
        if (speaker.isNotEmpty && text.isNotEmpty && speaker != 'tool') {
          DateTime? ts;
          if (time.length >= 8) {
            try {
              final parts = time.split(':');
              ts = DateTime(
                now.year,
                now.month,
                now.day,
                int.parse(parts[0]),
                int.parse(parts[1]),
                int.parse(parts[2]),
              );
            } catch (_) {}
          }
          history.add(
            ChatMessage(speaker, text, timestamp: ts, isHistory: true),
          );
        }
      }
    }
    if (history.isNotEmpty) {
      setState(() => _chatMessages.addAll(history));
    }
  }

  void _openNoteById(String id) {
    final note = _notes.cast<Map<String, dynamic>?>().firstWhere(
      (n) => n?['id'] == id,
      orElse: () => null,
    );
    if (note != null && mounted) {
      setState(() => _tab = 2);
      Navigator.push(
        context,
        slideRoute(NoteDetailPage(note: note, onDismiss: _dismissNote)),
      );
    }
  }

  void _onEvent(dynamic event) {
    if (event is! Map) return;
    final type = event['type']?.toString() ?? '';

    if (type == 'openNote') {
      final id = event['id']?.toString() ?? '';
      if (id.isNotEmpty) _openNoteById(id);
      return;
    }
    if (type == 'recordingPlayback' || type == 'recordingTranscription') {
      RecordingEvents.push(event.map((k, v) => MapEntry(k.toString(), v)));
      return;
    }

    setState(() {
      switch (type) {
        case 'callRinging':
          _callStatus = 'Incoming: ${event['number']}';
          _transcript.clear();
          _addLog('Ringing: ${event['number']}');
        case 'callActive':
          final callerName = event['name']?.toString() ?? '';
          final trusted = event['trusted'] == true;
          final callerLabel = callerName.isNotEmpty
              ? '$callerName${trusted ? ' (trusted)' : ''}'
              : event['number']?.toString() ?? '';
          if (event['manual'] == true) {
            // A call the owner is on themselves; the agent stays out unless handed the call.
            _callStatus = 'On call: $callerLabel';
            _takenOver = true;
            _addLog('On call, agent standing by: $callerLabel');
          } else {
            _callStatus = 'Agent handling: $callerLabel';
            _takenOver = false;
            _addLog('Agent on call: $callerLabel');
          }
        case 'callTakenOver':
          _takenOver = true;
          _callStatus = 'You are on call: ${event['number']}';
          _addLog('Took over call: ${event['number']}');
        case 'callEnded':
          _callStatus = '';
          _takenOver = false;
          _transcript.clear();
          _addLog('Call ended: ${event['number']}');
          _loadSessions();
        case 'transcript':
          final speaker = event['speaker']?.toString() ?? '';
          final text = event['text']?.toString() ?? '';
          if (_transcript.isNotEmpty &&
              _transcript.last.speaker == speaker &&
              _transcript.last.imagePath == null) {
            _transcript.last.text += ' $text';
          } else {
            _transcript.add(TranscriptLine(speaker, text));
            if (_transcript.length > 50) _transcript.removeAt(0);
          }
          _addLog('$speaker: $text');
        case 'agentToggled':
          _agentEnabled = event['enabled'] == true;
        case 'error':
          _addLog('ERROR: ${event['message']}');
        case 'permissionsResult':
          if (event['details'] is Map) {
            _permissions = (event['details'] as Map).map(
              (k, v) => MapEntry(k.toString(), v == true),
            );
          }
        case 'noteAdded':
          _notes.insert(0, {
            'id': event['id'],
            'source': event['source'],
            'text': event['text'],
            'date': event['date'],
          });
          _addLog('Note added from ${event['source']}');
        case 'chatReady':
          _chatState = _chatMode == ChatMode.conversation
              ? ChatState.conversing
              : ChatState.recording;
          _addLog('Chat: connected (${_chatMode.name})');
        case 'chatWaiting':
          _chatState = ChatState.waiting;
        case 'chatTurnComplete':
          final isConvo = event['conversationMode'] == true;
          _chatState = isConvo ? ChatState.conversing : ChatState.idle;
          if (!isConvo) {
            _setKeepScreenOn(false);
            _loadSessions();
          }
          _addLog('Chat: turn complete');
        case 'chatInteractionStatus':
          final status = event['status']?.toString() ?? '';
          final live =
              _chatState == ChatState.conversing ||
              _chatState == ChatState.waiting;
          if (status == 'IN_PROGRESS' && live) {
            _chatState = ChatState.waiting;
          } else if (status == 'REQUIRES_ACTION' &&
              _chatState == ChatState.waiting &&
              _chatMode == ChatMode.conversation) {
            _chatState = ChatState.conversing;
          }
        case 'chatEnded':
          _chatState = ChatState.idle;
          _setKeepScreenOn(false);
          _transcript.clear();
          final chatErr = event['error']?.toString();
          _addLog(
            chatErr != null
                ? 'Chat: ended — $chatErr'
                : 'Chat: conversation ended',
          );
          _loadSessions();
        case 'chatTranscript':
          final speaker = event['speaker']?.toString() ?? '';
          final text = event['text']?.toString() ?? '';
          if (_transcript.isNotEmpty &&
              _transcript.last.speaker == speaker &&
              _transcript.last.imagePath == null) {
            _transcript.last.text += ' $text';
          } else {
            _transcript.add(TranscriptLine(speaker, text));
            if (_transcript.length > 50) _transcript.removeAt(0);
          }
          _addLog('Chat $speaker: $text');
        case 'chatPhoto':
          final path = event['path']?.toString() ?? '';
          _transcript.add(
            TranscriptLine('user', 'Photo shared', imagePath: path),
          );
          if (_transcript.length > 50) _transcript.removeAt(0);
          _addLog('Chat: photo shared');
        case 'textChatReady':
          _textChatActive = true;
          _textChatWaiting = false;
          _chatState = ChatState.idle;
          _addLog('Text chat: ready');
        case 'textChatResponse':
          _textChatWaiting = false;
          final agentText = event['text']?.toString() ?? '';
          if (agentText.isNotEmpty) {
            _chatMessages.add(ChatMessage('agent', agentText));
          }
          _addLog(
            'Text chat agent: ${agentText.length > 80 ? '${agentText.substring(0, 80)}…' : agentText}',
          );
        case 'textChatThinking':
          final tool = event['tool']?.toString() ?? '';
          _addLog('Text chat: using $tool');
        case 'textChatEnded':
          _textChatActive = false;
          _textChatWaiting = false;
          _addLog('Text chat: ended');
          _loadSessions();
        case 'outboundCallDialing':
          _addLog('Dialing: ${event['number']}');
        case 'outboundCallActive':
          _addLog('Outbound call connected — agent talking');
        case 'outboundCallEnded':
          _addLog('Outbound call ended — back to chat');
        default:
          _addLog(type);
      }
    });
  }

  void _addLog(String msg) {
    final now = DateTime.now();
    final t =
        '${now.hour.toString().padLeft(2, '0')}:'
        '${now.minute.toString().padLeft(2, '0')}:'
        '${now.second.toString().padLeft(2, '0')}';
    _log.insert(0, LogEntry(t, msg));
    if (_log.length > 200) _log.removeLast();
  }

  bool get _allPermsGranted =>
      _permissions.isNotEmpty && _permissions.values.every((v) => v);

  bool get _configured => _hasApiKey;

  Future<void> _toggleAgent(bool value) async {
    HapticFeedback.lightImpact();
    await _control.invokeMethod('setAgentEnabled', {'enabled': value});
    setState(() => _agentEnabled = value);
  }

  Future<void> _setKeepScreenOn(bool on) async {
    try {
      await _control.invokeMethod('setKeepScreenOn', {'enabled': on});
    } catch (_) {}
  }

  Future<void> _startChat() async {
    HapticFeedback.mediumImpact();
    _transcript.clear();
    setState(() {
      _chatState = ChatState.connecting;
      _textChatActive = false;
      _textChatWaiting = false;
    });
    _setKeepScreenOn(true);
    await _control.invokeMethod('startChat', {'mode': _chatMode.name});
  }

  Future<void> _stopChat() async {
    HapticFeedback.lightImpact();
    await _control.invokeMethod('stopChat');
  }

  Future<void> _endConversation() async {
    HapticFeedback.mediumImpact();
    await _control.invokeMethod('endConversation');
    _setKeepScreenOn(false);
    setState(() {
      _chatState = ChatState.idle;
      _transcript.clear();
    });
  }

  Future<void> _cancelChat() async {
    await _control.invokeMethod('cancelChat');
    _setKeepScreenOn(false);
    setState(() {
      _chatState = ChatState.idle;
      _transcript.clear();
    });
  }

  Future<void> _takeOverCall() async {
    HapticFeedback.heavyImpact();
    await _control.invokeMethod('takeOverCall');
  }

  Future<void> _hangUpCall() async {
    HapticFeedback.mediumImpact();
    await _control.invokeMethod('hangUpCall');
  }

  Future<void> _handCallToAgent() async {
    HapticFeedback.mediumImpact();
    await _control.invokeMethod('handCallToAgent');
  }

  Future<void> _startTextChat() async {
    if (_textChatActive) return;
    setState(() => _textChatWaiting = true);
    await _control.invokeMethod('startTextChat');
  }

  Future<void> _sendTextMessage(
    String text, {
    String? imageBase64,
    String? imageMimeType,
    String? imagePath,
  }) async {
    if (text.trim().isEmpty && imageBase64 == null) return;
    setState(() {
      _chatMessages.add(ChatMessage('user', text.trim(), imagePath: imagePath));
      _textChatWaiting = true;
    });
    final args = <String, dynamic>{'text': text.trim()};
    if (imageBase64 != null) {
      args['imageBase64'] = imageBase64;
      args['imageMimeType'] = imageMimeType ?? 'image/jpeg';
    }
    await _control.invokeMethod('sendTextMessage', args);
  }

  Future<void> _sendVoiceImage(
    String imageBase64,
    String mimeType,
    String? caption,
    String? path,
  ) async {
    await _control.invokeMethod('sendVoiceImage', <String, dynamic>{
      'imageBase64': imageBase64,
      'imageMimeType': mimeType,
      if (caption != null && caption.isNotEmpty) 'caption': caption,
      'path': ?path,
    });
  }

  Future<void> _endTextChat() async {
    await _control.invokeMethod('endTextChat');
    setState(() {
      _textChatActive = false;
      _textChatWaiting = false;
    });
  }

  Future<void> _dismissNote(String id) async {
    HapticFeedback.lightImpact();
    await _control.invokeMethod('dismissNote', {'id': id});
    setState(() => _notes.removeWhere((n) => n['id'] == id));
  }

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: const BoxDecoration(gradient: PremiumTokens.shellGradient),
      child: Stack(
        children: [
          Positioned.fill(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: PremiumTokens.shellRadialCyan,
              ),
            ),
          ),
          Positioned.fill(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: PremiumTokens.shellRadialIndigo,
              ),
            ),
          ),
          Scaffold(
            body: SafeArea(
              child: AnimatedSwitcher(
                duration: PremiumTokens.durationSlow,
                switchInCurve: PremiumTokens.easeSpring,
                switchOutCurve: PremiumTokens.easeOut,
                transitionBuilder: (child, anim) =>
                    FadeTransition(opacity: anim, child: child),
                child: KeyedSubtree(
                  key: ValueKey(_tab),
                  child: [
                    HomePage(
                      agentEnabled: _agentEnabled,
                      configured: _configured,
                      allPermsGranted: _allPermsGranted,
                      callStatus: _callStatus,
                      takenOver: _takenOver,
                      chatState: _chatState,
                      chatMode: _chatMode,
                      transcript: _transcript,
                      agentName: _agentName,
                      onToggleAgent: _toggleAgent,
                      onStartChat: _startChat,
                      onStopChat: _stopChat,
                      onCancelChat: _cancelChat,
                      onEndConversation: _endConversation,
                      onTakeOver: _takeOverCall,
                      onHangUp: _hangUpCall,
                      onHandToAgent: _handCallToAgent,
                      onSendImage: _sendVoiceImage,
                      control: _control,
                    ),
                    ChatPage(
                      messages: _chatMessages,
                      active: _textChatActive,
                      waiting: _textChatWaiting,
                      onSend: _sendTextMessage,
                      onStartSession: _startTextChat,
                      onEndSession: _endTextChat,
                    ),
                    NotesPage(notes: _notes, onDismissNote: _dismissNote),
                    HistoryPage(
                      sessions: _sessions,
                      log: _log,
                      onClear: () => setState(() => _log.clear()),
                    ),
                    SettingsPage(
                      permissions: _permissions,
                      allPermsGranted: _allPermsGranted,
                      control: _control,
                      onRefresh: _refreshState,
                    ),
                  ][_tab],
                ),
              ),
            ),
            bottomNavigationBar: GlassNavBar(
              selectedIndex: _tab,
              onTap: (i) => setState(() => _tab = i),
            ),
          ),
        ],
      ),
    );
  }
}
