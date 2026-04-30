import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../models.dart';
import '../theme/tokens.dart';

class SessionCard extends StatefulWidget {
  final SessionSummary session;
  const SessionCard({super.key, required this.session});

  @override
  State<SessionCard> createState() => _SessionCardState();
}

class _SessionCardState extends State<SessionCard> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final s = widget.session;

    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
        child: BackdropFilter(
          filter: ImageFilter.blur(
            sigmaX: PremiumTokens.blurMd,
            sigmaY: PremiumTokens.blurMd,
          ),
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
                left: BorderSide(color: PremiumTokens.borderGlass, width: 0.5),
                right: BorderSide(color: PremiumTokens.borderGlass, width: 0.5),
                bottom: BorderSide(
                  color: PremiumTokens.borderGlass,
                  width: 0.5,
                ),
              ),
            ),
            child: Column(
              children: [
                InkWell(
                  borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
                  onTap: () {
                    HapticFeedback.selectionClick();
                    setState(() => _expanded = !_expanded);
                  },
                  child: Padding(
                    padding: const EdgeInsets.all(14),
                    child: Row(
                      children: [
                        Container(
                          width: 36,
                          height: 36,
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            color: s.accent.withAlpha(15),
                          ),
                          child: Icon(s.icon, size: 16, color: s.accent),
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
                                      s.displayTitle,
                                      style: const TextStyle(
                                        fontSize: 14,
                                        fontWeight: FontWeight.w600,
                                        color: PremiumTokens.textPrimary,
                                      ),
                                      overflow: TextOverflow.ellipsis,
                                    ),
                                  ),
                                  Text(
                                    s.timeLabel,
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
                                    s.displayType,
                                    style: TextStyle(
                                      fontSize: 11,
                                      color: s.accent.withAlpha(180),
                                    ),
                                  ),
                                  if (s.durationLabel.isNotEmpty) ...[
                                    const Text(
                                      ' · ',
                                      style: TextStyle(
                                        fontSize: 11,
                                        color: PremiumTokens.textMuted,
                                      ),
                                    ),
                                    Text(
                                      s.durationLabel,
                                      style: const TextStyle(
                                        fontSize: 11,
                                        color: PremiumTokens.textMuted,
                                      ),
                                    ),
                                  ],
                                  if (s.transcript.isNotEmpty) ...[
                                    const Text(
                                      ' · ',
                                      style: TextStyle(
                                        fontSize: 11,
                                        color: PremiumTokens.textMuted,
                                      ),
                                    ),
                                    Text(
                                      '${s.transcript.length} messages',
                                      style: const TextStyle(
                                        fontSize: 11,
                                        color: PremiumTokens.textMuted,
                                      ),
                                    ),
                                  ],
                                ],
                              ),
                              if (!_expanded && s.preview.isNotEmpty) ...[
                                const SizedBox(height: 4),
                                Text(
                                  s.preview,
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
                        AnimatedRotation(
                          turns: _expanded ? 0.5 : 0,
                          duration: PremiumTokens.durationNormal,
                          curve: PremiumTokens.easeSpring,
                          child: const Icon(
                            Icons.expand_more_rounded,
                            size: 20,
                            color: PremiumTokens.textMuted,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                AnimatedCrossFade(
                  firstChild: const SizedBox(width: double.infinity),
                  secondChild: _buildTranscript(context),
                  crossFadeState: _expanded
                      ? CrossFadeState.showSecond
                      : CrossFadeState.showFirst,
                  duration: PremiumTokens.durationSlow,
                  sizeCurve: PremiumTokens.easeSpring,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildTranscript(BuildContext context) {
    final entries = widget.session.transcript;
    if (entries.isEmpty) {
      return const Padding(
        padding: EdgeInsets.fromLTRB(14, 0, 14, 14),
        child: Text(
          'No transcript available',
          style: TextStyle(fontSize: 12, color: PremiumTokens.textMuted),
        ),
      );
    }

    return Container(
      margin: const EdgeInsets.fromLTRB(14, 0, 14, 14),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: PremiumTokens.surfaceGlass,
        borderRadius: BorderRadius.circular(PremiumTokens.radiusMd),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (final entry in entries)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 3),
              child: _buildTranscriptBubble(entry),
            ),
        ],
      ),
    );
  }

  Widget _buildTranscriptBubble(Map<String, String> entry) {
    final speaker = entry['speaker'] ?? '';
    final text = entry['text'] ?? '';
    final time = entry['time'] ?? '';
    final isAgent = speaker == 'model' || speaker == 'agent';
    final isTool = speaker == 'tool';

    if (isTool) {
      return Row(
        children: [
          const Icon(
            Icons.build_rounded,
            size: 10,
            color: PremiumTokens.textMuted,
          ),
          const SizedBox(width: 6),
          Text(
            'Tool: $text',
            style: const TextStyle(
              fontSize: 10,
              color: PremiumTokens.textMuted,
              fontStyle: FontStyle.italic,
            ),
          ),
        ],
      );
    }

    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 38,
          child: Text(
            time,
            style: const TextStyle(
              fontSize: 10,
              fontFamily: 'monospace',
              color: PremiumTokens.textMuted,
            ),
          ),
        ),
        Container(
          width: 3,
          height: 14,
          margin: const EdgeInsets.only(right: 8, top: 2),
          decoration: BoxDecoration(
            color: isAgent
                ? PremiumTokens.accentSecondary.withAlpha(120)
                : PremiumTokens.accentPrimary.withAlpha(120),
            borderRadius: BorderRadius.circular(2),
          ),
        ),
        Expanded(
          child: Text(
            text,
            style: const TextStyle(
              fontSize: 12,
              color: PremiumTokens.textSecondary,
              height: 1.3,
            ),
          ),
        ),
      ],
    );
  }
}
