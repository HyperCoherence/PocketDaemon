import 'package:flutter/material.dart';
import '../theme/tokens.dart';

class PremiumButton extends StatelessWidget {
  const PremiumButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.icon,
    this.danger = false,
    this.expanded = true,
  });

  final String label;
  final VoidCallback? onPressed;
  final IconData? icon;
  final bool danger;
  final bool expanded;

  @override
  Widget build(BuildContext context) {
    final colors = danger
        ? [PremiumTokens.error, PremiumTokens.error]
        : [PremiumTokens.accentPrimary, PremiumTokens.accentSecondary];

    return SizedBox(
      width: expanded ? double.infinity : null,
      height: 50,
      child: DecoratedBox(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            colors: onPressed != null
                ? colors
                : [PremiumTokens.surfaceGlass, PremiumTokens.surfaceGlass],
            begin: Alignment.centerLeft,
            end: Alignment.centerRight,
          ),
          borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
          boxShadow: onPressed != null
              ? [
                  BoxShadow(
                    color: colors[0].withAlpha(40),
                    blurRadius: 12,
                    offset: const Offset(0, 4),
                  ),
                ]
              : null,
        ),
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
            onTap: onPressed,
            child: Center(
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (icon != null) ...[
                    Icon(icon, size: 20, color: Colors.white),
                    const SizedBox(width: 8),
                  ],
                  Text(
                    label,
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                      color: Colors.white,
                    ),
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
