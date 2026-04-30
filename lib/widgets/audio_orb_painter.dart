import 'dart:math' as math;
import 'package:flutter/material.dart';

class AudioOrbPainter extends CustomPainter {
  final double progress;
  final double intensity;
  final Color color;

  AudioOrbPainter({
    required this.progress,
    required this.intensity,
    required this.color,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final maxRadius = size.width / 2;

    if (intensity > 0.05) {
      canvas.drawCircle(
        center,
        maxRadius * 0.65,
        Paint()
          ..color = color.withAlpha((18 * intensity).toInt().clamp(0, 255))
          ..maskFilter = MaskFilter.blur(BlurStyle.normal, 16 + 8 * intensity),
      );
    }

    for (int i = 0; i < 3; i++) {
      final p = (progress + i * 0.33) % 1.0;
      final ringAlpha = intensity < 0.1
          ? 0
          : ((1.0 - p) * 45 * intensity).toInt().clamp(0, 255);
      if (ringAlpha == 0) continue;
      final radius = maxRadius * (0.5 + 0.5 * p);
      final strokeWidth = (2.0 - p * 1.0 + intensity * 1.5).clamp(0.5, 4.0);
      canvas.drawCircle(
        center,
        radius,
        Paint()
          ..color = color.withAlpha(ringAlpha)
          ..style = PaintingStyle.stroke
          ..strokeWidth = strokeWidth,
      );
    }

    if (intensity > 0.15) {
      final blobR = maxRadius * 0.3;
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
        final r = blobR + deform;
        final pt = Offset(
          center.dx + r * math.cos(angle),
          center.dy + r * math.sin(angle),
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
          ..color = color.withAlpha((25 * intensity).toInt().clamp(0, 255))
          ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 10),
      );
    }
  }

  @override
  bool shouldRepaint(covariant AudioOrbPainter old) =>
      old.progress != progress ||
      old.intensity != intensity ||
      old.color != color;
}
