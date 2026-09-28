import 'package:flutter/material.dart';
import 'tokens.dart';
import 'typography.dart';

ThemeData premiumTheme() {
  final colorScheme = ColorScheme.fromSeed(
    seedColor: PremiumTokens.accentPrimary,
    brightness: Brightness.dark,
  );

  return ThemeData(
    brightness: Brightness.dark,
    colorScheme: colorScheme,
    scaffoldBackgroundColor: Colors.transparent,
    useMaterial3: true,
    textTheme: PremiumTypography.textTheme.apply(
      bodyColor: PremiumTokens.textPrimary,
      displayColor: PremiumTokens.textPrimary,
    ),
    appBarTheme: const AppBarTheme(
      backgroundColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0,
      titleTextStyle: TextStyle(
        fontFamily: 'Syne',
        fontSize: 18,
        fontWeight: FontWeight.w600,
        color: PremiumTokens.textPrimary,
      ),
      iconTheme: IconThemeData(color: PremiumTokens.textSecondary),
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith((states) {
        if (states.contains(WidgetState.selected)) return Colors.white;
        return PremiumTokens.textMuted;
      }),
      trackColor: WidgetStateProperty.resolveWith((states) {
        if (states.contains(WidgetState.selected)) {
          return PremiumTokens.accentPrimary;
        }
        return PremiumTokens.surfaceGlass;
      }),
      trackOutlineColor: WidgetStateProperty.resolveWith((states) {
        if (states.contains(WidgetState.selected)) {
          return PremiumTokens.accentPrimary;
        }
        return PremiumTokens.borderGlass;
      }),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: PremiumTokens.surfaceGlass,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
        borderSide: const BorderSide(color: PremiumTokens.borderGlass),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
        borderSide: const BorderSide(color: PremiumTokens.borderGlass),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
        borderSide: const BorderSide(
          color: PremiumTokens.borderFocus,
          width: 1.5,
        ),
      ),
      hintStyle: const TextStyle(color: PremiumTokens.textMuted),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: PremiumTokens.surfaceSolid,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(PremiumTokens.radiusXl),
        side: const BorderSide(color: PremiumTokens.borderGlass),
      ),
    ),
    bottomSheetTheme: const BottomSheetThemeData(
      backgroundColor: PremiumTokens.surfaceSolid,
      showDragHandle: true,
      dragHandleColor: PremiumTokens.borderGlassTop,
    ),
    splashFactory: InkSparkle.splashFactory,
    listTileTheme: const ListTileThemeData(
      iconColor: PremiumTokens.accentPrimary,
      titleTextStyle: TextStyle(
        fontSize: 15,
        fontWeight: FontWeight.w500,
        color: PremiumTokens.textPrimary,
      ),
      subtitleTextStyle: TextStyle(
        fontSize: 13,
        height: 1.35,
        color: PremiumTokens.textTertiary,
      ),
      minVerticalPadding: 10,
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(64, 50),
        shape: const StadiumBorder(),
        backgroundColor: PremiumTokens.accentPrimary,
        foregroundColor: const Color(0xFF0B0F1A),
        textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(64, 50),
        shape: const StadiumBorder(),
        foregroundColor: PremiumTokens.accentPrimaryHover,
        side: const BorderSide(color: PremiumTokens.borderGlass),
        textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: PremiumTokens.accentPrimaryHover,
        textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
      ),
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: const Color(0xF2141B2D),
      contentTextStyle: const TextStyle(
        fontSize: 14,
        color: PremiumTokens.textPrimary,
      ),
      actionTextColor: PremiumTokens.accentPrimaryHover,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
        side: const BorderSide(color: PremiumTokens.borderGlass),
      ),
    ),
    dividerTheme: const DividerThemeData(
      color: PremiumTokens.border,
      thickness: 0.5,
    ),
    progressIndicatorTheme: const ProgressIndicatorThemeData(
      color: PremiumTokens.accentPrimary,
    ),
    extensions: const [AppColors.dark],
  );
}

@immutable
class AppColors extends ThemeExtension<AppColors> {
  const AppColors({
    required this.surface,
    required this.surfaceVariant,
    required this.surfaceDim,
    required this.cardBorder,
    required this.cardBorderSubtle,
    required this.activeGlow,
    required this.callAccent,
    required this.chatAccent,
    required this.dangerAccent,
    required this.warningAccent,
  });

  final Color surface;
  final Color surfaceVariant;
  final Color surfaceDim;
  final Color cardBorder;
  final Color cardBorderSubtle;
  final Color activeGlow;
  final Color callAccent;
  final Color chatAccent;
  final Color dangerAccent;
  final Color warningAccent;

  static const dark = AppColors(
    surface: Color(0xFF09111D),
    surfaceVariant: Color(0xFF0F1728),
    surfaceDim: Color(0xFF060709),
    cardBorder: Color(0x1AFFFFFF),
    cardBorderSubtle: Color(0x0FFFFFFF),
    activeGlow: PremiumTokens.accentPrimary,
    callAccent: PremiumTokens.warning,
    chatAccent: PremiumTokens.accentPrimary,
    dangerAccent: PremiumTokens.error,
    warningAccent: PremiumTokens.warning,
  );

  @override
  AppColors copyWith({
    Color? surface,
    Color? surfaceVariant,
    Color? surfaceDim,
    Color? cardBorder,
    Color? cardBorderSubtle,
    Color? activeGlow,
    Color? callAccent,
    Color? chatAccent,
    Color? dangerAccent,
    Color? warningAccent,
  }) => AppColors(
    surface: surface ?? this.surface,
    surfaceVariant: surfaceVariant ?? this.surfaceVariant,
    surfaceDim: surfaceDim ?? this.surfaceDim,
    cardBorder: cardBorder ?? this.cardBorder,
    cardBorderSubtle: cardBorderSubtle ?? this.cardBorderSubtle,
    activeGlow: activeGlow ?? this.activeGlow,
    callAccent: callAccent ?? this.callAccent,
    chatAccent: chatAccent ?? this.chatAccent,
    dangerAccent: dangerAccent ?? this.dangerAccent,
    warningAccent: warningAccent ?? this.warningAccent,
  );

  @override
  AppColors lerp(covariant AppColors? other, double t) {
    if (other == null) return this;
    return AppColors(
      surface: Color.lerp(surface, other.surface, t)!,
      surfaceVariant: Color.lerp(surfaceVariant, other.surfaceVariant, t)!,
      surfaceDim: Color.lerp(surfaceDim, other.surfaceDim, t)!,
      cardBorder: Color.lerp(cardBorder, other.cardBorder, t)!,
      cardBorderSubtle: Color.lerp(
        cardBorderSubtle,
        other.cardBorderSubtle,
        t,
      )!,
      activeGlow: Color.lerp(activeGlow, other.activeGlow, t)!,
      callAccent: Color.lerp(callAccent, other.callAccent, t)!,
      chatAccent: Color.lerp(chatAccent, other.chatAccent, t)!,
      dangerAccent: Color.lerp(dangerAccent, other.dangerAccent, t)!,
      warningAccent: Color.lerp(warningAccent, other.warningAccent, t)!,
    );
  }
}

extension AppColorsExt on BuildContext {
  AppColors get colors =>
      Theme.of(this).extension<AppColors>() ?? AppColors.dark;
}
