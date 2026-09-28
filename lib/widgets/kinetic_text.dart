import 'dart:math' as math;
import 'package:flutter/material.dart';

/// Text that changes like a title card: the old word lifts away while the new
/// one drops in letter by letter.
class KineticText extends StatefulWidget {
  const KineticText(this.text, {super.key, required this.style});

  final String text;
  final TextStyle style;

  @override
  State<KineticText> createState() => _KineticTextState();
}

class _KineticTextState extends State<KineticText>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 900),
    value: 1,
  );
  String? _previous;

  @override
  void didUpdateWidget(covariant KineticText old) {
    super.didUpdateWidget(old);
    if (old.text != widget.text) {
      _previous = old.text;
      _ctrl.forward(from: 0);
    }
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _ctrl,
      builder: (context, _) {
        final t = _ctrl.value;
        final outgoing = _previous;
        return Stack(
          alignment: Alignment.center,
          children: [
            if (outgoing != null && t < 0.35)
              _letters(outgoing, (i, n) {
                // Leave together, quickly, upward.
                final p = (t / 0.3).clamp(0.0, 1.0);
                return (1 - p, Offset(0, -10 * p), 1.0);
              }),
            _letters(widget.text, (i, n) {
              const start = 0.15;
              const stagger = 0.035;
              const span = 0.4;
              final p = ((t - start - i * stagger) / span).clamp(0.0, 1.0);
              final eased = Curves.easeOutBack.transform(p);
              return (
                Curves.easeOut.transform(p),
                Offset(0, 14 * (1 - eased)),
                0.6 + 0.4 * eased,
              );
            }),
          ],
        );
      },
    );
  }

  Widget _letters(
    String text,
    (double, Offset, double) Function(int i, int n) at,
  ) {
    final chars = text.characters.toList();
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (var i = 0; i < chars.length; i++)
          Builder(
            builder: (_) {
              final (opacity, offset, scale) = at(i, chars.length);
              return Opacity(
                opacity: math.max(0, math.min(1, opacity)),
                child: Transform.translate(
                  offset: offset,
                  child: Transform.scale(
                    scale: scale,
                    child: Text(chars[i], style: widget.style),
                  ),
                ),
              );
            },
          ),
      ],
    );
  }
}
