import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import '../agent_activity.dart';

/// The agent's orb. Every phase has its own shape and motion and the orb
/// morphs between them: a soft blob at rest, a ring of converging sparks while
/// connecting, a voice-driven electric edge while listening, a spinning
/// three-lobed form while thinking and a radial equalizer while speaking.
///
/// Purely visual: the caller wraps it in its own gestures.
class VoiceOrb extends StatefulWidget {
  const VoiceOrb({
    super.key,
    required this.phase,
    required this.size,
    this.activity,
    this.pressed = false,
  });

  final AgentPhase phase;
  final double size;
  final AgentActivity? activity;

  /// Squeezes the core while a finger is down on it.
  final bool pressed;

  @override
  State<VoiceOrb> createState() => _VoiceOrbState();
}

class _VoiceOrbState extends State<VoiceOrb>
    with SingleTickerProviderStateMixin {
  late final Ticker _ticker;
  final _model = _OrbModel();
  Duration _last = Duration.zero;

  @override
  void initState() {
    super.initState();
    _model.snapTo(widget.phase);
    _ticker = createTicker(_tick)..start();
  }

  void _tick(Duration elapsed) {
    final dt = ((elapsed - _last).inMicroseconds / 1e6).clamp(0.0, 0.05);
    _last = elapsed;
    _model.step(dt, widget.phase, widget.activity, widget.pressed);
  }

  @override
  void dispose() {
    _ticker.dispose();
    _model.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return RepaintBoundary(
      child: CustomPaint(
        size: Size.square(widget.size),
        painter: _OrbPainter(_model),
      ),
    );
  }
}

class _Wave {
  _Wave(this.born, this.strength, this.color);
  final double born;
  final double strength;
  final Color color;
}

/// Per-frame orb state, eased toward the current phase so every change blends.
class _OrbModel extends ChangeNotifier {
  double t = 0;
  Color color = Colors.transparent;
  Color accent = Colors.transparent;
  double level = 0;
  double energy = 0;
  double spinSpeed = 0;
  double spin = 0;
  double squeeze = 0;

  /// Squash-and-stretch kick when the phase changes; decays as a damped wobble.
  double kickAt = -10;

  /// How much each phase's shape contributes, indexed by [AgentPhase.index].
  final weights = List<double>.filled(AgentPhase.values.length, 0);

  /// Ripples from the voice and shockwaves from phase changes.
  final waves = <_Wave>[];
  double _lastRipple = -10;
  AgentPhase? _phase;

  double w(AgentPhase p) => weights[p.index];

  void snapTo(AgentPhase phase) {
    color = phase.color;
    accent = phase.accent;
    energy = _energy(phase);
    spinSpeed = _spin(phase);
    weights[phase.index] = 1;
    _phase = phase;
  }

  static double _energy(AgentPhase p) => switch (p) {
    AgentPhase.offline => 0.05,
    AgentPhase.standby => 0.2,
    AgentPhase.connecting => 0.4,
    AgentPhase.listening => 0.5,
    AgentPhase.muted => 0.1,
    AgentPhase.thinking => 0.65,
    AgentPhase.speaking => 0.55,
    AgentPhase.call => 0.5,
  };

  /// Revolutions per second of the rings.
  static double _spin(AgentPhase p) => switch (p) {
    AgentPhase.offline => 0.005,
    AgentPhase.standby => 0.03,
    AgentPhase.connecting => 0.6,
    AgentPhase.listening => 0.08,
    AgentPhase.muted => 0.01,
    AgentPhase.thinking => 0.45,
    AgentPhase.speaking => 0.15,
    AgentPhase.call => 0.12,
  };

  static double _ease(double dt, double rate) => 1 - math.exp(-dt * rate);

  void step(
    double dt,
    AgentPhase phase,
    AgentActivity? activity,
    bool pressed,
  ) {
    t += dt;
    if (phase != _phase) {
      _phase = phase;
      kickAt = t;
      waves.add(_Wave(t, 1, phase.color));
    }

    final k = _ease(dt, 4.5);
    color = Color.lerp(color, phase.color, k)!;
    accent = Color.lerp(accent, phase.accent, k)!;
    energy += (_energy(phase) - energy) * k;
    spinSpeed += (_spin(phase) - spinSpeed) * _ease(dt, 2.5);
    spin = (spin + spinSpeed * dt) % 1.0;
    for (final p in AgentPhase.values) {
      final target = p == phase ? 1.0 : 0.0;
      weights[p.index] += (target - weights[p.index]) * k;
    }
    squeeze += ((pressed ? 1 : 0) - squeeze) * _ease(dt, 18);

    double target = 0;
    if (activity != null && activity.levelsFresh) {
      if (phase == AgentPhase.listening) target = activity.mic;
      if (phase == AgentPhase.speaking || phase == AgentPhase.call) {
        target = math.max(activity.agent, activity.mic * 0.6);
      }
    }
    // Fast attack, slower release, like a VU meter.
    level += (target - level) * _ease(dt, target > level ? 16 : 5);

    if (phase == AgentPhase.listening && level > 0.3 && t - _lastRipple > 0.3) {
      waves.add(_Wave(t, level * 0.6, phase.color));
      _lastRipple = t;
    }
    waves.removeWhere((r) => t - r.born > waveLife);
    notifyListeners();
  }

  /// A damped wobble after each phase change: squash, overshoot, settle.
  double get kick {
    final s = t - kickAt;
    if (s > 1.2) return 0;
    return math.sin(s * 14) * math.exp(-s * 5);
  }

  static const waveLife = 1.5;
}

class _OrbPainter extends CustomPainter {
  _OrbPainter(this.m) : super(repaint: m);

  final _OrbModel m;

  static const _twoPi = 2 * math.pi;

  static Color _mix(Color a, Color b, double t) => Color.lerp(a, b, t)!;

  @override
  void paint(Canvas canvas, Size size) {
    final c = size.center(Offset.zero);
    final maxR = size.shortestSide / 2;
    final dim = m.w(AgentPhase.offline) + 0.5 * m.w(AgentPhase.muted);
    final color = _mix(m.color, const Color(0xFF64748B), dim * 0.7);
    final accent = _mix(m.accent, const Color(0xFF475569), dim * 0.7);
    final e = m.energy;
    final kick = m.kick;
    final coreR =
        maxR *
        0.38 *
        (1 + 0.07 * m.level + 0.02 * math.sin(m.t * 1.7) + 0.06 * kick) *
        (1 - 0.07 * m.squeeze);

    _aura(canvas, c, coreR, color, e);
    _waves(canvas, c, maxR, coreR);
    _hud(canvas, c, maxR, color, accent, e);
    _ring(canvas, c, maxR, color, accent, e);
    if (m.w(AgentPhase.connecting) > 0.01) {
      _sparks(canvas, c, maxR, coreR, color);
    }
    if (m.w(AgentPhase.thinking) > 0.01) {
      _orbiters(canvas, c, maxR, color, accent);
    }
    if (m.w(AgentPhase.speaking) > 0.01) {
      _equalizer(canvas, c, coreR, color, accent);
    }
    _core(canvas, c, coreR, color, accent, e, kick);
  }

  // ── Layers ──

  void _aura(Canvas canvas, Offset c, double coreR, Color color, double e) {
    final r = coreR * (2.0 + 0.5 * e + 0.6 * m.level);
    final a = (0.16 + 0.34 * e + 0.25 * m.level).clamp(0.0, 0.8);
    canvas.drawCircle(
      c,
      r,
      Paint()
        ..shader = RadialGradient(
          colors: [
            color.withValues(alpha: a),
            color.withValues(alpha: a * 0.3),
            color.withValues(alpha: 0),
          ],
          stops: const [0.2, 0.55, 1.0],
        ).createShader(Rect.fromCircle(center: c, radius: r)),
    );
  }

  void _waves(Canvas canvas, Offset c, double maxR, double coreR) {
    for (final w in m.waves) {
      final p = ((m.t - w.born) / _OrbModel.waveLife).clamp(0.0, 1.0);
      final eased = 1 - math.pow(1 - p, 3).toDouble();
      final radius = coreR + (maxR * 1.02 - coreR) * eased;
      final alpha = (1 - p) * 0.6 * w.strength;
      canvas.drawCircle(
        c,
        radius,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 0.8 + 3 * (1 - p) * w.strength
          ..color = w.color.withValues(alpha: alpha),
      );
    }
  }

  /// Instrument-panel geometry: a tick ring and counter-rotating dashed arcs.
  void _hud(
    Canvas canvas,
    Offset c,
    double maxR,
    Color color,
    Color accent,
    double e,
  ) {
    final alpha = 0.10 + 0.22 * e;
    final tickR = maxR * 0.88;
    final rot = -m.spin * _twoPi * 0.35;
    final tick = Paint()
      ..strokeCap = StrokeCap.round
      ..color = color.withValues(alpha: alpha);
    const ticks = 72;
    for (var i = 0; i < ticks; i++) {
      final a = rot + i * _twoPi / ticks;
      final major = i % 6 == 0;
      final len = major ? maxR * 0.045 : maxR * 0.018;
      final dir = Offset(math.cos(a), math.sin(a));
      tick.strokeWidth = major ? 1.6 : 1;
      canvas.drawLine(c + dir * tickR, c + dir * (tickR - len), tick);
    }

    // A bright marker sweeping the tick ring.
    final ma = m.spin * _twoPi * 1.5;
    final marker = c + Offset(math.cos(ma), math.sin(ma)) * tickR;
    canvas.drawCircle(
      marker,
      3,
      Paint()
        ..color = accent.withValues(alpha: 0.35 + 0.5 * e)
        ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 3),
    );

    final arcR = maxR * 0.78;
    final arcRect = Rect.fromCircle(center: c, radius: arcR);
    final arc = Paint()
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeWidth = 2
      ..color = accent.withValues(alpha: alpha * 1.4);
    final arcRot = m.spin * _twoPi * 1.2 + m.t * 0.1;
    for (var i = 0; i < 3; i++) {
      final start = arcRot + i * _twoPi / 3;
      final sweep = 0.35 + 0.25 * math.sin(m.t * 0.8 + i * 2);
      canvas.drawArc(arcRect, start, sweep, false, arc);
    }
  }

  void _ring(
    Canvas canvas,
    Offset c,
    double maxR,
    Color color,
    Color accent,
    double e,
  ) {
    final r = maxR * 0.66;
    final rect = Rect.fromCircle(center: c, radius: r);
    final shader = SweepGradient(
      colors: [
        color.withValues(alpha: 0),
        color.withValues(alpha: 0.9),
        accent.withValues(alpha: 0.8),
        accent.withValues(alpha: 0),
        color.withValues(alpha: 0),
      ],
      stops: const [0.0, 0.35, 0.6, 0.85, 1.0],
      transform: GradientRotation(m.spin * _twoPi),
    ).createShader(rect);
    final width = 1.2 + 1.8 * e + 3 * m.level;
    canvas.drawCircle(
      c,
      r,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = width + 6
        ..shader = shader
        ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 8),
    );
    canvas.drawCircle(
      c,
      r,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = width
        ..shader = shader,
    );
    canvas.drawCircle(
      c,
      r,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1
        ..color = color.withValues(alpha: 0.12 + 0.1 * e),
    );
  }

  /// Connecting: sparks spiral in from the rim and are swallowed by the core.
  void _sparks(
    Canvas canvas,
    Offset c,
    double maxR,
    double coreR,
    Color color,
  ) {
    final w = m.w(AgentPhase.connecting);
    const count = 14;
    for (var i = 0; i < count; i++) {
      final phase = (m.t * 0.9 + i / count) % 1.0;
      final r =
          maxR * 0.95 -
          (maxR * 0.95 - coreR * 0.8) * Curves.easeIn.transform(phase);
      final a = i * _twoPi / count + phase * 2.2;
      final p = c + Offset(math.cos(a), math.sin(a)) * r;
      final alpha = math.sin(phase * math.pi) * w;
      canvas.drawCircle(
        p,
        2.2 * (1 - phase * 0.5),
        Paint()..color = color.withValues(alpha: 0.9 * alpha),
      );
      final tail =
          c +
          Offset(math.cos(a - 0.12), math.sin(a - 0.12)) * (r + maxR * 0.06);
      canvas.drawLine(
        tail,
        p,
        Paint()
          ..strokeWidth = 1.2
          ..strokeCap = StrokeCap.round
          ..color = color.withValues(alpha: 0.35 * alpha),
      );
    }
  }

  void _orbiters(
    Canvas canvas,
    Offset c,
    double maxR,
    Color color,
    Color accent,
  ) {
    final w = m.w(AgentPhase.thinking);
    const count = 6;
    for (var i = 0; i < count; i++) {
      final speed = 0.9 + i * 0.17;
      final dir = i.isEven ? 1 : -1;
      final a = m.t * speed * dir + i * (_twoPi / count);
      final r = maxR * (0.72 + 0.1 * math.sin(m.t * 1.3 + i));
      final p = c + Offset(math.cos(a), math.sin(a)) * r;
      final dot = 1.6 + 1.4 * ((math.sin(m.t * 2.2 + i * 1.7) + 1) / 2);
      final col = i.isEven ? color : accent;
      canvas.drawCircle(
        p,
        dot * 3,
        Paint()
          ..color = col.withValues(alpha: 0.25 * w)
          ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 4),
      );
      canvas.drawCircle(
        p,
        dot,
        Paint()..color = col.withValues(alpha: 0.9 * w),
      );
    }
  }

  /// Speaking: radial bars bloom around the core with the agent's voice.
  void _equalizer(
    Canvas canvas,
    Offset c,
    double coreR,
    Color color,
    Color accent,
  ) {
    final w = m.w(AgentPhase.speaking);
    const bars = 56;
    final base = coreR * 1.16;
    final paint = Paint()
      ..strokeCap = StrokeCap.round
      ..strokeWidth = 2.4;
    for (var i = 0; i < bars; i++) {
      final a = i * _twoPi / bars - math.pi / 2;
      final n =
          (math.sin(i * 0.9 + m.t * 7) * 0.5 +
              math.sin(i * 0.37 - m.t * 4.3) * 0.35 +
              math.sin(i * 2.1 + m.t * 11) * 0.15 +
              1) /
          2;
      final len = coreR * (0.04 + (0.1 + 0.55 * m.level) * n) * w;
      final dir = Offset(math.cos(a), math.sin(a));
      paint.color = _mix(color, accent, n).withValues(alpha: 0.85 * w);
      canvas.drawLine(c + dir * base, c + dir * (base + len), paint);
    }
  }

  // ── Core: a closed shape whose radius blends every phase's form ──

  double _radius(double a, double r) {
    final t = m.t;
    final blob =
        math.sin(a * 3 + t * 1.3) * 0.5 +
        math.sin(a * 5 - t * 1.7) * 0.3 +
        math.sin(a * 8 + t * 0.9) * 0.2;
    final rot = m.spin * _twoPi;
    var f = 0.0;
    f += m.w(AgentPhase.offline) * (0.9 + 0.02 * blob);
    f += m.w(AgentPhase.standby) * (1 + 0.05 * blob);
    f += m.w(AgentPhase.call) * (1 + 0.07 * blob);
    // Muted: drawn in, nearly still.
    f += m.w(AgentPhase.muted) * (0.88 + 0.015 * blob);
    f += m.w(AgentPhase.connecting) * (0.62 + 0.035 * math.sin(a * 6 + t * 9));
    // Electric edge: high harmonics whose height follows the mic.
    final spikes =
        math.sin(a * 9 + t * 6) * 0.5 +
        math.sin(a * 14 - t * 9) * 0.3 +
        math.sin(a * 5 + t * 3) * 0.2;
    f +=
        m.w(AgentPhase.listening) *
        (1 + (0.03 + 0.22 * m.level) * spikes + 0.03 * blob);
    // Three rounded lobes turning with the rings.
    final lobe = math.max(0.0, math.cos(3 * (a - rot)));
    f +=
        m.w(AgentPhase.thinking) *
        (0.8 + 0.28 * math.pow(lobe, 1.6).toDouble() + 0.02 * blob);
    f +=
        m.w(AgentPhase.speaking) *
        (0.96 + (0.03 + 0.1 * m.level) * math.sin(a * 4 - t * 5) + 0.03 * blob);
    return r * f;
  }

  Path _shape(Offset c, double r, double kick) {
    final path = Path();
    const steps = 144;
    // Squash-and-stretch after a phase change: wider one way, taller the other.
    final sx = 1 + 0.08 * kick;
    final sy = 1 - 0.08 * kick;
    for (var i = 0; i <= steps; i++) {
      final a = i * _twoPi / steps;
      final rr = _radius(a, r);
      final p = c + Offset(math.cos(a) * rr * sx, math.sin(a) * rr * sy);
      if (i == 0) {
        path.moveTo(p.dx, p.dy);
      } else {
        path.lineTo(p.dx, p.dy);
      }
    }
    return path..close();
  }

  void _core(
    Canvas canvas,
    Offset c,
    double r,
    Color color,
    Color accent,
    double e,
    double kick,
  ) {
    final rect = Rect.fromCircle(center: c, radius: r * 1.3);
    final body = _shape(c, r, kick);

    // Soft halo hugging the core.
    canvas.drawPath(
      body,
      Paint()
        ..color = color.withValues(alpha: 0.4 + 0.3 * m.level)
        ..maskFilter = MaskFilter.blur(BlurStyle.normal, 14 + 16 * m.level),
    );

    canvas.drawPath(
      body,
      Paint()
        ..shader = RadialGradient(
          center: const Alignment(-0.25, -0.35),
          radius: 0.95,
          colors: [
            _mix(color, Colors.white, 0.55),
            color,
            _mix(accent, const Color(0xFF05060F), 0.35),
            const Color(0xFF05060F),
          ],
          stops: const [0.0, 0.35, 0.72, 1.0],
        ).createShader(rect),
    );

    // Accent swirl drifting inside the core.
    canvas.save();
    canvas.clipPath(body);
    final phase = m.t * (1.2 + 2.5 * e);
    final swirlC =
        c + Offset(math.cos(phase * 0.6), math.sin(phase * 0.6)) * r * 0.3;
    canvas.drawCircle(
      swirlC,
      r * 0.6,
      Paint()
        ..color = accent.withValues(alpha: 0.45)
        ..blendMode = BlendMode.plus
        ..maskFilter = MaskFilter.blur(BlurStyle.normal, r * 0.22),
    );
    canvas.restore();

    // Glass highlight and rim.
    final hl = Rect.fromCenter(
      center: c + Offset(-r * 0.28, -r * 0.42),
      width: r * 0.9,
      height: r * 0.42,
    );
    canvas.drawOval(
      hl,
      Paint()
        ..shader = RadialGradient(
          colors: [
            Colors.white.withValues(alpha: 0.32),
            Colors.white.withValues(alpha: 0),
          ],
        ).createShader(hl),
    );
    canvas.drawPath(
      body,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1
        ..color = Colors.white.withValues(alpha: 0.14 + 0.1 * e),
    );
  }

  @override
  bool shouldRepaint(covariant _OrbPainter old) => old.m != m;
}
