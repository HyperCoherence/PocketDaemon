import 'dart:ui';
import 'package:flutter/material.dart';
import '../models.dart';
import '../theme/tokens.dart';
import '../widgets/glass_card.dart';
import '../widgets/session_card.dart';

class HistoryPage extends StatefulWidget {
  final List<SessionSummary> sessions;
  final List<LogEntry> log;
  final VoidCallback onClear;

  const HistoryPage({
    super.key,
    required this.sessions,
    required this.log,
    required this.onClear,
  });

  @override
  State<HistoryPage> createState() => _HistoryPageState();
}

class _HistoryPageState extends State<HistoryPage> {
  int _selectedTab = 0;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
          child: Text(
            'History',
            style: Theme.of(context).textTheme.headlineMedium?.copyWith(
              fontFamily: 'Syne',
              fontWeight: FontWeight.w700,
              letterSpacing: -0.5,
              color: PremiumTokens.textPrimary,
            ),
          ),
        ),
        const SizedBox(height: 12),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          child: _buildPillTabs(),
        ),
        const SizedBox(height: 12),
        Expanded(
          child: _selectedTab == 0
              ? _buildSessionsList(context)
              : _buildRawLog(context),
        ),
      ],
    );
  }

  Widget _buildPillTabs() {
    return ClipRRect(
      borderRadius: BorderRadius.circular(PremiumTokens.radiusMd),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 8, sigmaY: 8),
        child: Container(
          height: 36,
          decoration: BoxDecoration(
            color: PremiumTokens.surfaceGlass,
            borderRadius: BorderRadius.circular(PremiumTokens.radiusMd),
            border: Border.all(color: PremiumTokens.borderGlass, width: 0.5),
          ),
          child: Row(
            children: [
              _buildPillTab('Sessions', 0),
              _buildPillTab('Raw Log', 1),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPillTab(String label, int index) {
    final selected = _selectedTab == index;
    return Expanded(
      child: GestureDetector(
        onTap: () => setState(() => _selectedTab = index),
        child: AnimatedContainer(
          duration: PremiumTokens.durationNormal,
          curve: PremiumTokens.easeSpring,
          margin: const EdgeInsets.all(3),
          decoration: BoxDecoration(
            color: selected
                ? PremiumTokens.surfaceGlassElevated
                : Colors.transparent,
            borderRadius: BorderRadius.circular(PremiumTokens.radiusSm + 1),
          ),
          alignment: Alignment.center,
          child: Text(
            label,
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: selected
                  ? PremiumTokens.textPrimary
                  : PremiumTokens.textMuted,
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildSessionsList(BuildContext context) {
    if (widget.sessions.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.history_outlined,
              size: 32,
              color: PremiumTokens.textMuted.withAlpha(60),
            ),
            const SizedBox(height: 10),
            const Text(
              'No sessions yet',
              style: TextStyle(fontSize: 13, color: PremiumTokens.textMuted),
            ),
          ],
        ),
      );
    }

    return ListView.builder(
      padding: const EdgeInsets.symmetric(horizontal: 20),
      itemCount: widget.sessions.length,
      itemBuilder: (_, i) => SessionCard(session: widget.sessions[i]),
    );
  }

  Widget _buildRawLog(BuildContext context) {
    if (widget.log.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.timeline_outlined,
              size: 32,
              color: PremiumTokens.textMuted.withAlpha(60),
            ),
            const SizedBox(height: 10),
            const Text(
              'No activity yet',
              style: TextStyle(fontSize: 13, color: PremiumTokens.textMuted),
            ),
          ],
        ),
      );
    }

    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          child: Row(
            children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 1),
                decoration: BoxDecoration(
                  color: PremiumTokens.surfaceGlass,
                  borderRadius: BorderRadius.circular(PremiumTokens.radiusMd),
                ),
                child: Text(
                  '${widget.log.length}',
                  style: const TextStyle(
                    fontSize: 11,
                    color: PremiumTokens.textMuted,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
              const Spacer(),
              GestureDetector(
                onTap: widget.onClear,
                child: Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 4,
                  ),
                  decoration: BoxDecoration(
                    color: PremiumTokens.surfaceGlass,
                    borderRadius: BorderRadius.circular(PremiumTokens.radiusSm),
                  ),
                  child: const Text(
                    'Clear',
                    style: TextStyle(
                      color: PremiumTokens.textMuted,
                      fontSize: 12,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 8),
        Expanded(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: GlassCard(
              padding: EdgeInsets.zero,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
                child: ListView.builder(
                  padding: const EdgeInsets.all(14),
                  itemCount: widget.log.length,
                  itemBuilder: (_, i) {
                    final entry = widget.log[i];
                    return Padding(
                      padding: const EdgeInsets.symmetric(vertical: 2),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            entry.time,
                            style: const TextStyle(
                              fontFamily: 'monospace',
                              fontSize: 11,
                              color: PremiumTokens.textMuted,
                            ),
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Text(
                              entry.message,
                              style: TextStyle(
                                fontFamily: 'monospace',
                                fontSize: 11,
                                color: _logEntryColor(entry.message),
                              ),
                            ),
                          ),
                        ],
                      ),
                    );
                  },
                ),
              ),
            ),
          ),
        ),
        const SizedBox(height: 16),
      ],
    );
  }

  Color _logEntryColor(String msg) {
    if (msg.startsWith('ERROR')) return PremiumTokens.error;
    if (msg.contains(': ')) return PremiumTokens.textTertiary;
    return PremiumTokens.textMuted;
  }
}
