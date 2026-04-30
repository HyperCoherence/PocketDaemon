import 'package:flutter/material.dart';
import 'theme/premium_theme.dart';
import 'app_shell.dart';

void main() {
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
      home: const AppShell(),
    );
  }
}
