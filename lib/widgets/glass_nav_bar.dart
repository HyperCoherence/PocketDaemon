import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../theme/tokens.dart';

/// Floating glass capsule with a highlight that glides to the selected tab.
class GlassNavBar extends StatelessWidget {
  const GlassNavBar({
    super.key,
    required this.selectedIndex,
    required this.onTap,
  });

  final int selectedIndex;
  final ValueChanged<int> onTap;

  static const _items = [
    _NavItem(Icons.graphic_eq_rounded, 'Voice'),
    _NavItem(Icons.chat_bubble_rounded, 'Chat'),
    _NavItem(Icons.sticky_note_2_rounded, 'Notes'),
    _NavItem(Icons.history_rounded, 'History'),
    _NavItem(Icons.tune_rounded, 'Settings'),
  ];

  static const _height = 64.0;

  @override
  Widget build(BuildContext context) {
    final bottomInset = MediaQuery.paddingOf(context).bottom;
    return Padding(
      padding: EdgeInsets.fromLTRB(14, 0, 14, 10 + bottomInset),
      child: DecoratedBox(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(PremiumTokens.radius2xl + 4),
          boxShadow: const [
            BoxShadow(
              offset: Offset(0, 10),
              blurRadius: 30,
              color: Color(0x66000000),
            ),
          ],
        ),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(PremiumTokens.radius2xl + 4),
          child: BackdropFilter(
            filter: ImageFilter.blur(
              sigmaX: PremiumTokens.blurXl,
              sigmaY: PremiumTokens.blurXl,
            ),
            child: Container(
              height: _height,
              padding: const EdgeInsets.all(6),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(
                  PremiumTokens.radius2xl + 4,
                ),
                color: const Color(0xB30B1120),
                border: Border.all(color: PremiumTokens.borderGlass),
              ),
              child: Stack(
                children: [
                  Positioned.fill(
                    child: _LiquidIndicator(
                      index: selectedIndex,
                      count: _items.length,
                    ),
                  ),
                  Row(
                    children: List.generate(_items.length, (i) {
                      return Expanded(
                        child: Semantics(
                          selected: i == selectedIndex,
                          button: true,
                          label: _items[i].label,
                          excludeSemantics: true,
                          child: GestureDetector(
                            behavior: HitTestBehavior.opaque,
                            onTap: () {
                              if (i != selectedIndex) {
                                HapticFeedback.selectionClick();
                              }
                              onTap(i);
                            },
                            child: _NavItemWidget(
                              item: _items[i],
                              selected: i == selectedIndex,
                            ),
                          ),
                        ),
                      );
                    }),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// The selected-tab highlight. Its leading edge races ahead and the trailing
/// edge catches up, so it stretches like a droplet in transit and snaps back.
class _LiquidIndicator extends StatefulWidget {
  const _LiquidIndicator({required this.index, required this.count});

  final int index;
  final int count;

  @override
  State<_LiquidIndicator> createState() => _LiquidIndicatorState();
}

class _LiquidIndicatorState extends State<_LiquidIndicator>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 520),
    value: 1,
  );
  late double _from = widget.index.toDouble();

  static const _lead = Interval(0, 0.6, curve: Curves.easeOutCubic);
  static const _trail = Interval(0.2, 1, curve: Curves.easeOutBack);

  @override
  void didUpdateWidget(covariant _LiquidIndicator old) {
    super.didUpdateWidget(old);
    if (old.index != widget.index) {
      _from = _position(old.index);
      _ctrl.forward(from: 0);
    }
  }

  /// Where the indicator is now, in tab units, so a tap mid-flight continues smoothly.
  double _position(int target) {
    final t = _ctrl.value;
    return _from + (target - _from) * Curves.easeOut.transform(t);
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, box) {
        final w = box.maxWidth / widget.count;
        return AnimatedBuilder(
          animation: _ctrl,
          builder: (context, _) {
            final t = _ctrl.value;
            final to = widget.index.toDouble();
            final right = to >= _from;
            final lead = _from + (to - _from) * _lead.transform(t);
            final trail = _from + (to - _from) * _trail.transform(t);
            final left = (right ? trail : lead) * w;
            final rightEdge = ((right ? lead : trail) + 1) * w;
            return Stack(
              children: [
                Positioned(
                  left: left,
                  width: (rightEdge - left).clamp(w * 0.6, box.maxWidth),
                  top: 0,
                  bottom: 0,
                  child: Container(
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(
                        PremiumTokens.radius2xl,
                      ),
                      gradient: LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: [
                          PremiumTokens.accentPrimary.withValues(alpha: 0.26),
                          PremiumTokens.accentSecondary.withValues(alpha: 0.18),
                        ],
                      ),
                      border: Border.all(
                        color: PremiumTokens.accentPrimary.withValues(
                          alpha: 0.4,
                        ),
                      ),
                      boxShadow: [
                        BoxShadow(
                          color: PremiumTokens.accentPrimary.withValues(
                            alpha: 0.25,
                          ),
                          blurRadius: 16,
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            );
          },
        );
      },
    );
  }
}

class _NavItem {
  const _NavItem(this.icon, this.label);
  final IconData icon;
  final String label;
}

class _NavItemWidget extends StatelessWidget {
  const _NavItemWidget({required this.item, required this.selected});

  final _NavItem item;
  final bool selected;

  @override
  Widget build(BuildContext context) {
    final color = selected
        ? PremiumTokens.accentPrimaryHover
        : PremiumTokens.textMuted;
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        AnimatedScale(
          scale: selected ? 1.08 : 1,
          duration: PremiumTokens.durationSlow,
          curve: PremiumTokens.easeSpring,
          child: Icon(
            item.icon,
            size: 22,
            color: color,
            shadows: selected
                ? [
                    Shadow(
                      color: PremiumTokens.accentPrimary.withValues(alpha: 0.7),
                      blurRadius: 12,
                    ),
                  ]
                : null,
          ),
        ),
        const SizedBox(height: 3),
        AnimatedDefaultTextStyle(
          duration: PremiumTokens.durationNormal,
          // Built on the theme's label style so it carries the app font;
          // AnimatedDefaultTextStyle replaces rather than merges.
          style: Theme.of(context).textTheme.labelSmall!.copyWith(
            fontSize: 11,
            fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
            color: color,
            letterSpacing: 0.2,
          ),
          child: Text(item.label, maxLines: 1),
        ),
      ],
    );
  }
}
