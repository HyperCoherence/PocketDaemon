// Renders the voice and chat screens at phone sizes with real fonts.
// Fails on any layout overflow; `--update-goldens` also writes previews to
// test/goldens/ (git-ignored).
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pocket_daemon/agent_activity.dart';
import 'package:pocket_daemon/models.dart';
import 'package:pocket_daemon/screens/chat_page.dart';
import 'package:pocket_daemon/screens/home_page.dart';
import 'package:pocket_daemon/theme/premium_theme.dart';
import 'package:pocket_daemon/theme/tokens.dart';
import 'package:pocket_daemon/widgets/glass_nav_bar.dart';

Future<void> _loadFont(String family, List<String> paths) async {
  final loader = FontLoader(family);
  for (final p in paths) {
    final bytes = File(p).readAsBytesSync();
    loader.addFont(Future.value(ByteData.view(bytes.buffer)));
  }
  await loader.load();
}

Widget _shell(Widget page, int tab) => MaterialApp(
  debugShowCheckedModeBanner: false,
  theme: premiumTheme(),
  home: DecoratedBox(
    decoration: const BoxDecoration(gradient: PremiumTokens.shellGradient),
    child: Stack(
      children: [
        const Positioned.fill(
          child: DecoratedBox(
            decoration: BoxDecoration(gradient: PremiumTokens.shellRadialCyan),
          ),
        ),
        Scaffold(
          body: SafeArea(child: page),
          bottomNavigationBar: GlassNavBar(selectedIndex: tab, onTap: (_) {}),
        ),
      ],
    ),
  ),
);

HomePage _home(
  AgentActivity activity, {
  ChatState state = ChatState.idle,
  String callStatus = '',
  bool takenOver = false,
  List<TranscriptLine> transcript = const [],
  bool agentEnabled = true,
  bool muted = false,
}) => HomePage(
  agentEnabled: agentEnabled,
  configured: true,
  allPermsGranted: true,
  callStatus: callStatus,
  takenOver: takenOver,
  chatState: state,
  chatMode: ChatMode.conversation,
  transcript: transcript,
  activity: activity,
  muted: muted,
  onToggleMute: () {},
  onToggleAgent: (_) {},
  onStartChat: () {},
  onStopChat: () {},
  onCancelChat: () {},
  onEndConversation: () {},
  onTakeOver: () {},
  onHangUp: () {},
  onHandToAgent: () {},
  onSendImage: (_, _, _, _) async {},
  control: const MethodChannel('test'),
);

List<TranscriptLine> _talk() => [
  TranscriptLine('user', 'Where am I right now, and is there a pharmacy open nearby?'),
  TranscriptLine('tool', 'get_location'),
  TranscriptLine('agent', "You're on Main Street. The closest open pharmacy is a four minute walk north."),
  TranscriptLine('user', 'Great, text that to me.'),
];

void main() {
  setUpAll(() async {
    final root = Platform.environment['FLUTTER_ROOT']!;
    final fonts = '$root/bin/cache/artifacts/material_fonts';
    await _loadFont('Roboto', [
      '$fonts/roboto-regular.ttf',
      '$fonts/roboto-medium.ttf',
      '$fonts/roboto-bold.ttf',
    ]);
    await _loadFont('MaterialIcons', ['$fonts/materialicons-regular.otf']);
    await _loadFont('Syne', ['assets/fonts/Syne-Variable.ttf']);
  });

  const phones = {'pixel9a': Size(411, 923), 'small': Size(360, 740)};

  for (final phone in phones.entries) {
    Future<AgentActivity> render(
      WidgetTester tester,
      String name,
      Widget Function(AgentActivity) build, {
      int tab = 0,
      Future<void> Function(WidgetTester, AgentActivity)? act,
    }) async {
      const dpr = 2.625;
      tester.view.physicalSize = phone.value * dpr;
      tester.view.devicePixelRatio = dpr;
      tester.view.padding = const FakeViewPadding(top: 24 * dpr, bottom: 20 * dpr);
      addTearDown(tester.view.reset);
      final activity = AgentActivity();
      await tester.pumpWidget(_shell(build(activity), tab));
      await tester.pump(const Duration(milliseconds: 900));
      if (act != null) await act(tester, activity);
      expect(tester.takeException(), isNull);
      // Previews are for eyeballing, not regression checks: only written on request.
      if (autoUpdateGoldenFiles) {
        await expectLater(
          find.byType(MaterialApp),
          matchesGoldenFile('goldens/${phone.key}_$name.png'),
        );
      }
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(seconds: 2));
      activity.dispose();
      return activity;
    }

    group(phone.key, () {
      testWidgets('standby', (t) => render(t, 'standby', (a) => _home(a)));

      testWidgets('offline', (t) => render(
        t,
        'offline',
        (a) => _home(a, agentEnabled: false),
      ));

      testWidgets('listening with tool tag', (t) => render(
        t,
        'listening',
        (a) => _home(a, state: ChatState.conversing, transcript: _talk()),
        act: (t, a) async {
          a.onTool('get_location');
          for (var i = 0; i < 6; i++) {
            a.onLevels(0.75, 0);
            await t.pump(const Duration(milliseconds: 70));
          }
        },
      ));

      testWidgets('muted', (t) => render(
        t,
        'muted',
        (a) => _home(
          a,
          state: ChatState.conversing,
          transcript: _talk(),
          muted: true,
        ),
      ));

      testWidgets('thinking', (t) => render(
        t,
        'thinking',
        (a) => _home(a, state: ChatState.waiting, transcript: _talk()),
        act: (t, a) async {
          a.onTool('google_search');
          await t.pump(const Duration(milliseconds: 250));
          a.onTool('send_sms');
          await t.pump(const Duration(milliseconds: 700));
        },
      ));

      testWidgets('speaking', (t) => render(
        t,
        'speaking',
        (a) => _home(a, state: ChatState.conversing, transcript: _talk()),
        act: (t, a) async {
          for (var i = 0; i < 8; i++) {
            a.onLevels(0.1, 0.8);
            await t.pump(const Duration(milliseconds: 70));
          }
        },
      ));

      testWidgets('agent call', (t) => render(
        t,
        'call',
        (a) => _home(
          a,
          callStatus: 'Agent handling: Mum (trusted)',
          transcript: [
            TranscriptLine('user', 'Hi, is Alex around?'),
            TranscriptLine('agent', "He's busy right now, can I take a message?"),
          ],
        ),
      ));

      testWidgets('taken over call', (t) => render(
        t,
        'takenover',
        (a) => _home(a, callStatus: 'On call: +1 555 0100', takenOver: true),
      ));

      testWidgets('chat', (t) => render(
        t,
        'chat',
        (a) => ChatPage(
          messages: [
            ChatMessage('user', 'What is on my calendar tomorrow?'),
            ChatMessage('agent', 'You have a dentist appointment at 10:00 and dinner with Sam at 19:30.'),
            ChatMessage('user', 'Remind me an hour before dinner.'),
          ],
          active: true,
          waiting: true,
          activeTool: 'schedule_task',
          onSend: (_, {imageBase64, imageMimeType, imagePath}) {},
          onStartSession: () {},
          onEndSession: () {},
        ),
        tab: 1,
      ));
    });
  }
}
