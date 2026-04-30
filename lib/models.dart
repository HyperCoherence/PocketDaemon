import 'package:flutter/material.dart';

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
  TranscriptLine(this.speaker, this.text) : timestamp = DateTime.now();
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
