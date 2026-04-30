import 'dart:math' as math;
import 'dart:ui';
import 'package:flutter/material.dart';
import '../models.dart';
import '../theme/tokens.dart';

class AgentPresenceChip extends StatefulWidget {
  final ChatState state;
  final bool isCall;

  const AgentPresenceChip({
    super.key,
    required this.state,
    this.isCall = false,
  });

  @override
  State<AgentPresenceChip> createState() => _AgentPresenceChipState();
}

class _AgentPresenceChipState extends State<AgentPresenceChip>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ticker;

  static const _numBars = 12;
  static const _barBaseH = 4.0;

  @override
  void initState() {
    super.initState();
    _ticker = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 4),
    )..repeat();
  }

  @override
  void dispose() {
    _ticker.dispose();
    super.dispose();
  }

  Color get _stateColor {
    if (widget.isCall) return PremiumTokens.warning;
    switch (widget.state) {
      case ChatState.idle:
        return PremiumTokens.stateIdle;
      case ChatState.connecting:
        return PremiumTokens.stateIdle;
      case ChatState.recording:
        return PremiumTokens.stateListening;
      case ChatState.waiting:
        return PremiumTokens.stateThinking;
      case ChatState.conversing:
        return PremiumTokens.stateListening;
    }
  }

  String get _label {
    if (widget.isCall) return 'Handling Call...';
    switch (widget.state) {
      case ChatState.idle:
        return 'Ready';
      case ChatState.connecting:
        return 'Connecting...';
      case ChatState.recording:
        return 'Listening...';
      case ChatState.waiting:
        return 'Thinking...';
      case ChatState.conversing:
        return 'Listening...';
    }
  }

  double _barScale(int i, double t) {
    final half = _numBars / 2;
    final dist = (i - half + 0.5).abs() / half;
    final centerBoost = 1.0 - dist * 0.6;

    switch (widget.state) {
      case ChatState.idle:
        final wave = math.sin(t * math.pi * 2 + i * 0.4) * 0.15 + 0.1;
        return 1.0 + wave * centerBoost;

      case ChatState.connecting:
        final ramp = (math.sin(t * math.pi * 3) * 0.5 + 0.5);
        final wave = math.sin(t * math.pi * 4 + i * 0.5) * 0.3;
        return 1.0 + (ramp * 0.6 + wave) * centerBoost;

      case ChatState.recording:
        final w1 = math.sin(t * math.pi * 6 + i * 0.7) * 0.45;
        final w2 = math.sin(t * math.pi * 10 + i * 1.1) * 0.25;
        final w3 = math.sin(t * math.pi * 3 + i * 0.3) * 0.15;
        return 1.0 + (w1 + w2 + w3).clamp(0.0, 1.2) * centerBoost;

      case ChatState.waiting:
        final pulse = math.sin(t * math.pi * 1.5) * 0.12 + 0.08;
        final offset = math.sin(i * 0.8) * 0.05;
        return 1.0 + (pulse + offset) * centerBoost;

      case ChatState.conversing:
        final w1 = math.sin(t * math.pi * 4 + i * 0.6) * 0.3;
        final w2 = math.sin(t * math.pi * 2.5 + i * 0.4) * 0.15;
        return 1.0 + (w1 + w2).clamp(0.0, 0.8) * centerBoost;
    }
  }

  @override
  Widget build(BuildContext context) {
    final color = _stateColor;

    return ClipRRect(
      borderRadius: BorderRadius.circular(999),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
        child: AnimatedContainer(
          duration: PremiumTokens.durationNormal,
          curve: PremiumTokens.easeOut,
          padding: const EdgeInsets.fromLTRB(14, 10, 20, 10),
          decoration: BoxDecoration(
            color: const Color(0xD9141423),
            borderRadius: BorderRadius.circular(999),
            border: Border.all(color: PremiumTokens.borderGlass, width: 1),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              _buildDot(color),
              const SizedBox(width: 10),
              _buildWaveform(color),
              const SizedBox(width: 10),
              _buildLabel(color),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildDot(Color color) {
    return AnimatedContainer(
      duration: PremiumTokens.durationNormal,
      width: 10,
      height: 10,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: color,
        boxShadow: [BoxShadow(color: color.withAlpha(100), blurRadius: 12)],
      ),
    );
  }

  Widget _buildWaveform(Color color) {
    return AnimatedBuilder(
      animation: _ticker,
      builder: (context, child) {
        final t = _ticker.value;
        return Row(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.center,
          children: List.generate(_numBars, (i) {
            final sy = _barScale(i, t);
            return Padding(
              padding: const EdgeInsets.symmetric(horizontal: 1),
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 60),
                width: 3,
                height: _barBaseH * sy,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(2),
                  color: color.withAlpha(130),
                ),
              ),
            );
          }),
        );
      },
    );
  }

  Widget _buildLabel(Color color) {
    return AnimatedSwitcher(
      duration: PremiumTokens.durationNormal,
      child: Text(
        _label,
        key: ValueKey(_label),
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w500,
          color: color.withAlpha(180),
        ),
      ),
    );
  }
}
