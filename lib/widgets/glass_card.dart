import 'dart:ui';
import 'package:flutter/material.dart';
import '../theme/tokens.dart';

class GlassCard extends StatelessWidget {
  const GlassCard({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(16),
    this.borderRadius,
    this.blur = PremiumTokens.blurMd,
    this.glowColor,
    this.onTap,
  });

  final Widget child;
  final EdgeInsetsGeometry padding;
  final BorderRadius? borderRadius;
  final double blur;
  final Color? glowColor;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final radius =
        borderRadius ?? BorderRadius.circular(PremiumTokens.radiusLg);

    Widget card = ClipRRect(
      borderRadius: radius,
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: blur, sigmaY: blur),
        child: Container(
          decoration: BoxDecoration(
            gradient: const LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [
                Color(0x1FFFFFFF), // 12%
                Color(0x0DFFFFFF), // 5%
                Color(0x05FFFFFF), // 2%
              ],
              stops: [0.0, 0.08, 1.0],
            ),
            borderRadius: radius,
            // Rounded borders must be one colour; the top sheen comes from the gradient.
            border: Border.all(color: PremiumTokens.borderGlass, width: 0.5),
            boxShadow: [
              const BoxShadow(
                offset: Offset(0, 8),
                blurRadius: 24,
                color: Color(0x40000000),
              ),
              if (glowColor != null)
                BoxShadow(
                  color: glowColor!.withAlpha(30),
                  blurRadius: 24,
                  spreadRadius: -2,
                ),
            ],
          ),
          child: Padding(padding: padding, child: child),
        ),
      ),
    );

    if (onTap != null) {
      card = GestureDetector(onTap: onTap, child: card);
    }

    return card;
  }
}
