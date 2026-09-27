import 'package:flutter/material.dart';
import 'theme/tokens.dart';

String kAppVersion = '';

enum ChatState { idle, connecting, recording, waiting, conversing }

enum ChatMode { ptt, conversation }

class LogEntry {
  final String time;
  final String message;
  LogEntry(this.time, this.message);
}

class TranscriptLine {
  final String speaker;
  String text;
  final DateTime timestamp;

  /// Local file of a photo the user shared, shown as a thumbnail.
  final String? imagePath;
  TranscriptLine(this.speaker, this.text, {this.imagePath})
    : timestamp = DateTime.now();
}

class ChatMessage {
  final String sender;
  final String text;
  final DateTime timestamp;
  final bool isHistory;
  final String? imagePath;
  ChatMessage(
    this.sender,
    this.text, {
    DateTime? timestamp,
    this.isHistory = false,
    this.imagePath,
  }) : timestamp = timestamp ?? DateTime.now();
}

class SessionSummary {
  final String filename;
  final String type;
  final String caller;
  final String name;
  final String started;
  final String ended;
  final List<Map<String, String>> transcript;

  SessionSummary({
    required this.filename,
    required this.type,
    required this.caller,
    required this.name,
    required this.started,
    required this.ended,
    required this.transcript,
  });

  String get displayTitle {
    if (name.isNotEmpty) return name;
    if (caller.isNotEmpty) return caller;
    if (type == 'chat' || type == 'conversation') return 'Chat';
    return type;
  }

  String get displayType {
    switch (type) {
      case 'call':
        return 'Phone Call';
      case 'trusted-call':
        return 'Trusted Call';
      case 'chat':
        return 'Chat';
      case 'conversation':
        return 'Conversation';
      default:
        return type;
    }
  }

  IconData get icon {
    switch (type) {
      case 'call':
        return Icons.phone_outlined;
      case 'trusted-call':
        return Icons.verified_user_outlined;
      case 'chat':
      case 'conversation':
        return Icons.chat_outlined;
      default:
        return Icons.timeline_outlined;
    }
  }

  Color get accent {
    switch (type) {
      case 'call':
        return Colors.greenAccent;
      case 'trusted-call':
        return Colors.tealAccent;
      case 'chat':
      case 'conversation':
        return Colors.blue;
      default:
        return Colors.grey;
    }
  }

  String get preview {
    for (final t in transcript) {
      if (t['speaker'] != 'tool') return t['text'] ?? '';
    }
    return '';
  }

  String get timeLabel {
    if (started.isEmpty) return '';
    try {
      final dt = DateTime.parse(started);
      final now = DateTime.now();
      final diff = now.difference(dt);
      if (diff.inMinutes < 1) return 'just now';
      if (diff.inHours < 1) return '${diff.inMinutes}m ago';
      if (diff.inDays < 1) return '${diff.inHours}h ago';
      if (diff.inDays < 7) return '${diff.inDays}d ago';
      return '${dt.month}/${dt.day}';
    } catch (_) {
      return started.length > 10 ? started.substring(11, 16) : started;
    }
  }

  String get durationLabel {
    if (started.isEmpty || ended.isEmpty) return '';
    try {
      final s = DateTime.parse(started);
      final e = DateTime.parse(ended);
      final diff = e.difference(s);
      if (diff.inSeconds < 60) return '${diff.inSeconds}s';
      return '${diff.inMinutes}m ${diff.inSeconds % 60}s';
    } catch (_) {
      return '';
    }
  }
}

class TranscriptSegment {
  final String? speaker;
  final double? startSec;
  final String text;

  TranscriptSegment({this.speaker, this.startSec, required this.text});

  factory TranscriptSegment.fromMap(Map m) => TranscriptSegment(
    speaker: m['speaker']?.toString(),
    startSec: m['startSec'] is num ? (m['startSec'] as num).toDouble() : null,
    text: m['text']?.toString() ?? '',
  );

  /// "spk_1" becomes "Speaker 1"; anything else is shown as-is.
  String get speakerLabel {
    final s = speaker;
    if (s == null || s.isEmpty) return '';
    final m = RegExp(r'(\d+)$').firstMatch(s);
    return m != null ? 'Speaker ${m.group(1)}' : s;
  }
}

class RecordingTranscript {
  final String text;
  final String model;
  final String mode;
  final String createdAt;
  final List<TranscriptSegment> segments;

  RecordingTranscript({
    required this.text,
    required this.model,
    required this.mode,
    required this.createdAt,
    required this.segments,
  });

  factory RecordingTranscript.fromMap(Map m) => RecordingTranscript(
    text: m['text']?.toString() ?? '',
    model: m['model']?.toString() ?? '',
    mode: m['mode']?.toString() ?? '',
    createdAt: m['createdAt']?.toString() ?? '',
    segments: [
      if (m['segments'] is List)
        for (final s in m['segments'] as List)
          if (s is Map) TranscriptSegment.fromMap(s),
    ],
  );
}

/// One saved WAV under PocketDaemon/recordings, as described by the native side.
class RecordingInfo {
  final String name;
  final String path;
  final String type;
  final int startedAt;
  final String started;
  final int durationMs;
  final int sizeBytes;
  final String caller;
  final String callerName;
  final String sessionLog;
  final RecordingTranscript? transcript;
  final bool transcribing;

  RecordingInfo({
    required this.name,
    required this.path,
    required this.type,
    required this.startedAt,
    required this.started,
    required this.durationMs,
    required this.sizeBytes,
    required this.caller,
    required this.callerName,
    required this.sessionLog,
    required this.transcript,
    required this.transcribing,
  });

  factory RecordingInfo.fromMap(Map m) => RecordingInfo(
    name: m['name']?.toString() ?? '',
    path: m['path']?.toString() ?? '',
    type: m['type']?.toString() ?? '',
    startedAt: (m['startedAt'] as num?)?.toInt() ?? 0,
    started: m['started']?.toString() ?? '',
    durationMs: (m['durationMs'] as num?)?.toInt() ?? 0,
    sizeBytes: (m['sizeBytes'] as num?)?.toInt() ?? 0,
    caller: m['caller']?.toString() ?? '',
    callerName: m['callerName']?.toString() ?? '',
    sessionLog: m['sessionLog']?.toString() ?? '',
    transcript: m['transcript'] is Map
        ? RecordingTranscript.fromMap(m['transcript'] as Map)
        : null,
    transcribing: m['transcribing'] == true,
  );

  String get displayType {
    switch (type) {
      case 'call':
        return 'Agent Call';
      case 'trusted-call':
        return 'Trusted Call';
      case 'phone':
        return 'Phone Call';
      case 'chat':
        return 'Conversation';
      default:
        return type.isEmpty ? 'Recording' : type;
    }
  }

  String get displayTitle {
    if (callerName.isNotEmpty) return callerName;
    if (caller.isNotEmpty) return caller;
    return displayType;
  }

  IconData get icon {
    switch (type) {
      case 'call':
        return Icons.support_agent_rounded;
      case 'trusted-call':
        return Icons.verified_user_rounded;
      case 'phone':
        return Icons.phone_in_talk_rounded;
      case 'chat':
        return Icons.chat_bubble_rounded;
      default:
        return Icons.graphic_eq_rounded;
    }
  }

  Color get accent {
    switch (type) {
      case 'call':
        return PremiumTokens.accentSecondary;
      case 'trusted-call':
        return PremiumTokens.success;
      case 'phone':
        return PremiumTokens.accentPrimary;
      case 'chat':
        return PremiumTokens.warning;
      default:
        return PremiumTokens.textMuted;
    }
  }

  String get durationLabel {
    final totalSec = (durationMs / 1000).round();
    final h = totalSec ~/ 3600;
    final m = (totalSec % 3600) ~/ 60;
    final s = totalSec % 60;
    if (h > 0) return '${h}h ${m}m';
    if (m > 0) return '${m}m ${s}s';
    return '${s}s';
  }

  String get sizeLabel {
    if (sizeBytes >= 1024 * 1024) {
      return '${(sizeBytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    }
    return '${(sizeBytes / 1024).round()} KB';
  }

  String get timeLabel {
    if (startedAt <= 0) return started;
    final dt = DateTime.fromMillisecondsSinceEpoch(startedAt);
    final now = DateTime.now();
    final diff = now.difference(dt);
    if (diff.inMinutes < 1) return 'just now';
    if (diff.inHours < 1) return '${diff.inMinutes}m ago';
    if (diff.inDays < 1) return '${diff.inHours}h ago';
    if (diff.inDays < 7) return '${diff.inDays}d ago';
    return '${dt.month}/${dt.day}';
  }
}
