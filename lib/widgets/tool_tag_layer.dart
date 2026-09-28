import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import '../agent_activity.dart';

/// Tool calls pop out of the orb as small tags that drift up and fade, so the
/// user sees what the agent is doing while it talks.
///
/// Fills its parent; tags start at the parent's centre, where the orb sits.
class ToolTagLayer extends StatefulWidget {
  const ToolTagLayer({super.key, required this.tools, required this.orbRadius});

  final Stream<ToolEvent> tools;
  final double orbRadius;

  @override
  State<ToolTagLayer> createState() => _ToolTagLayerState();
}

class _Tag {
  _Tag(this.event, this.born, this.angle);
  final ToolEvent event;
  final Duration born;
  final double angle;
}

class _ToolTagLayerState extends State<ToolTagLayer>
    with SingleTickerProviderStateMixin {
  static const _life = Duration(milliseconds: 2800);
  static const _pop = 0.12; // share of the life spent popping out

  // Upper-hemisphere launch angles, alternating sides so tags don't stack.
  static const _angles = [-1.95, -1.2, -2.35, -0.8, -1.6, -2.7, -0.45];

  late final Ticker _ticker;
  StreamSubscription<ToolEvent>? _sub;
  final _tags = <_Tag>[];
  Duration _now = Duration.zero;
  int _launches = 0;

  @override
  void initState() {
    super.initState();
    _ticker = createTicker(_tick);
    _sub = widget.tools.listen(_spawn);
  }

  @override
  void didUpdateWidget(covariant ToolTagLayer old) {
    super.didUpdateWidget(old);
    if (old.tools != widget.tools) {
      _sub?.cancel();
      _sub = widget.tools.listen(_spawn);
    }
  }

  void _spawn(ToolEvent e) {
    if (!mounted) return;
    HapticFeedback.selectionClick();
    final angle = _angles[_launches++ % _angles.length];
    if (!_ticker.isActive) {
      // A restarted ticker counts from zero again.
      _now = Duration.zero;
      _ticker.start();
    }
    setState(() => _tags.add(_Tag(e, _now, angle)));
  }

  void _tick(Duration elapsed) {
    _now = elapsed;
    _tags.removeWhere((t) => elapsed - t.born > _life);
    if (_tags.isEmpty) _ticker.stop();
    setState(() {});
  }

  @override
  void dispose() {
    _sub?.cancel();
    _ticker.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_tags.isEmpty) return const SizedBox.expand();
    return IgnorePointer(
      child: Stack(
        clipBehavior: Clip.none,
        alignment: Alignment.center,
        children: [for (final tag in _tags) ..._buildTag(tag)],
      ),
    );
  }

  List<Widget> _buildTag(_Tag tag) {
    final p = ((_now - tag.born).inMicroseconds / _life.inMicroseconds).clamp(
      0.0,
      1.0,
    );
    final dir = Offset(math.cos(tag.angle), math.sin(tag.angle));
    final r = widget.orbRadius;
    final color = tag.event.info.color;

    final double scale;
    final double dist;
    final double rise;
    if (p < _pop) {
      final q = p / _pop;
      scale = 0.3 + 0.7 * Curves.easeOutBack.transform(q);
      dist = r * 0.55 + r * 0.5 * Curves.easeOutCubic.transform(q);
      rise = 0;
    } else {
      final q = (p - _pop) / (1 - _pop);
      scale = 1 - 0.12 * q;
      dist = r * 1.05 + 70 * Curves.easeOutCubic.transform(q);
      rise = 90 * Curves.easeInCubic.transform(q);
    }
    final opacity = p < 0.65 ? 1.0 : 1 - (p - 0.65) / 0.35;
    final offset = dir * dist + Offset(0, -rise);

    // A ring bursts from the orb's edge where the tag leaves it.
    final burst = p < 0.22 ? p / 0.22 : 1.0;
    return [
      if (burst < 1)
        Transform.translate(
          offset: dir * r * 0.95,
          child: Container(
            width: 18 + 46 * burst,
            height: 18 + 46 * burst,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              border: Border.all(
                color: color.withValues(alpha: 0.8 * (1 - burst)),
                width: 2 * (1 - burst) + 0.5,
              ),
            ),
          ),
        ),
      Transform.translate(
        offset: offset,
        child: Opacity(
          opacity: opacity.clamp(0.0, 1.0),
          child: Transform.scale(
            scale: scale,
            child: _ToolTag(info: tag.event.info),
          ),
        ),
      ),
    ];
  }
}

class _ToolTag extends StatelessWidget {
  const _ToolTag({required this.info});

  final ToolInfo info;

  @override
  Widget build(BuildContext context) {
    final color = info.color;
    return Container(
      padding: const EdgeInsets.fromLTRB(9, 6, 12, 6),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(999),
        color: Color.lerp(const Color(0xFF0B1020), color, 0.18),
        border: Border.all(color: color.withValues(alpha: 0.7), width: 1),
        boxShadow: [
          BoxShadow(color: color.withValues(alpha: 0.45), blurRadius: 18),
        ],
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(info.icon, size: 15, color: color),
          const SizedBox(width: 6),
          Text(
            info.label.toUpperCase(),
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w700,
              letterSpacing: 1.1,
              color: Color.lerp(color, Colors.white, 0.55),
            ),
          ),
        ],
      ),
    );
  }
}
