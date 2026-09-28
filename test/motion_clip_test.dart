// Plays a scripted voice conversation through the real widgets and saves every
// frame, to review motion design without a phone. Skipped unless MOTION_CLIP
// is set; frames land in build/motion/ for ffmpeg:
//   MOTION_CLIP=1 flutter test test/motion_clip_test.dart
//   ffmpeg -framerate 30 -i build/motion/f%04d.png -pix_fmt yuv420p voice.mp4
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pocket_daemon/agent_activity.dart';
import 'package:pocket_daemon/models.dart';
import 'package:pocket_daemon/screens/home_page.dart';
import 'package:pocket_daemon/theme/premium_theme.dart';
import 'package:pocket_daemon/theme/tokens.dart';
import 'package:pocket_daemon/widgets/glass_nav_bar.dart';
import 'package:pocket_daemon/widgets/voice_orb.dart';

Future<void> _loadFont(String family, List<String> paths) async {
  final loader = FontLoader(family);
  for (final p in paths) {
    final bytes = File(p).readAsBytesSync();
    loader.addFont(Future.value(ByteData.view(bytes.buffer)));
  }
  await loader.load();
}

class _Story extends StatefulWidget {
  const _Story({super.key, required this.activity});
  final AgentActivity activity;
  @override
  State<_Story> createState() => _StoryState();
}

class _StoryState extends State<_Story> {
  ChatState chat = ChatState.idle;
  int tab = 0;
  bool muted = false;
  final transcript = <TranscriptLine>[];

  void set(ChatState s) => setState(() => chat = s);
  void showTab(int i) => setState(() => tab = i);
  void say(String who, String text) =>
      setState(() => transcript.add(TranscriptLine(who, text)));

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: premiumTheme(),
      home: DecoratedBox(
        decoration: const BoxDecoration(gradient: PremiumTokens.shellGradient),
        child: Scaffold(
          body: SafeArea(
            child: HomePage(
              agentEnabled: true,
              configured: true,
              allPermsGranted: true,
              callStatus: '',
              takenOver: false,
              chatState: chat,
              chatMode: ChatMode.conversation,
              transcript: transcript,
              activity: widget.activity,
              muted: muted,
              onToggleMute: () => setState(() => muted = !muted),
              onToggleAgent: (_) {},
              onStartChat: () {},
              onStopChat: () {},
              onCancelChat: () {},
              onEndConversation: () {},
              onTakeOver: () {},
              onHangUp: () {},
              onHandToAgent: () {},
              onSendImage: (_, _, _, _) {},
              control: const MethodChannel('clip'),
            ),
          ),
          bottomNavigationBar: GlassNavBar(
            selectedIndex: tab,
            onTap: (i) => setState(() => tab = i),
          ),
        ),
      ),
    );
  }
}

void main() {
  testWidgets('voice motion clip', (tester) async {
    final root = Platform.environment['FLUTTER_ROOT']!;
    final fonts = '$root/bin/cache/artifacts/material_fonts';
    await _loadFont('Roboto', [
      '$fonts/roboto-regular.ttf',
      '$fonts/roboto-medium.ttf',
      '$fonts/roboto-bold.ttf',
    ]);
    await _loadFont('MaterialIcons', ['$fonts/materialicons-regular.otf']);
    await _loadFont('Syne', ['assets/fonts/Syne-Variable.ttf']);

    const dpr = 2.0;
    tester.view.physicalSize = const Size(411, 923) * dpr;
    tester.view.devicePixelRatio = dpr;
    tester.view.padding = const FakeViewPadding(
      top: 24 * dpr,
      bottom: 20 * dpr,
    );
    addTearDown(tester.view.reset);

    final out = Directory('build/motion');
    if (out.existsSync()) out.deleteSync(recursive: true);
    out.createSync(recursive: true);

    final activity = AgentActivity();
    final key = GlobalKey<_StoryState>();
    final boundary = GlobalKey();
    await tester.pumpWidget(
      RepaintBoundary(
        key: boundary,
        child: _Story(key: key, activity: activity),
      ),
    );

    var frame = 0;
    final rng = math.Random(3);

    // Advance [seconds] at 30 fps, capturing each frame; [each] runs first.
    Future<void> play(double seconds, [void Function(double t)? each]) async {
      final frames = (seconds * 30).round();
      for (var i = 0; i < frames; i++) {
        each?.call(i / 30);
        await tester.pump(const Duration(microseconds: 33333));
        final ro =
            boundary.currentContext!.findRenderObject()!
                as RenderRepaintBoundary;
        await tester.runAsync(() async {
          final image = await ro.toImage(pixelRatio: 1);
          final png = await image.toByteData(format: ui.ImageByteFormat.png);
          File(
            '${out.path}/f${(frame++).toString().padLeft(4, '0')}.png',
          ).writeAsBytesSync(png!.buffer.asUint8List());
          image.dispose();
        });
      }
    }

    double voice(double t) =>
        (0.45 + 0.4 * math.sin(t * 9) * math.sin(t * 2.3)).abs() +
        rng.nextDouble() * 0.15;

    final story = key.currentState!;
    await play(1.6); // entrance
    await play(0.8);

    // Tap the orb.
    final orb = tester.getCenter(find.byType(VoiceOrb));
    final press = await tester.startGesture(orb);
    await play(0.25);
    await press.up();
    story.set(ChatState.connecting);
    await play(1.4);

    story.set(ChatState.conversing);
    await play(0.6);
    story.say('user', 'Where am I right now?');
    await play(1.8, (t) => activity.onLevels(voice(t), 0));
    story.say('user', 'Is a pharmacy open nearby?');
    await play(0.6, (t) => activity.onLevels(voice(t), 0));

    story.set(ChatState.waiting);
    activity.onTool('get_location');
    story.say('tool', 'get_location');
    await play(0.7);
    activity.onTool('google_search');
    story.say('tool', 'google_search');
    await play(1.6);

    story.set(ChatState.conversing);
    story.say(
      'agent',
      "You're on Main Street. The closest open pharmacy is four minutes north.",
    );
    await play(2.6, (t) => activity.onLevels(0.05, voice(t + 3)));
    await play(1.3); // speaking holds, then back to listening

    // Mute and unmute with the centre button.
    final mute = await tester.startGesture(tester.getCenter(find.text('Mute')));
    await play(0.15);
    await mute.up();
    await play(1.8);
    final unmute = await tester.startGesture(
      tester.getCenter(find.text('Unmute')),
    );
    await play(0.15);
    await unmute.up();
    await play(1.2, (t) => activity.onLevels(voice(t), 0));

    // Tap the orb to end.
    final end = tester.getCenter(find.byType(VoiceOrb));
    final endPress = await tester.startGesture(end);
    await play(0.2);
    await endPress.up();
    story.set(ChatState.idle);
    story.transcript.clear();
    await play(1.4);

    // Glide the nav highlight across.
    story.showTab(3);
    await play(0.9);
    story.showTab(0);
    await play(0.9);

    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 2));
    activity.dispose();
  }, skip: !Platform.environment.containsKey('MOTION_CLIP'));
}
