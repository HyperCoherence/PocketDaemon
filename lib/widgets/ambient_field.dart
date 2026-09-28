import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

/// Slow motion behind the voice screen: drifting motes over a perspective grid
/// that scrolls toward the viewer. Tinted by [color], livelier with [energy].
class AmbientField extends StatefulWidget {
  const AmbientField({super.key, required this.color, required this.energy});

  final Color color;

  /// 0..1: how bright and fast the field moves.
  final double energy;

  @override
  State<AmbientField> createState() => _AmbientFieldState();
}

class _Mote {
  _Mote(math.Random r)
    : x = r.nextDouble(),
      y = r.nextDouble(),
      size = 0.6 + r.nextDouble() * 1.8,
      speed = 0.004 + r.nextDouble() * 0.012,
      phase = r.nextDouble() * math.pi * 2;
  double x;
  double y;
  final double size;
  final double speed;
  final double phase;
}

class _FieldModel extends ChangeNotifier {
  double t = 0;
  double energy = 0;
  Color color = Colors.transparent;
  final motes = List.generate(46, (_) => _Mote(_rng));
  static final _rng = math.Random(7);

  void step(double dt, Color target, double targetEnergy) {
    final k = 1 - math.exp(-dt * 2.5);
    color = Color.lerp(color, target, k)!;
    energy += (targetEnergy - energy) * k;
    t += dt * (0.4 + 1.6 * energy);
    for (final m in motes) {
      m.y -= m.speed * dt * (0.5 + 2 * energy);
      if (m.y < -0.02) m.y += 1.04;
    }
    notifyListeners();
  }
}

class _AmbientFieldState extends State<AmbientField>
    with SingleTickerProviderStateMixin {
  late final Ticker _ticker;
  final _model = _FieldModel();
  Duration _last = Duration.zero;

  @override
  void initState() {
    super.initState();
    _model.color = widget.color;
    _model.energy = widget.energy;
    _ticker = createTicker((elapsed) {
      final dt = ((elapsed - _last).inMicroseconds / 1e6).clamp(0.0, 0.05);
      _last = elapsed;
      _model.step(dt, widget.color, widget.energy);
    })..start();
  }

  @override
  void dispose() {
    _ticker.dispose();
    _model.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: RepaintBoundary(
        child: CustomPaint(painter: _FieldPainter(_model), size: Size.infinite),
      ),
    );
  }
}

class _FieldPainter extends CustomPainter {
  _FieldPainter(this.m) : super(repaint: m);

  final _FieldModel m;

  @override
  void paint(Canvas canvas, Size size) {
    _grid(canvas, size);
    final dot = Paint();
    for (final mote in m.motes) {
      final twinkle = (math.sin(m.t * 2 + mote.phase) + 1) / 2;
      final p = Offset(
        mote.x * size.width + math.sin(m.t * 0.5 + mote.phase) * 6,
        mote.y * size.height,
      );
      dot.color = m.color.withValues(
        alpha: (0.10 + 0.35 * m.energy) * (0.3 + 0.7 * twinkle),
      );
      canvas.drawCircle(p, mote.size, dot);
    }
  }

  /// A floor grid in the lower third, receding to a horizon.
  void _grid(Canvas canvas, Size size) {
    final horizon = size.height * 0.66;
    final floor = size.height - horizon;
    final alpha = 0.04 + 0.08 * m.energy;
    final line = Paint()..strokeWidth = 1;
    final cx = size.width / 2;

    // Rows slide toward the viewer and fade in from the horizon.
    const rows = 9;
    final scroll = (m.t * 0.35) % 1.0;
    for (var i = 0; i < rows; i++) {
      final d = (i + scroll) / rows; // 0 at horizon, 1 at the bottom edge
      final y = horizon + floor * d * d;
      line.color = m.color.withValues(alpha: alpha * d);
      canvas.drawLine(Offset(0, y), Offset(size.width, y), line);
    }

    // Columns converge on the vanishing point.
    const cols = 12;
    for (var i = -cols; i <= cols; i++) {
      final bottomX = cx + i * size.width / cols * 1.4;
      line.shader = LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
        colors: [
          m.color.withValues(alpha: 0),
          m.color.withValues(alpha: alpha),
        ],
      ).createShader(Rect.fromLTWH(0, horizon, size.width, floor));
      canvas.drawLine(
        Offset(cx + i * 6.0, horizon),
        Offset(bottomX, size.height),
        line,
      );
    }
    line.shader = null;
  }

  @override
  bool shouldRepaint(covariant _FieldPainter old) => old.m != m;
}
