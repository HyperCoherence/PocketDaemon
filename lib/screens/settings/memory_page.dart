import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../theme/tokens.dart';
import '../../utils.dart';

class MemoryPage extends StatefulWidget {
  final MethodChannel control;
  const MemoryPage({super.key, required this.control});
  @override
  State<MemoryPage> createState() => _MemoryPageState();
}

class _MemoryPageState extends State<MemoryPage> {
  static const _extractionEvents = EventChannel(
    'pocket_daemon/extraction_events',
  );

  bool _memoryEnabled = true;
  bool _loaded = false;
  bool _extracting = false;
  String? _extractionResult;
  int _progressCurrent = 0;
  int _progressTotal = 0;
  String _progressFile = '';
  StreamSubscription? _eventSub;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _eventSub?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final enabled =
          await widget.control.invokeMethod<bool>(
            'getMemoryExtractionEnabled',
          ) ??
          true;
      setState(() {
        _memoryEnabled = enabled;
        _loaded = true;
      });
    } catch (_) {
      setState(() => _loaded = true);
    }
  }

  Future<void> _toggleMemory(bool enabled) async {
    HapticFeedback.selectionClick();
    await widget.control.invokeMethod('setMemoryExtractionEnabled', {
      'enabled': enabled,
    });
    setState(() => _memoryEnabled = enabled);
  }

  void _onExtractionProgress(dynamic event) {
    if (event is! Map) return;
    setState(() {
      _progressCurrent = event['current'] as int? ?? 0;
      _progressTotal = event['total'] as int? ?? 0;
      _progressFile = event['filename']?.toString() ?? '';
    });
  }

  Future<void> _runExtraction() async {
    _eventSub?.cancel();
    _eventSub = _extractionEvents.receiveBroadcastStream().listen(
      _onExtractionProgress,
    );
    setState(() {
      _extracting = true;
      _extractionResult = null;
      _progressCurrent = 0;
      _progressTotal = 0;
      _progressFile = '';
    });
    try {
      final raw = await widget.control.invokeMethod('runMemoryExtraction');
      if (raw is Map) {
        final total = raw['total'] ?? 0;
        final processed = raw['processed'] ?? 0;
        final skipped = raw['skipped'] ?? 0;
        setState(() {
          _extractionResult =
              '$processed new, $skipped already done, $total total';
        });
      }
    } catch (e) {
      setState(() => _extractionResult = 'Error: $e');
    } finally {
      _eventSub?.cancel();
      _eventSub = null;
      setState(() => _extracting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return settingsScaffold(
      'Persistent Memory',
      !_loaded
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(20),
              children: [
                sectionCard(
                  context,
                  title: 'Automatic Extraction',
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 4),
                    child: Row(
                      children: [
                        Icon(
                          Icons.auto_awesome_rounded,
                          size: 18,
                          color: _memoryEnabled
                              ? PremiumTokens.accentPrimary
                              : PremiumTokens.textMuted,
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              const Text(
                                'After each session',
                                style: TextStyle(
                                  fontSize: 14,
                                  fontWeight: FontWeight.w500,
                                  color: PremiumTokens.textSecondary,
                                ),
                              ),
                              const Text(
                                'Extract facts & summaries when a session ends',
                                style: TextStyle(
                                  fontSize: 11,
                                  color: PremiumTokens.textMuted,
                                ),
                              ),
                            ],
                          ),
                        ),
                        Switch(value: _memoryEnabled, onChanged: _toggleMemory),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 14),
                sectionCard(
                  context,
                  title: 'Manual Extraction',
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(4, 4, 4, 8),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Process all unprocessed session logs',
                          style: TextStyle(
                            fontSize: 11,
                            color: PremiumTokens.textMuted,
                          ),
                        ),
                        const SizedBox(height: 10),
                        SizedBox(
                          width: double.infinity,
                          child: FilledButton(
                            onPressed: _extracting ? null : _runExtraction,
                            style: FilledButton.styleFrom(
                              backgroundColor: PremiumTokens.accentSecondary,
                              foregroundColor: Colors.white,
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(
                                  PremiumTokens.radiusMd,
                                ),
                              ),
                            ),
                            child: _extracting
                                ? Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      const SizedBox(
                                        height: 16,
                                        width: 16,
                                        child: CircularProgressIndicator(
                                          strokeWidth: 2,
                                          color: Colors.white,
                                        ),
                                      ),
                                      if (_progressTotal > 0) ...[
                                        const SizedBox(width: 10),
                                        Text(
                                          '$_progressCurrent / $_progressTotal',
                                          style: const TextStyle(fontSize: 13),
                                        ),
                                      ],
                                    ],
                                  )
                                : const Text('Run Now'),
                          ),
                        ),
                        if (_extracting && _progressTotal > 0) ...[
                          const SizedBox(height: 10),
                          ClipRRect(
                            borderRadius: BorderRadius.circular(4),
                            child: LinearProgressIndicator(
                              value: _progressCurrent / _progressTotal,
                              backgroundColor: PremiumTokens.surfaceGlass,
                              valueColor: const AlwaysStoppedAnimation(
                                PremiumTokens.accentSecondary,
                              ),
                              minHeight: 6,
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            _progressFile,
                            style: const TextStyle(
                              fontSize: 10,
                              color: PremiumTokens.textMuted,
                            ),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ],
                        if (_extractionResult != null) ...[
                          const SizedBox(height: 8),
                          Text(
                            _extractionResult!,
                            style: const TextStyle(
                              fontSize: 12,
                              color: PremiumTokens.textTertiary,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ],
            ),
    );
  }
}
