import 'dart:ui';
import 'package:flutter/material.dart';
import '../models.dart';
import '../theme/tokens.dart';

class AgentEdgeGlow extends StatelessWidget {
  final ChatState state;
  final bool active;
  final bool isCall;

  const AgentEdgeGlow({
    super.key,
    required this.state,
    required this.active,
    this.isCall = false,
  });

  Color get _stateColor {
    if (isCall) return PremiumTokens.warning;
    switch (state) {
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

  double get _intensity {
    if (!active) return 0.0;
    switch (state) {
      case ChatState.idle:
        return 0.0;
      case ChatState.connecting:
        return 0.3;
      case ChatState.recording:
        return 0.7;
      case ChatState.waiting:
        return 0.5;
      case ChatState.conversing:
        return 0.3;
    }
  }

  @override
  Widget build(BuildContext context) {
    final color = _stateColor;
    final intensity = _intensity;
    final baseOpacity = active && state != ChatState.idle ? 0.15 : 0.08;
    final opacity = (baseOpacity + intensity * 0.5).clamp(0.0, 1.0);

    return IgnorePointer(
      child: AnimatedOpacity(
        duration: PremiumTokens.durationSlow,
        opacity: active ? 1.0 : 0.0,
        child: Stack(
          children: [
            _buildBottom(color, opacity, intensity),
            _buildFocal(color, opacity, intensity),
            _buildSideStrip(color, intensity, Alignment.centerLeft),
            _buildSideStrip(color, intensity, Alignment.centerRight),
          ],
        ),
      ),
    );
  }

  Widget _buildBottom(Color color, double opacity, double intensity) {
    final height = 160.0 + intensity * 80.0;
    return Positioned(
      left: 0,
      right: 0,
      bottom: 0,
      child: AnimatedContainer(
        duration: PremiumTokens.durationSlow,
        curve: PremiumTokens.easeOut,
        height: height,
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.bottomCenter,
            end: Alignment.topCenter,
            colors: [
              color.withAlpha((opacity * 255 * 0.25).round()),
              color.withAlpha((opacity * 255 * 0.06).round()),
              Colors.transparent,
            ],
            stops: const [0.0, 0.4, 1.0],
          ),
        ),
      ),
    );
  }

  Widget _buildFocal(Color color, double opacity, double intensity) {
    final size = 300.0 + intensity * 150.0;
    return Positioned(
      bottom: 20,
      left: 0,
      right: 0,
      child: Center(
        child: AnimatedContainer(
          duration: PremiumTokens.durationSlow,
          curve: PremiumTokens.easeOut,
          width: size,
          height: size,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            gradient: RadialGradient(
              colors: [
                color.withAlpha((opacity * 255 * 0.18).round()),
                Colors.transparent,
              ],
              stops: const [0.0, 0.7],
            ),
          ),
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 30, sigmaY: 30),
            child: const SizedBox.expand(),
          ),
        ),
      ),
    );
  }

  Widget _buildSideStrip(Color color, double intensity, Alignment side) {
    final isLeft = side == Alignment.centerLeft;
    final sideOpacity = (0.1 + intensity * 0.9).clamp(0.0, 1.0);

    return Positioned(
      top: 0,
      bottom: 0,
      left: isLeft ? 0 : null,
      right: isLeft ? null : 0,
      child: AnimatedContainer(
        duration: PremiumTokens.durationSlow,
        curve: PremiumTokens.easeOut,
        width: 80,
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: isLeft ? Alignment.centerLeft : Alignment.centerRight,
            end: isLeft ? Alignment.centerRight : Alignment.centerLeft,
            colors: [
              color.withAlpha((sideOpacity * 255 * 0.12).round()),
              Colors.transparent,
            ],
          ),
        ),
      ),
    );
  }
}
