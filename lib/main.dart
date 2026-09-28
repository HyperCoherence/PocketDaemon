import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'theme/premium_theme.dart';
import 'app_shell.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  // Draw under transparent system bars; the shell paints its own backdrop.
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
      systemNavigationBarColor: Colors.transparent,
      systemNavigationBarIconBrightness: Brightness.light,
      systemNavigationBarContrastEnforced: false,
    ),
  );
  runApp(const PocketDaemonApp());
}

class PocketDaemonApp extends StatelessWidget {
  const PocketDaemonApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'PocketDaemon',
      debugShowCheckedModeBanner: false,
      theme: premiumTheme(),
      // Honour large-text settings without letting them break fixed layouts.
      builder: (context, child) =>
          MediaQuery.withClampedTextScaling(maxScaleFactor: 1.3, child: child!),
      home: const AppShell(),
    );
  }
}
