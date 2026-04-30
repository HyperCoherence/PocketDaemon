import 'package:flutter/animation.dart';
import 'package:flutter/painting.dart';

abstract final class PremiumTokens {
  // ── Signature gradient (from SyncSpark globals.css) ──
  static const gradientFrom = Color(0xFF0f172a); // slate-900
  static const gradientVia = Color(0xFF0c4a6e); // cyan-900
  static const gradientTo = Color(0xFF312e81); // indigo-900

  static const shellGradient = LinearGradient(
    begin: Alignment(-.8, -.6),
    end: Alignment(.8, .6),
    colors: [Color(0xFF09111d), Color(0xFF0f1728), Color(0xFF060709)],
  );

  static const shellRadialCyan = RadialGradient(
    center: Alignment(-.44, -.32),
    radius: .72,
    colors: [
      Color(0x5638BDF8), // sky-400 @ 34%
      Color(0x3317447E),
      Color(0x00000000),
    ],
    stops: [0.0, 0.4, 0.74],
  );

  static const shellRadialIndigo = RadialGradient(
    center: Alignment(.52, .32),
    radius: .6,
    colors: [
      Color(0x1F818CF8), // indigo-400 @ 12%
      Color(0x0F3A327C),
      Color(0x00000000),
    ],
    stops: [0.0, 0.38, 0.72],
  );

  // ── Surfaces — glass morphism ──
  static const surfaceGlass = Color(0x0FFFFFFF); // 6%
  static const surfaceGlassHover = Color(0x1AFFFFFF); // 10%
  static const surfaceGlassElevated = Color(0x14FFFFFF); // 8%
  static const surfaceSolid = Color(0xD90F172A); // slate-900 @ 85%

  // ── Text hierarchy (sky-200 tints) ──
  static const textPrimary = Color(0xFFFFFFFF);
  static const textSecondary = Color(0xE6BAE6FD); // sky-200 @ 90%
  static const textTertiary = Color(0xB3BAE6FD); // sky-200 @ 70%
  static const textMuted = Color(0x80BAE6FD); // sky-200 @ 50%

  // ── Accent ──
  static const accentPrimary = Color(0xFF38BDF8); // sky-400
  static const accentPrimaryHover = Color(0xFF7DD3FC); // sky-300
  static const accentSecondary = Color(0xFF818CF8); // indigo-400
  static const accentGradient = LinearGradient(
    colors: [accentPrimary, accentSecondary],
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
  );

  // ── Status ──
  static const success = Color(0xFF34D399);
  static const successBg = Color(0x1F34D399); // 12%
  static const warning = Color(0xFFFBBF24);
  static const warningBg = Color(0x1FFBBF24);
  static const error = Color(0xFFF87171);
  static const errorBg = Color(0x1FF87171);

  // ── Agent state colors (from agent-presence/types.ts STATE_RGB) ──
  static const stateIdle = Color(0xFF38BDF8);
  static const stateListening = Color(0xFF60A5FA);
  static const stateThinking = Color(0xFFA78BFA);
  static const stateSpeaking = Color(0xFF818CF8);

  // ── Borders ──
  static const border = Color(0x0FFFFFFF); // 6%
  static const borderHover = Color(0x1AFFFFFF); // 10%
  static const borderFocus = Color(0x6638BDF8); // sky-400 @ 40%
  static const borderGlass = Color(0x1AFFFFFF); // 10%
  static const borderGlassTop = Color(0x4DFFFFFF); // 30%

  // ── Radii ──
  static const radiusSm = 6.0;
  static const radiusMd = 10.0;
  static const radiusLg = 14.0;
  static const radiusXl = 18.0;
  static const radius2xl = 22.0;

  // ── Shadows ──
  static const shadowSm = [
    BoxShadow(offset: Offset(0, 1), blurRadius: 2, color: Color(0x26000000)),
  ];
  static const shadowMd = [
    BoxShadow(offset: Offset(0, 2), blurRadius: 8, color: Color(0x33000000)),
  ];
  static const shadowLg = [
    BoxShadow(offset: Offset(0, 8), blurRadius: 24, color: Color(0x40000000)),
  ];
  static const shadowXl = [
    BoxShadow(offset: Offset(0, 16), blurRadius: 48, color: Color(0x4D000000)),
  ];

  // ── Motion ──
  static const easeSpring = Cubic(0.25, 1, 0.5, 1);
  static const easeOut = Cubic(0.16, 1, 0.3, 1);
  static const durationFast = Duration(milliseconds: 150);
  static const durationNormal = Duration(milliseconds: 200);
  static const durationSlow = Duration(milliseconds: 350);

  // ── Blur ──
  static const blurSm = 8.0;
  static const blurMd = 12.0;
  static const blurLg = 20.0;
  static const blurXl = 32.0;
}
