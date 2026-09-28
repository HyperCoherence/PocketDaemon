import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';

/// Wraps a control in spring physics: it squashes while pressed and overshoots
/// back when released, like a physical key.
class Pressable extends StatefulWidget {
  const Pressable({
    super.key,
    required this.child,
    this.onTap,
    this.enabled = true,
    this.depth = 0.08,
  });

  final Widget child;
  final VoidCallback? onTap;
  final bool enabled;

  /// How far the control shrinks while held, as a fraction of its size.
  final double depth;

  @override
  State<Pressable> createState() => _PressableState();
}

class _PressableState extends State<Pressable>
    with SingleTickerProviderStateMixin {
  // 0 = at rest, 1 = fully pressed. Unbounded so the release can overshoot.
  late final AnimationController _press = AnimationController.unbounded(
    vsync: this,
  );

  static const _spring = SpringDescription(
    mass: 1,
    stiffness: 520,
    damping: 14,
  );

  void _springTo(double target, [double velocity = 0]) {
    _press.animateWith(
      SpringSimulation(_spring, _press.value, target, velocity),
    );
  }

  @override
  void dispose() {
    _press.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final active = widget.enabled && widget.onTap != null;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTapDown: active ? (_) => _springTo(1) : null,
      onTapUp: active ? (_) => _springTo(0, -6) : null,
      onTapCancel: active ? () => _springTo(0) : null,
      onTap: active ? widget.onTap : null,
      child: AnimatedBuilder(
        animation: _press,
        builder: (context, child) {
          final p = _press.value;
          // Squash a touch wider than tall, then stretch back past rest.
          return Transform(
            alignment: Alignment.center,
            transform: Matrix4.diagonal3Values(
              1 - widget.depth * p * 0.8,
              1 - widget.depth * p * 1.2,
              1,
            ),
            child: child,
          );
        },
        child: widget.child,
      ),
    );
  }
}

/// Springs its child in from nothing when [visible] turns on, and back out
/// when it turns off, overshooting on the way in.
class SpringReveal extends StatefulWidget {
  const SpringReveal({
    super.key,
    required this.visible,
    required this.child,
    this.from = Offset.zero,
  });

  final bool visible;
  final Widget child;

  /// Where the child flies in from, in logical pixels relative to its slot.
  final Offset from;

  @override
  State<SpringReveal> createState() => _SpringRevealState();
}

class _SpringRevealState extends State<SpringReveal>
    with SingleTickerProviderStateMixin {
  late final AnimationController _t = AnimationController.unbounded(
    vsync: this,
    value: widget.visible ? 1 : 0,
  );

  static const _spring = SpringDescription(
    mass: 1,
    stiffness: 260,
    damping: 16,
  );

  @override
  void didUpdateWidget(covariant SpringReveal old) {
    super.didUpdateWidget(old);
    if (old.visible != widget.visible) {
      _t.animateWith(
        SpringSimulation(_spring, _t.value, widget.visible ? 1 : 0, 0),
      );
    }
  }

  @override
  void dispose() {
    _t.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _t,
      builder: (context, child) {
        final v = _t.value;
        final clamped = v.clamp(0.0, 1.0);
        return IgnorePointer(
          ignoring: !widget.visible,
          child: Opacity(
            opacity: clamped,
            child: Transform.translate(
              offset: widget.from * (1 - v),
              child: Transform.scale(scale: 0.4 + 0.6 * v, child: child),
            ),
          ),
        );
      },
      child: widget.child,
    );
  }
}
