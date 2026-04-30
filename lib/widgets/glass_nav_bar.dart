import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../theme/tokens.dart';

class GlassNavBar extends StatelessWidget {
  const GlassNavBar({
    super.key,
    required this.selectedIndex,
    required this.onTap,
  });

  final int selectedIndex;
  final ValueChanged<int> onTap;

  static const _items = [
    _NavItem(Icons.home_rounded, 'Home'),
    _NavItem(Icons.chat_rounded, 'Chat'),
    _NavItem(Icons.sticky_note_2_rounded, 'Notes'),
    _NavItem(Icons.history_rounded, 'History'),
    _NavItem(Icons.settings_rounded, 'Settings'),
  ];

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      child: BackdropFilter(
        filter: ImageFilter.blur(
          sigmaX: PremiumTokens.blurLg,
          sigmaY: PremiumTokens.blurLg,
        ),
        child: Container(
          height: 64 + MediaQuery.of(context).padding.bottom,
          padding: EdgeInsets.only(
            bottom: MediaQuery.of(context).padding.bottom,
          ),
          decoration: const BoxDecoration(
            color: PremiumTokens.surfaceGlass,
            border: Border(
              top: BorderSide(color: PremiumTokens.border, width: 0.5),
            ),
          ),
          child: Row(
            children: List.generate(_items.length, (i) {
              final selected = i == selectedIndex;
              return Expanded(
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () {
                    if (i != selectedIndex) HapticFeedback.selectionClick();
                    onTap(i);
                  },
                  child: _NavItemWidget(item: _items[i], selected: selected),
                ),
              );
            }),
          ),
        ),
      ),
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
    return AnimatedContainer(
      duration: PremiumTokens.durationNormal,
      curve: PremiumTokens.easeSpring,
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            item.icon,
            size: 22,
            color: selected
                ? PremiumTokens.accentPrimary
                : PremiumTokens.textMuted,
          ),
          const SizedBox(height: 4),
          Text(
            item.label,
            style: TextStyle(
              fontSize: 10,
              fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
              color: selected
                  ? PremiumTokens.accentPrimary
                  : PremiumTokens.textMuted,
              letterSpacing: 0.2,
            ),
          ),
          const SizedBox(height: 3),
          AnimatedContainer(
            duration: PremiumTokens.durationNormal,
            curve: PremiumTokens.easeSpring,
            width: selected ? 16 : 0,
            height: 2,
            decoration: BoxDecoration(
              color: selected
                  ? PremiumTokens.accentPrimary
                  : Colors.transparent,
              borderRadius: BorderRadius.circular(1),
              boxShadow: selected
                  ? [
                      BoxShadow(
                        color: PremiumTokens.accentPrimary.withAlpha(100),
                        blurRadius: 6,
                      ),
                    ]
                  : null,
            ),
          ),
        ],
      ),
    );
  }
}
