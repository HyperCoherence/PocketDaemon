import 'package:flutter/material.dart';
import '../theme/tokens.dart';

/// Ambient light washing up from the bottom and in from the edges in the
/// agent's current colour. Fades between colours rather than snapping.
class AgentEdgeGlow extends StatelessWidget {
  const AgentEdgeGlow({
    super.key,
    required this.color,
    required this.intensity,
  });

  final Color color;

  /// 0 hides the glow; 1 is the brightest it gets.
  final double intensity;

  static const _duration = Duration(milliseconds: 600);

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: TweenAnimationBuilder<Color?>(
        tween: ColorTween(end: color),
        duration: _duration,
        curve: PremiumTokens.easeOut,
        builder: (context, c, _) => TweenAnimationBuilder<double>(
          tween: Tween(end: intensity.clamp(0.0, 1.0)),
          duration: _duration,
          curve: PremiumTokens.easeOut,
          builder: (context, i, _) => CustomPaint(
            painter: _GlowPainter(c ?? color, i),
            size: Size.infinite,
          ),
        ),
      ),
    );
  }
}

class _GlowPainter extends CustomPainter {
  _GlowPainter(this.color, this.intensity);

  final Color color;
  final double intensity;

  @override
  void paint(Canvas canvas, Size size) {
    if (intensity <= 0.001) return;
    final rect = Offset.zero & size;

    // Bottom wash.
    final bottom = Rect.fromLTWH(
      0,
      size.height * 0.45,
      size.width,
      size.height * 0.55,
    );
    canvas.drawRect(
      bottom,
      Paint()
        ..shader = LinearGradient(
          begin: Alignment.bottomCenter,
          end: Alignment.topCenter,
          colors: [
            color.withValues(alpha: 0.22 * intensity),
            color.withValues(alpha: 0.05 * intensity),
            color.withValues(alpha: 0),
          ],
          stops: const [0.0, 0.45, 1.0],
        ).createShader(bottom),
    );

    // Pool of light behind the orb.
    final focal = Rect.fromCircle(
      center: Offset(size.width / 2, size.height * 0.5),
      radius: size.width * 0.85,
    );
    canvas.drawRect(
      rect,
      Paint()
        ..shader = RadialGradient(
          colors: [
            color.withValues(alpha: 0.16 * intensity),
            color.withValues(alpha: 0),
          ],
        ).createShader(focal),
    );

    // Edge strips.
    for (final left in [true, false]) {
      final strip = Rect.fromLTWH(
        left ? 0 : size.width - 56,
        0,
        56,
        size.height,
      );
      canvas.drawRect(
        strip,
        Paint()
          ..shader = LinearGradient(
            begin: left ? Alignment.centerLeft : Alignment.centerRight,
            end: left ? Alignment.centerRight : Alignment.centerLeft,
            colors: [
              color.withValues(alpha: 0.14 * intensity),
              color.withValues(alpha: 0),
            ],
          ).createShader(strip),
      );
    }
  }

  @override
  bool shouldRepaint(covariant _GlowPainter old) =>
      old.color != color || old.intensity != intensity;
}
