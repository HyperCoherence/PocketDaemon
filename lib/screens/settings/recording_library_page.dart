import 'dart:async';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../models.dart';
import '../../recording_events.dart';
import '../../theme/tokens.dart';
import '../../utils.dart';
import 'recording_detail_page.dart';

/// Every saved recording, newest first. Tap one to play it, read the details
/// and transcribe it.
class RecordingLibraryPage extends StatefulWidget {
  final MethodChannel control;
  const RecordingLibraryPage({super.key, required this.control});

  @override
  State<RecordingLibraryPage> createState() => _RecordingLibraryPageState();
}

class _RecordingLibraryPageState extends State<RecordingLibraryPage> {
  List<RecordingInfo> _recordings = [];
  bool _loaded = false;
  String _error = '';
  StreamSubscription<Map<String, dynamic>>? _sub;

  @override
  void initState() {
    super.initState();
    _sub = RecordingEvents.stream.listen((event) {
      // A finished transcription changes the badge on its card.
      if (event['type'] == 'recordingTranscription' &&
          (event['status'] == 'done' || event['status'] == 'error')) {
        _load();
      }
    });
    _load();
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final raw = await widget.control.invokeMethod('getRecordings');
      final list = <RecordingInfo>[];
      if (raw is List) {
        for (final item in raw) {
          if (item is Map) list.add(RecordingInfo.fromMap(item));
        }
      }
      if (!mounted) return;
      setState(() {
        _recordings = list;
        _loaded = true;
        _error = '';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loaded = true;
        _error = 'Could not read recordings: $e';
      });
    }
  }

  Future<void> _open(RecordingInfo rec) async {
    HapticFeedback.selectionClick();
    await Navigator.push(
      context,
      slideRoute(RecordingDetailPage(control: widget.control, recording: rec)),
    );
    _load();
  }

  @override
  Widget build(BuildContext context) {
    if (!_loaded) {
      return settingsScaffold(
        'Recording Library',
        const Center(child: CircularProgressIndicator()),
      );
    }

    return settingsScaffold(
      'Recording Library',
      RefreshIndicator(
        onRefresh: _load,
        color: PremiumTokens.accentPrimary,
        child: _recordings.isEmpty ? _buildEmpty() : _buildList(),
      ),
    );
  }

  Widget _buildEmpty() {
    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        const SizedBox(height: 120),
        Icon(
          Icons.library_music_outlined,
          size: 36,
          color: PremiumTokens.textMuted.withAlpha(80),
        ),
        const SizedBox(height: 12),
        Text(
          _error.isNotEmpty ? _error : 'No recordings yet',
          textAlign: TextAlign.center,
          style: const TextStyle(fontSize: 13, color: PremiumTokens.textMuted),
        ),
        const SizedBox(height: 6),
        const Text(
          'Calls are recorded when the matching toggle is on in Recordings.',
          textAlign: TextAlign.center,
          style: TextStyle(fontSize: 12, color: PremiumTokens.textMuted),
        ),
      ],
    );
  }

  Widget _buildList() {
    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
      itemCount: _recordings.length + 1,
      itemBuilder: (_, i) {
        if (i == _recordings.length) {
          return Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(
              '${_recordings.length} recording${_recordings.length == 1 ? '' : 's'} in /sdcard/PocketDaemon/recordings/',
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontSize: 11,
                color: PremiumTokens.textMuted,
              ),
            ),
          );
        }
        return _RecordingCard(recording: _recordings[i], onTap: _open);
      },
    );
  }
}

class _RecordingCard extends StatelessWidget {
  final RecordingInfo recording;
  final ValueChanged<RecordingInfo> onTap;

  const _RecordingCard({required this.recording, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final r = recording;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
        child: BackdropFilter(
          filter: ImageFilter.blur(
            sigmaX: PremiumTokens.blurMd,
            sigmaY: PremiumTokens.blurMd,
          ),
          child: Material(
            color: Colors.transparent,
            child: InkWell(
              borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
              onTap: () => onTap(r),
              child: Container(
                decoration: BoxDecoration(
                  gradient: const LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [Color(0x14FFFFFF), Color(0x05FFFFFF)],
                  ),
                  borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
                  border: const Border(
                    top: BorderSide(
                      color: PremiumTokens.borderGlassTop,
                      width: 0.5,
                    ),
                    left: BorderSide(
                      color: PremiumTokens.borderGlass,
                      width: 0.5,
                    ),
                    right: BorderSide(
                      color: PremiumTokens.borderGlass,
                      width: 0.5,
                    ),
                    bottom: BorderSide(
                      color: PremiumTokens.borderGlass,
                      width: 0.5,
                    ),
                  ),
                ),
                padding: const EdgeInsets.all(14),
                child: Row(
                  children: [
                    Container(
                      width: 36,
                      height: 36,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: r.accent.withAlpha(15),
                      ),
                      child: Icon(r.icon, size: 16, color: r.accent),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Expanded(
                                child: Text(
                                  r.displayTitle,
                                  style: const TextStyle(
                                    fontSize: 14,
                                    fontWeight: FontWeight.w600,
                                    color: PremiumTokens.textPrimary,
                                  ),
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                              Text(
                                r.timeLabel,
                                style: const TextStyle(
                                  fontSize: 11,
                                  color: PremiumTokens.textMuted,
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 3),
                          Row(
                            children: [
                              Text(
                                r.displayType,
                                style: TextStyle(
                                  fontSize: 11,
                                  color: r.accent.withAlpha(180),
                                ),
                              ),
                              const Text(
                                ' · ',
                                style: TextStyle(
                                  fontSize: 11,
                                  color: PremiumTokens.textMuted,
                                ),
                              ),
                              Text(
                                r.durationLabel,
                                style: const TextStyle(
                                  fontSize: 11,
                                  color: PremiumTokens.textMuted,
                                ),
                              ),
                              const Text(
                                ' · ',
                                style: TextStyle(
                                  fontSize: 11,
                                  color: PremiumTokens.textMuted,
                                ),
                              ),
                              Text(
                                r.sizeLabel,
                                style: const TextStyle(
                                  fontSize: 11,
                                  color: PremiumTokens.textMuted,
                                ),
                              ),
                              const Spacer(),
                              if (r.transcribing)
                                const SizedBox(
                                  width: 12,
                                  height: 12,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 1.5,
                                    color: PremiumTokens.accentPrimary,
                                  ),
                                )
                              else if (r.transcript != null)
                                const Icon(
                                  Icons.notes_rounded,
                                  size: 14,
                                  color: PremiumTokens.success,
                                ),
                            ],
                          ),
                          if (r.transcript != null &&
                              r.transcript!.text.isNotEmpty) ...[
                            const SizedBox(height: 4),
                            Text(
                              r.transcript!.text,
                              style: const TextStyle(
                                fontSize: 12,
                                color: PremiumTokens.textTertiary,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ],
                        ],
                      ),
                    ),
                    const SizedBox(width: 8),
                    const Icon(
                      Icons.chevron_right_rounded,
                      size: 20,
                      color: PremiumTokens.textMuted,
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
