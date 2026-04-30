import 'dart:math' as math;
import 'package:flutter/material.dart';
import '../theme/tokens.dart';

Color stateColor(String state) {
  switch (state) {
    case 'listening':
      return PremiumTokens.stateListening;
    case 'thinking':
      return PremiumTokens.stateThinking;
    case 'speaking':
      return PremiumTokens.stateSpeaking;
    default:
      return PremiumTokens.stateIdle;
  }
}

class AgentOrbPainter extends CustomPainter {
  final double progress;
  final double intensity;
  final Color color;

  AgentOrbPainter({
    required this.progress,
    required this.intensity,
    required this.color,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final maxR = size.width / 2;

    final r = color.r;
    final g = color.g;
    final b = color.b;

    final coreR = (r + (1.0 - r) * 0.35 * 0.6);
    final coreG = (g + (1.0 - g) * 0.35 * 0.6);
    final coreB = (b + (1.0 - b) * 0.35 * 0.4);

    // Outer glow -- expands with intensity
    final glowRadius = maxR * (0.7 + intensity * 0.5);
    final glowOpacity = (0.3 + intensity * 0.7).clamp(0.0, 1.0);
    canvas.drawCircle(
      center,
      glowRadius,
      Paint()
        ..shader = RadialGradient(
          colors: [
            Color.from(
              alpha: 0.35 * glowOpacity,
              red: coreR,
              green: coreG,
              blue: coreB,
            ),
            Color.from(alpha: 0.12 * glowOpacity, red: r, green: g, blue: b),
            Colors.transparent,
          ],
          stops: const [0.0, 0.45, 0.75],
        ).createShader(Rect.fromCircle(center: center, radius: glowRadius))
        ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 20),
    );

    // Mid ring -- subtle border, state-colored
    final ringRadius = maxR * 0.42;
    final ringAlpha = (0.15 + intensity * 0.2).clamp(0.0, 1.0);
    canvas.drawCircle(
      center,
      ringRadius,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.0
        ..color = Color.from(
          alpha: ringAlpha,
          red: coreR,
          green: coreG,
          blue: coreB,
        ),
    );

    // Ring glow shadow
    if (intensity > 0.05) {
      canvas.drawCircle(
        center,
        ringRadius,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1.5
          ..color = Color.from(
            alpha: 0.3 * intensity,
            red: r,
            green: g,
            blue: b,
          )
          ..maskFilter = MaskFilter.blur(BlurStyle.normal, 12 + 12 * intensity),
      );
    }

    // Inner core -- radial gradient sphere with highlight
    final coreRadius = maxR * 0.35 * (1.0 + intensity * 0.15);
    canvas.drawCircle(
      center,
      coreRadius,
      Paint()
        ..shader = RadialGradient(
          center: const Alignment(-0.3, -0.3),
          radius: 1.0,
          colors: [
            Color.from(alpha: 0.55, red: coreR, green: coreG, blue: coreB),
            Color.from(alpha: 0.25, red: r, green: g, blue: b),
            const Color.from(alpha: 0.9, red: 0.06, green: 0.06, blue: 0.16),
            const Color.from(alpha: 1.0, red: 0.04, green: 0.04, blue: 0.1),
          ],
          stops: const [0.0, 0.35, 0.65, 1.0],
        ).createShader(Rect.fromCircle(center: center, radius: coreRadius)),
    );

    // Highlight spot
    final hlCenter = Offset(
      center.dx - coreRadius * 0.25,
      center.dy - coreRadius * 0.25,
    );
    canvas.drawOval(
      Rect.fromCenter(
        center: hlCenter,
        width: coreRadius * 0.6,
        height: coreRadius * 0.3,
      ),
      Paint()
        ..shader =
            RadialGradient(
              colors: [
                Color.from(
                  alpha: 0.18 + intensity * 0.12,
                  red: 1,
                  green: 1,
                  blue: 1,
                ),
                Colors.transparent,
              ],
            ).createShader(
              Rect.fromCenter(
                center: hlCenter,
                width: coreRadius * 0.6,
                height: coreRadius * 0.3,
              ),
            ),
    );

    // Expanding pulse rings
    for (int i = 0; i < 3; i++) {
      final p = (progress + i * 0.33) % 1.0;
      final ringA = intensity < 0.1
          ? 0.0
          : ((1.0 - p) * 0.18 * intensity).clamp(0.0, 1.0);
      if (ringA < 0.01) continue;
      final radius = maxR * (0.45 + 0.55 * p);
      final sw = (1.5 - p * 0.8 + intensity * 1.2).clamp(0.3, 3.0);
      canvas.drawCircle(
        center,
        radius,
        Paint()
          ..color = Color.from(alpha: ringA, red: r, green: g, blue: b)
          ..style = PaintingStyle.stroke
          ..strokeWidth = sw,
      );
    }

    // Blob deformation at high intensity
    if (intensity > 0.15) {
      final blobR = maxR * 0.28;
      final path = Path();
      const steps = 120;
      for (int deg = 0; deg <= steps; deg++) {
        final angle = deg * 2 * math.pi / steps;
        final deform =
            intensity *
            blobR *
            0.35 *
            (math.sin(angle * 3 + progress * math.pi * 2) * 0.55 +
                math.sin(angle * 5 - progress * math.pi * 4) * 0.3 +
                math.sin(angle * 7 + progress * math.pi * 6) * 0.15);
        final rr = blobR + deform;
        final pt = Offset(
          center.dx + rr * math.cos(angle),
          center.dy + rr * math.sin(angle),
        );
        if (deg == 0) {
          path.moveTo(pt.dx, pt.dy);
        } else {
          path.lineTo(pt.dx, pt.dy);
        }
      }
      path.close();
      canvas.drawPath(
        path,
        Paint()
          ..color = Color.from(
            alpha: 0.1 * intensity,
            red: r,
            green: g,
            blue: b,
          )
          ..maskFilter = MaskFilter.blur(BlurStyle.normal, 10),
      );
    }
  }

  @override
  bool shouldRepaint(covariant AgentOrbPainter old) =>
      old.progress != progress ||
      old.intensity != intensity ||
      old.color != color;
}
