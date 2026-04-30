import 'dart:ui';
import 'package:flutter/material.dart';
import 'theme/tokens.dart';

Route<T> slideRoute<T>(Widget page) {
  return PageRouteBuilder<T>(
    pageBuilder: (context, animation, secondaryAnimation) => page,
    transitionsBuilder: (context, anim, secondaryAnimation, child) {
      return SlideTransition(
        position: Tween(begin: const Offset(1, 0), end: Offset.zero).animate(
          CurvedAnimation(parent: anim, curve: PremiumTokens.easeSpring),
        ),
        child: FadeTransition(opacity: anim, child: child),
      );
    },
    transitionDuration: PremiumTokens.durationSlow,
  );
}

Widget settingsScaffold(String title, Widget body) {
  return Scaffold(
    backgroundColor: Colors.transparent,
    extendBodyBehindAppBar: true,
    appBar: PreferredSize(
      preferredSize: const Size.fromHeight(56),
      child: ClipRRect(
        child: BackdropFilter(
          filter: ImageFilter.blur(
            sigmaX: PremiumTokens.blurLg,
            sigmaY: PremiumTokens.blurLg,
          ),
          child: AppBar(
            title: Text(
              title,
              style: const TextStyle(
                fontFamily: 'Syne',
                fontWeight: FontWeight.w600,
              ),
            ),
            backgroundColor: PremiumTokens.surfaceGlass,
            elevation: 0,
            scrolledUnderElevation: 0,
          ),
        ),
      ),
    ),
    body: SafeArea(child: body),
  );
}

Widget sectionCard(
  BuildContext context, {
  required String title,
  required Widget child,
}) {
  return ClipRRect(
    borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
    child: BackdropFilter(
      filter: ImageFilter.blur(
        sigmaX: PremiumTokens.blurMd,
        sigmaY: PremiumTokens.blurMd,
      ),
      child: Container(
        decoration: BoxDecoration(
          gradient: const LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [
              Color(0x14FFFFFF), // 8%
              Color(0x05FFFFFF), // 2%
            ],
          ),
          borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
          border: const Border(
            top: BorderSide(color: PremiumTokens.borderGlassTop, width: 0.5),
            left: BorderSide(color: PremiumTokens.borderGlass, width: 0.5),
            right: BorderSide(color: PremiumTokens.borderGlass, width: 0.5),
            bottom: BorderSide(color: PremiumTokens.borderGlass, width: 0.5),
          ),
        ),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: Theme.of(context).textTheme.titleSmall?.copyWith(
                  color: PremiumTokens.textTertiary,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(height: 14),
              child,
            ],
          ),
        ),
      ),
    ),
  );
}
