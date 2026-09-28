import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../models.dart';
import '../../recording_events.dart';
import '../../theme/tokens.dart';
import '../../utils.dart';
import '../../widgets/premium_button.dart';

/// One recording: a player, its details, and the Gemini transcript.
class RecordingDetailPage extends StatefulWidget {
  final MethodChannel control;
  final RecordingInfo recording;

  const RecordingDetailPage({
    super.key,
    required this.control,
    required this.recording,
  });

  @override
  State<RecordingDetailPage> createState() => _RecordingDetailPageState();
}

class _RecordingDetailPageState extends State<RecordingDetailPage> {
  late RecordingInfo _rec;
  StreamSubscription<Map<String, dynamic>>? _sub;

  // Playback
  bool _isCurrent = false;
  bool _playing = false;
  int _positionMs = 0;
  int _durationMs = 0;
  double? _dragValue;

  // Transcription
  bool _transcribing = false;
  String _status = '';
  String _error = '';

  @override
  void initState() {
    super.initState();
    _rec = widget.recording;
    _durationMs = _rec.durationMs;
    _transcribing = _rec.transcribing;
    if (_transcribing) _status = 'Transcribing…';
    _sub = RecordingEvents.stream.listen(_onEvent);
    _syncPlayback();
  }

  @override
  void dispose() {
    _sub?.cancel();
    // Leaving the page ends playback of this recording.
    if (_isCurrent) widget.control.invokeMethod('stopPlayback');
    super.dispose();
  }

  Future<void> _syncPlayback() async {
    try {
      final raw = await widget.control.invokeMethod('getPlaybackState');
      if (raw is Map) _applyPlayback(raw);
    } catch (_) {}
  }

  void _onEvent(Map<String, dynamic> event) {
    final type = event['type'];
    if (type == 'recordingPlayback') {
      _applyPlayback(event);
    } else if (type == 'recordingTranscription' && event['name'] == _rec.name) {
      final status = event['status']?.toString() ?? '';
      setState(() {
        switch (status) {
          case 'queued':
            _transcribing = true;
            _status = 'Queued…';
            _error = '';
          case 'uploading':
            _transcribing = true;
            _status = 'Uploading audio to Gemini…';
          case 'transcribing':
            _transcribing = true;
            _status = 'Transcribing…';
          case 'done':
            _transcribing = false;
            _status = '';
            _reload();
          case 'error':
            _transcribing = false;
            _status = '';
            _error = event['message']?.toString() ?? 'Transcription failed';
        }
      });
    }
  }

  void _applyPlayback(Map raw) {
    final name = raw['name']?.toString() ?? '';
    final state = raw['state']?.toString() ?? 'stopped';
    final current =
        name == _rec.name &&
        state != 'stopped' &&
        state != 'finished' &&
        state != 'error';
    if (!mounted) return;
    setState(() {
      _isCurrent = current;
      _playing = current && state == 'playing';
      if (current) {
        _positionMs = (raw['positionMs'] as num?)?.toInt() ?? 0;
        final d = (raw['durationMs'] as num?)?.toInt() ?? 0;
        if (d > 0) _durationMs = d;
      } else {
        _positionMs = 0;
        _dragValue = null;
      }
    });
  }

  Future<void> _reload() async {
    try {
      final raw = await widget.control.invokeMethod('getRecording', {
        'name': _rec.name,
      });
      if (raw is Map && mounted) {
        setState(() => _rec = RecordingInfo.fromMap(raw));
      }
    } catch (_) {}
  }

  Future<void> _togglePlay() async {
    HapticFeedback.lightImpact();
    if (_playing) {
      await widget.control.invokeMethod('pausePlayback');
    } else if (_isCurrent) {
      await widget.control.invokeMethod('resumePlayback');
    } else {
      final ok = await widget.control.invokeMethod('playRecording', {
        'name': _rec.name,
      });
      if (ok != true && mounted) {
        _snack('Could not play this recording');
      }
    }
  }

  Future<void> _seek(double value) async {
    setState(() => _dragValue = null);
    if (!_isCurrent) return;
    await widget.control.invokeMethod('seekPlayback', {
      'positionMs': value.round(),
    });
  }

  Future<void> _transcribe() async {
    HapticFeedback.mediumImpact();
    setState(() {
      _error = '';
      _status = 'Starting…';
      _transcribing = true;
    });
    try {
      final raw = await widget.control.invokeMethod('transcribeRecording', {
        'name': _rec.name,
      });
      if (raw is Map && raw['started'] != true) {
        setState(() {
          _transcribing = false;
          _status = '';
          _error = raw['error']?.toString() ?? 'Could not start transcription';
        });
      }
    } catch (e) {
      setState(() {
        _transcribing = false;
        _status = '';
        _error = 'Could not start transcription: $e';
      });
    }
  }

  Future<void> _copyTranscript() async {
    final t = _rec.transcript;
    if (t == null) return;
    final buffer = StringBuffer();
    if (t.segments.any((s) => s.speakerLabel.isNotEmpty)) {
      for (final s in t.segments) {
        if (s.speakerLabel.isNotEmpty) buffer.write('${s.speakerLabel}: ');
        buffer.writeln(s.text);
      }
    } else {
      buffer.write(t.text);
    }
    await Clipboard.setData(ClipboardData(text: buffer.toString().trim()));
    HapticFeedback.selectionClick();
    _snack('Transcript copied');
  }

  Future<void> _delete() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: PremiumTokens.surfaceSolid,
        title: const Text('Delete recording?'),
        content: Text(
          'This removes ${_rec.name}${_rec.transcript != null ? ' and its transcript' : ''}. This cannot be undone.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text(
              'Delete',
              style: TextStyle(color: PremiumTokens.error),
            ),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    final ok = await widget.control.invokeMethod('deleteRecording', {
      'name': _rec.name,
    });
    if (!mounted) return;
    if (ok == true) {
      _isCurrent = false;
      Navigator.pop(context, true);
    } else {
      _snack('Could not delete the recording');
    }
  }

  void _snack(String text) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
  }

  @override
  Widget build(BuildContext context) {
    return settingsScaffold(
      _rec.displayTitle,
      ListView(
        padding: const EdgeInsets.all(20),
        children: [
          sectionCard(context, title: 'Playback', child: _buildPlayer()),
          const SizedBox(height: 14),
          sectionCard(context, title: 'Details', child: _buildDetails()),
          const SizedBox(height: 14),
          sectionCard(context, title: 'Transcript', child: _buildTranscript()),
          const SizedBox(height: 20),
          PremiumButton(
            label: 'Delete Recording',
            icon: Icons.delete_outline_rounded,
            danger: true,
            onPressed: _delete,
          ),
          const SizedBox(height: 12),
        ],
      ),
    );
  }

  Widget _buildPlayer() {
    final total = _durationMs > 0 ? _durationMs.toDouble() : 1.0;
    final value = (_dragValue ?? _positionMs.toDouble()).clamp(0.0, total);
    return Column(
      children: [
        Row(
          children: [
            GestureDetector(
              onTap: _togglePlay,
              child: Container(
                width: 56,
                height: 56,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  gradient: PremiumTokens.accentGradient,
                  boxShadow: [
                    BoxShadow(
                      color: PremiumTokens.accentPrimary.withAlpha(60),
                      blurRadius: 16,
                      offset: const Offset(0, 4),
                    ),
                  ],
                ),
                child: Icon(
                  _playing ? Icons.pause_rounded : Icons.play_arrow_rounded,
                  color: Colors.white,
                  size: 32,
                ),
              ),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _rec.displayType,
                    style: const TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                      color: PremiumTokens.textPrimary,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    _rec.started,
                    style: const TextStyle(
                      fontSize: 12,
                      color: PremiumTokens.textTertiary,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        SliderTheme(
          data: SliderTheme.of(context).copyWith(
            trackHeight: 3,
            thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 6),
            overlayShape: const RoundSliderOverlayShape(overlayRadius: 14),
            activeTrackColor: PremiumTokens.accentPrimary,
            inactiveTrackColor: PremiumTokens.surfaceGlassHover,
            thumbColor: PremiumTokens.accentPrimary,
          ),
          child: Slider(
            value: value,
            min: 0,
            max: total,
            onChanged: _isCurrent
                ? (v) => setState(() => _dragValue = v)
                : null,
            onChangeEnd: _seek,
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                _fmt(value.round()),
                style: const TextStyle(
                  fontSize: 12,
                  fontFamily: 'monospace',
                  color: PremiumTokens.textMuted,
                ),
              ),
              Text(
                _fmt(_durationMs),
                style: const TextStyle(
                  fontSize: 12,
                  fontFamily: 'monospace',
                  color: PremiumTokens.textMuted,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildDetails() {
    final rows = <MapEntry<String, String>>[
      MapEntry('Type', _rec.displayType),
      MapEntry('Recorded', _rec.started),
      MapEntry('Duration', _rec.durationLabel),
      MapEntry('Size', _rec.sizeLabel),
      if (_rec.callerName.isNotEmpty) MapEntry('Contact', _rec.callerName),
      if (_rec.caller.isNotEmpty) MapEntry('Number', _rec.caller),
      if (_rec.sessionLog.isNotEmpty) MapEntry('Session log', _rec.sessionLog),
      MapEntry('File', _rec.name),
    ];
    return Column(
      children: [
        for (var i = 0; i < rows.length; i++) ...[
          if (i > 0) const SizedBox(height: 8),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SizedBox(
                width: 92,
                child: Text(
                  rows[i].key,
                  style: const TextStyle(
                    fontSize: 12,
                    color: PremiumTokens.textTertiary,
                  ),
                ),
              ),
              Expanded(
                child: SelectableText(
                  rows[i].value,
                  style: const TextStyle(
                    fontSize: 13,
                    color: PremiumTokens.textPrimary,
                  ),
                ),
              ),
            ],
          ),
        ],
      ],
    );
  }

  Widget _buildTranscript() {
    final t = _rec.transcript;
    final children = <Widget>[];

    if (_transcribing) {
      children.add(
        Row(
          children: [
            const SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(
                strokeWidth: 2,
                color: PremiumTokens.accentPrimary,
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                _status.isEmpty ? 'Working…' : _status,
                style: const TextStyle(
                  fontSize: 13,
                  color: PremiumTokens.textSecondary,
                ),
              ),
            ),
          ],
        ),
      );
    } else if (t != null) {
      children.add(_buildSegments(t));
      children.add(const SizedBox(height: 10));
      children.add(
        Text(
          'Transcribed with ${t.model.isNotEmpty ? t.model : 'Gemini'}'
          '${t.mode == 'verbatim' ? ' · speaker labels' : ''}'
          '${t.createdAt.isNotEmpty ? ' · ${t.createdAt}' : ''}',
          style: const TextStyle(fontSize: 12, color: PremiumTokens.textMuted),
        ),
      );
      children.add(const SizedBox(height: 12));
      children.add(
        Row(
          children: [
            Expanded(
              child: OutlinedButton.icon(
                onPressed: _copyTranscript,
                icon: const Icon(Icons.copy_rounded, size: 16),
                label: const Text('Copy'),
                style: _outlinedStyle(),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: OutlinedButton.icon(
                onPressed: _transcribe,
                icon: const Icon(Icons.refresh_rounded, size: 16),
                label: const Text('Re-transcribe'),
                style: _outlinedStyle(),
              ),
            ),
          ],
        ),
      );
    } else {
      children.add(
        const Text(
          'No transcript yet. Gemini 3.5 Transcribe uses your Gemini API key and labels the speakers on calls up to 30 minutes.',
          style: TextStyle(
            fontSize: 12,
            color: PremiumTokens.textTertiary,
            height: 1.4,
          ),
        ),
      );
      children.add(const SizedBox(height: 12));
      children.add(
        PremiumButton(
          label: 'Transcribe with Gemini',
          icon: Icons.auto_awesome_rounded,
          onPressed: _transcribe,
        ),
      );
    }

    if (_error.isNotEmpty) {
      children.add(const SizedBox(height: 10));
      children.add(
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(
            color: PremiumTokens.errorBg,
            borderRadius: BorderRadius.circular(PremiumTokens.radiusMd),
          ),
          child: Text(
            _error,
            style: const TextStyle(fontSize: 12, color: PremiumTokens.error),
          ),
        ),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: children,
    );
  }

  Widget _buildSegments(RecordingTranscript t) {
    if (t.segments.isEmpty) {
      return SelectableText(
        t.text,
        style: const TextStyle(
          fontSize: 13,
          color: PremiumTokens.textSecondary,
          height: 1.4,
        ),
      );
    }
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: PremiumTokens.surfaceGlass,
        borderRadius: BorderRadius.circular(PremiumTokens.radiusMd),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (var i = 0; i < t.segments.length; i++) ...[
            if (i > 0) const SizedBox(height: 8),
            _buildSegment(t.segments[i]),
          ],
        ],
      ),
    );
  }

  Widget _buildSegment(TranscriptSegment s) {
    final label = s.speakerLabel;
    final color = _speakerColor(s.speaker);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 3,
          height: 14,
          margin: const EdgeInsets.only(right: 8, top: 3),
          decoration: BoxDecoration(
            color: color.withAlpha(140),
            borderRadius: BorderRadius.circular(2),
          ),
        ),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (label.isNotEmpty || s.startSec != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 2),
                  child: Text(
                    [
                      if (label.isNotEmpty) label,
                      if (s.startSec != null)
                        _fmt((s.startSec! * 1000).round()),
                    ].join(' · '),
                    style: TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w600,
                      color: color.withAlpha(200),
                    ),
                  ),
                ),
              SelectableText(
                s.text,
                style: const TextStyle(
                  fontSize: 13,
                  color: PremiumTokens.textSecondary,
                  height: 1.4,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Color _speakerColor(String? speaker) {
    if (speaker == null || speaker.isEmpty) return PremiumTokens.textMuted;
    const palette = [
      PremiumTokens.accentPrimary,
      PremiumTokens.accentSecondary,
      PremiumTokens.success,
      PremiumTokens.warning,
    ];
    final m = RegExp(r'(\d+)$').firstMatch(speaker);
    final idx = m != null
        ? (int.tryParse(m.group(1)!) ?? 1) - 1
        : speaker.hashCode;
    return palette[idx.abs() % palette.length];
  }

  ButtonStyle _outlinedStyle() {
    return OutlinedButton.styleFrom(
      foregroundColor: PremiumTokens.accentPrimary,
      side: const BorderSide(color: PremiumTokens.borderGlass),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(PremiumTokens.radiusMd),
      ),
    );
  }

  static String _fmt(int ms) {
    final totalSec = (ms / 1000).floor();
    final h = totalSec ~/ 3600;
    final m = (totalSec % 3600) ~/ 60;
    final s = totalSec % 60;
    final mm = m.toString().padLeft(2, '0');
    final ss = s.toString().padLeft(2, '0');
    return h > 0 ? '$h:$mm:$ss' : '$mm:$ss';
  }
}
