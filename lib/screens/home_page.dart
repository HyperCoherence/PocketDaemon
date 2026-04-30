import 'dart:convert';
import 'dart:io';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:image_picker/image_picker.dart';
import '../models.dart';
import '../theme/tokens.dart';
import '../widgets/breathing_dot.dart';
import '../widgets/agent_orb.dart';
import '../widgets/glass_card.dart';
import '../widgets/agent_presence_chip.dart';
import '../widgets/agent_edge_glow.dart';

class HomePage extends StatefulWidget {
  final bool agentEnabled;
  final bool configured;
  final bool allPermsGranted;
  final String callStatus;
  final bool takenOver;
  final ChatState chatState;
  final ChatMode chatMode;
  final List<TranscriptLine> transcript;
  final String agentName;
  final ValueChanged<bool> onToggleAgent;
  final VoidCallback onStartChat;
  final VoidCallback onStopChat;
  final VoidCallback onCancelChat;
  final VoidCallback onEndConversation;
  final VoidCallback onTakeOver;
  final VoidCallback onHangUp;
  final void Function(String imageBase64, String mimeType, String? caption)
  onSendImage;

  const HomePage({
    super.key,
    required this.agentEnabled,
    required this.configured,
    required this.allPermsGranted,
    required this.callStatus,
    required this.takenOver,
    required this.chatState,
    required this.chatMode,
    required this.transcript,
    this.agentName = '',
    required this.onToggleAgent,
    required this.onStartChat,
    required this.onStopChat,
    required this.onCancelChat,
    required this.onEndConversation,
    required this.onTakeOver,
    required this.onHangUp,
    required this.onSendImage,
  });

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with TickerProviderStateMixin {
  static const _logoAsset = 'assets/images/logo_pocketdaemon.svg';

  late final AnimationController _pulseCtrl;
  late final AnimationController _orbCtrl;
  final _picker = ImagePicker();

  @override
  void initState() {
    super.initState();
    _pulseCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1500),
    );
    _orbCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 3000),
    )..repeat();
  }

  @override
  void didUpdateWidget(covariant HomePage old) {
    super.didUpdateWidget(old);
    final shouldPulse =
        widget.chatState == ChatState.recording ||
        widget.chatState == ChatState.conversing;
    if (shouldPulse) {
      if (!_pulseCtrl.isAnimating) _pulseCtrl.repeat();
    } else {
      _pulseCtrl.stop();
      _pulseCtrl.reset();
    }
  }

  @override
  void dispose() {
    _pulseCtrl.dispose();
    _orbCtrl.dispose();
    super.dispose();
  }

  void _showImagePicker() {
    showModalBottomSheet(
      context: context,
      backgroundColor: PremiumTokens.surfaceSolid,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(
          top: Radius.circular(PremiumTokens.radiusXl),
        ),
      ),
      builder: (_) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              ListTile(
                leading: const Icon(
                  Icons.camera_alt_rounded,
                  color: PremiumTokens.accentPrimary,
                ),
                title: const Text('Camera'),
                onTap: () {
                  Navigator.pop(context);
                  _pickAndSend(ImageSource.camera);
                },
              ),
              ListTile(
                leading: const Icon(
                  Icons.photo_library_rounded,
                  color: PremiumTokens.accentPrimary,
                ),
                title: const Text('Photo Library'),
                onTap: () {
                  Navigator.pop(context);
                  _pickAndSend(ImageSource.gallery);
                },
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _pickAndSend(ImageSource source) async {
    final xfile = await _picker.pickImage(
      source: source,
      maxWidth: 1024,
      maxHeight: 1024,
      imageQuality: 85,
    );
    if (xfile == null) return;
    final bytes = await File(xfile.path).readAsBytes();
    final b64 = base64Encode(bytes);
    final mime = xfile.path.toLowerCase().endsWith('.png')
        ? 'image/png'
        : 'image/jpeg';
    if (!mounted) return;
    _showCaptionDialog(b64, mime);
  }

  void _showCaptionDialog(String b64, String mime) {
    final captionCtrl = TextEditingController();
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text(
          'Send image',
          style: TextStyle(fontFamily: 'Syne', fontSize: 16),
        ),
        content: TextField(
          controller: captionCtrl,
          autofocus: true,
          decoration: const InputDecoration(
            hintText: 'Add a caption (optional)',
          ),
          onSubmitted: (_) {
            Navigator.pop(ctx);
            widget.onSendImage(
              b64,
              mime,
              captionCtrl.text.trim().isEmpty ? null : captionCtrl.text.trim(),
            );
          },
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text(
              'Cancel',
              style: TextStyle(color: PremiumTokens.textMuted),
            ),
          ),
          TextButton(
            onPressed: () {
              Navigator.pop(ctx);
              widget.onSendImage(
                b64,
                mime,
                captionCtrl.text.trim().isEmpty
                    ? null
                    : captionCtrl.text.trim(),
              );
            },
            child: const Text(
              'Send',
              style: TextStyle(color: PremiumTokens.accentPrimary),
            ),
          ),
        ],
      ),
    );
  }

  bool get _hasActiveSession =>
      widget.callStatus.isNotEmpty || widget.chatState != ChatState.idle;

  double get _orbIntensity {
    switch (widget.chatState) {
      case ChatState.recording:
        return 1.0;
      case ChatState.conversing:
        return 0.8;
      case ChatState.waiting:
        return 0.4;
      case ChatState.connecting:
        return 0.3;
      case ChatState.idle:
        return 0.0;
    }
  }

  Color get _orbColor {
    switch (widget.chatState) {
      case ChatState.recording:
        return PremiumTokens.stateListening;
      case ChatState.conversing:
        return PremiumTokens.stateSpeaking;
      case ChatState.waiting:
        return PremiumTokens.stateThinking;
      case ChatState.connecting:
        return PremiumTokens.stateListening;
      case ChatState.idle:
        return PremiumTokens.stateIdle;
    }
  }

  bool get _showChip =>
      widget.chatState != ChatState.idle || widget.callStatus.isNotEmpty;

  @override
  Widget build(BuildContext context) {
    final isCall = widget.callStatus.isNotEmpty && !widget.takenOver;

    return Stack(
      children: [
        Positioned.fill(
          child: AgentEdgeGlow(
            state: widget.chatState,
            active: widget.agentEnabled || _hasActiveSession,
            isCall: isCall,
          ),
        ),
        _buildLogoBackdrop(context, isCall),

        Column(
          children: [
            Expanded(
              child: CustomScrollView(
                slivers: [
                  SliverPadding(
                    padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
                    sliver: SliverList(
                      delegate: SliverChildListDelegate([
                        _buildHeader(context),
                        const SizedBox(height: 20),
                        _buildAgentToggle(context),
                        const SizedBox(height: 20),
                        if (_hasActiveSession &&
                            widget.transcript.isNotEmpty) ...[
                          _buildTranscriptOverlay(context),
                          const SizedBox(height: 16),
                        ],
                      ]),
                    ),
                  ),
                  const SliverPadding(padding: EdgeInsets.only(bottom: 16)),
                ],
              ),
            ),
            if (widget.callStatus.isNotEmpty)
              _buildCallControls(context)
            else ...[
              if (widget.chatState != ChatState.idle)
                Padding(
                  padding: const EdgeInsets.only(right: 24, bottom: 4),
                  child: Align(
                    alignment: Alignment.centerRight,
                    child: IconButton(
                      icon: const Icon(
                        Icons.camera_alt_rounded,
                        color: PremiumTokens.textMuted,
                        size: 22,
                      ),
                      onPressed: _showImagePicker,
                      padding: EdgeInsets.zero,
                      constraints: const BoxConstraints(),
                    ),
                  ),
                ),
              widget.chatMode == ChatMode.conversation
                  ? _buildConversationButton(context)
                  : _buildPushToTalk(context),
            ],
            const SizedBox(height: 12),
          ],
        ),

        Positioned(
          bottom: 185,
          left: 0,
          right: 0,
          child: Center(
            child: AnimatedSlide(
              duration: PremiumTokens.durationSlow,
              curve: PremiumTokens.easeSpring,
              offset: _showChip ? Offset.zero : const Offset(0, 0.5),
              child: AnimatedOpacity(
                duration: PremiumTokens.durationSlow,
                opacity: _showChip ? 1.0 : 0.0,
                child: AgentPresenceChip(
                  state: widget.chatState,
                  isCall: isCall,
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildLogoBackdrop(BuildContext context, bool isCall) {
    final width = MediaQuery.sizeOf(context).width;
    final markSize = width.clamp(230.0, 340.0).toDouble();
    final bool active = widget.agentEnabled || _hasActiveSession;
    final Color tint = isCall
        ? PremiumTokens.warning
        : active
        ? PremiumTokens.accentPrimaryHover
        : PremiumTokens.textSecondary;

    return Positioned(
      top: 28,
      left: 0,
      right: 0,
      child: IgnorePointer(
        child: AnimatedOpacity(
          duration: PremiumTokens.durationSlow,
          curve: PremiumTokens.easeOut,
          opacity: active ? 0.13 : 0.08,
          child: Center(
            child: SvgPicture.asset(
              _logoAsset,
              width: markSize,
              height: markSize,
              fit: BoxFit.contain,
              colorFilter: ColorFilter.mode(tint, BlendMode.srcIn),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildHeader(BuildContext context) {
    final bool hasCall = widget.callStatus.isNotEmpty;
    final Color dotColor = hasCall
        ? PremiumTokens.warning
        : widget.agentEnabled
        ? PremiumTokens.accentPrimary
        : PremiumTokens.textMuted;
    final bool shouldPulse = hasCall || widget.agentEnabled;

    return Row(
      children: [
        Semantics(
          label: 'PocketDaemon logo',
          image: true,
          child: Container(
            width: 44,
            height: 44,
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: PremiumTokens.surfaceGlass,
              border: Border.all(color: PremiumTokens.borderGlass, width: 0.5),
            ),
            child: SvgPicture.asset(
              _logoAsset,
              fit: BoxFit.contain,
              colorFilter: const ColorFilter.mode(
                PremiumTokens.textPrimary,
                BlendMode.srcIn,
              ),
            ),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Text(
                    widget.agentName.isNotEmpty
                        ? widget.agentName
                        : 'PocketDaemon',
                    style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                      fontFamily: 'Syne',
                      fontWeight: FontWeight.w700,
                      letterSpacing: 0,
                      color: PremiumTokens.textPrimary,
                    ),
                  ),
                  const SizedBox(width: 10),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(PremiumTokens.radiusSm),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 8, sigmaY: 8),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 2,
                        ),
                        decoration: BoxDecoration(
                          color: PremiumTokens.surfaceGlass,
                          borderRadius: BorderRadius.circular(
                            PremiumTokens.radiusSm,
                          ),
                          border: Border.all(
                            color: PremiumTokens.borderGlass,
                            width: 0.5,
                          ),
                        ),
                        child: Text(
                          'v$kAppVersion',
                          style: const TextStyle(
                            fontSize: 10,
                            color: PremiumTokens.textMuted,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              Row(
                children: [
                  BreathingDot(color: dotColor, animate: shouldPulse),
                  const SizedBox(width: 8),
                  Flexible(
                    child: Text(
                      widget.callStatus.isNotEmpty
                          ? widget.callStatus
                          : widget.agentEnabled
                          ? 'Agent active - waiting for calls'
                          : 'Agent disabled',
                      style: TextStyle(color: dotColor, fontSize: 13),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildAgentToggle(BuildContext context) {
    final active = widget.agentEnabled;
    return GlassCard(
      glowColor: active ? PremiumTokens.accentPrimary : null,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Row(
        children: [
          AnimatedContainer(
            duration: PremiumTokens.durationNormal,
            curve: PremiumTokens.easeSpring,
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color:
                  (active
                          ? PremiumTokens.accentPrimary
                          : PremiumTokens.textMuted)
                      .withAlpha(20),
            ),
            child: Icon(
              active ? Icons.phone_in_talk : Icons.phone_disabled,
              color: active
                  ? PremiumTokens.accentPrimary
                  : PremiumTokens.textMuted,
              size: 20,
            ),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'PocketDaemon',
                  style: TextStyle(
                    fontFamily: 'Syne',
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    color: PremiumTokens.textPrimary,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  active ? 'Auto-answering calls' : 'Tap to enable',
                  style: const TextStyle(
                    fontSize: 12,
                    color: PremiumTokens.textTertiary,
                  ),
                ),
              ],
            ),
          ),
          Switch(
            value: active,
            onChanged: widget.allPermsGranted && widget.configured
                ? widget.onToggleAgent
                : null,
          ),
        ],
      ),
    );
  }

  Widget _buildTranscriptOverlay(BuildContext context) {
    final lines = widget.transcript;
    final displayLines = lines.length > 6
        ? lines.sublist(lines.length - 6)
        : lines;

    return GlassCard(
      padding: EdgeInsets.zero,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxHeight: 180),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 10, 14, 6),
              child: Row(
                children: [
                  Container(
                    width: 6,
                    height: 6,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: PremiumTokens.accentPrimary,
                      boxShadow: [
                        BoxShadow(
                          color: PremiumTokens.accentPrimary.withAlpha(80),
                          blurRadius: 4,
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 8),
                  const Text(
                    'Live Transcript',
                    style: TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w600,
                      color: PremiumTokens.textTertiary,
                      letterSpacing: 0.5,
                    ),
                  ),
                ],
              ),
            ),
            Flexible(
              child: ListView.builder(
                padding: const EdgeInsets.fromLTRB(14, 0, 14, 10),
                shrinkWrap: true,
                reverse: false,
                itemCount: displayLines.length,
                itemBuilder: (_, i) {
                  final line = displayLines[i];
                  final isAgent =
                      line.speaker == 'model' || line.speaker == 'agent';
                  return Padding(
                    padding: const EdgeInsets.symmetric(vertical: 3),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        SizedBox(
                          width: 42,
                          child: Text(
                            isAgent ? 'Agent' : 'Caller',
                            style: TextStyle(
                              fontSize: 10,
                              fontWeight: FontWeight.w600,
                              color: isAgent
                                  ? PremiumTokens.accentSecondary.withAlpha(200)
                                  : PremiumTokens.accentPrimary.withAlpha(200),
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            line.text,
                            style: const TextStyle(
                              fontSize: 12,
                              color: PremiumTokens.textSecondary,
                              height: 1.3,
                            ),
                          ),
                        ),
                      ],
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildConversationButton(BuildContext context) {
    final bool isIdle = widget.chatState == ChatState.idle;
    final bool isConversing = widget.chatState == ChatState.conversing;
    final bool isConnecting = widget.chatState == ChatState.connecting;

    final Color btnColor = _orbColor;
    IconData btnIcon;

    if (isConversing) {
      btnIcon = Icons.stop_rounded;
    } else if (isConnecting) {
      btnIcon = Icons.sync_rounded;
    } else {
      btnIcon = Icons.headset_mic_rounded;
    }

    const double btnSize = 100;

    return Column(
      children: [
        SizedBox(
          width: btnSize + 40,
          height: btnSize + 40,
          child: Stack(
            alignment: Alignment.center,
            children: [
              AnimatedBuilder(
                animation: isConversing ? _pulseCtrl : _orbCtrl,
                builder: (context, child) {
                  return CustomPaint(
                    size: const Size(btnSize + 40, btnSize + 40),
                    painter: AgentOrbPainter(
                      progress: isConversing
                          ? _pulseCtrl.value
                          : _orbCtrl.value,
                      intensity: _orbIntensity,
                      color: btnColor,
                    ),
                  );
                },
              ),
              GestureDetector(
                onTap: () {
                  if (isIdle) {
                    widget.onStartChat();
                  } else if (isConversing) {
                    widget.onEndConversation();
                  }
                },
                child: AnimatedContainer(
                  duration: PremiumTokens.durationNormal,
                  curve: PremiumTokens.easeSpring,
                  width: btnSize,
                  height: btnSize,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: btnColor.withAlpha(isConversing ? 40 : 15),
                    border: Border.all(
                      color: btnColor.withAlpha(isConversing ? 180 : 60),
                      width: isConversing ? 2 : 1,
                    ),
                    boxShadow: [
                      BoxShadow(
                        color: btnColor.withAlpha(isConversing ? 50 : 0),
                        blurRadius: isConversing ? 24 : 0,
                        spreadRadius: isConversing ? 3 : 0,
                      ),
                    ],
                  ),
                  child: Icon(btnIcon, color: btnColor, size: 40),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildCallControls(BuildContext context) {
    if (widget.takenOver) {
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20),
        child: Column(
          children: [
            GlassCard(
              glowColor: PremiumTokens.accentPrimary,
              padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 16),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Container(
                    width: 10,
                    height: 10,
                    decoration: const BoxDecoration(
                      shape: BoxShape.circle,
                      color: PremiumTokens.accentPrimary,
                    ),
                  ),
                  const SizedBox(width: 10),
                  const Text(
                    "You're on the call",
                    style: TextStyle(
                      color: PremiumTokens.accentPrimary,
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 10),
            SizedBox(
              width: double.infinity,
              height: 50,
              child: FilledButton.icon(
                onPressed: widget.onHangUp,
                icon: const Icon(Icons.call_end_rounded),
                label: const Text(
                  'Hang Up',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
                ),
                style: FilledButton.styleFrom(
                  backgroundColor: PremiumTokens.error,
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
                  ),
                ),
              ),
            ),
          ],
        ),
      );
    }

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20),
      child: Column(
        children: [
          if (widget.transcript.isNotEmpty) ...[
            _buildCallScreeningTranscript(context),
            const SizedBox(height: 10),
          ],
          GlassCard(
            padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 16),
            child: Row(
              children: [
                BreathingDot(color: PremiumTokens.warning, animate: true),
                const SizedBox(width: 10),
                const Expanded(
                  child: Text(
                    'Agent handling call',
                    style: TextStyle(
                      color: PremiumTokens.warning,
                      fontSize: 13,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ),
                const Text(
                  'On speaker',
                  style: TextStyle(
                    fontSize: 11,
                    color: PremiumTokens.textMuted,
                  ),
                ),
                const SizedBox(width: 4),
                const Icon(
                  Icons.volume_up_rounded,
                  size: 16,
                  color: PremiumTokens.textMuted,
                ),
              ],
            ),
          ),
          const SizedBox(height: 10),
          SizedBox(
            width: double.infinity,
            height: 50,
            child: FilledButton.icon(
              onPressed: widget.onTakeOver,
              icon: const Icon(Icons.phone_forwarded_rounded),
              label: const Text(
                'Take Over Call',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
              ),
              style: FilledButton.styleFrom(
                backgroundColor: PremiumTokens.warning,
                foregroundColor: Colors.white,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildCallScreeningTranscript(BuildContext context) {
    final lines = widget.transcript;
    final displayLines = lines.length > 4
        ? lines.sublist(lines.length - 4)
        : lines;

    return GlassCard(
      padding: EdgeInsets.zero,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxHeight: 140),
        child: ListView.builder(
          padding: const EdgeInsets.all(12),
          shrinkWrap: true,
          itemCount: displayLines.length,
          itemBuilder: (_, i) {
            final line = displayLines[i];
            final isAgent = line.speaker == 'model' || line.speaker == 'agent';
            return Padding(
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: 42,
                    child: Text(
                      isAgent ? 'Agent' : 'Caller',
                      style: TextStyle(
                        fontSize: 10,
                        fontWeight: FontWeight.w600,
                        color: isAgent
                            ? PremiumTokens.accentSecondary.withAlpha(200)
                            : PremiumTokens.warning.withAlpha(220),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      line.text,
                      style: const TextStyle(
                        fontSize: 12,
                        color: PremiumTokens.textSecondary,
                        height: 1.3,
                      ),
                    ),
                  ),
                ],
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _buildPushToTalk(BuildContext context) {
    final bool isIdle = widget.chatState == ChatState.idle;
    final bool isRecording = widget.chatState == ChatState.recording;
    final bool isWaiting = widget.chatState == ChatState.waiting;
    final bool isConnecting = widget.chatState == ChatState.connecting;

    Color btnColor;
    IconData btnIcon;

    if (isRecording) {
      btnColor = PremiumTokens.error;
      btnIcon = Icons.stop_rounded;
    } else if (isWaiting) {
      btnColor = PremiumTokens.stateThinking;
      btnIcon = Icons.hourglass_top_rounded;
    } else if (isConnecting) {
      btnColor = PremiumTokens.stateListening;
      btnIcon = Icons.sync_rounded;
    } else {
      btnColor = PremiumTokens.stateIdle;
      btnIcon = Icons.mic_rounded;
    }

    const double idleSize = 100;
    const double activeSize = 120;
    final double size = isRecording ? activeSize : idleSize;

    return Column(
      children: [
        SizedBox(
          width: activeSize + 40,
          height: activeSize + 40,
          child: Stack(
            alignment: Alignment.center,
            children: [
              AnimatedBuilder(
                animation: _orbCtrl,
                builder: (context, child) {
                  return CustomPaint(
                    size: const Size(activeSize + 40, activeSize + 40),
                    painter: AgentOrbPainter(
                      progress: _orbCtrl.value,
                      intensity: _orbIntensity,
                      color: btnColor,
                    ),
                  );
                },
              ),
              GestureDetector(
                onTapDown: isIdle ? (_) => widget.onStartChat() : null,
                onTapUp: isRecording ? (_) => widget.onStopChat() : null,
                onTapCancel: isRecording ? widget.onStopChat : null,
                onLongPressStart: isIdle ? (_) => widget.onStartChat() : null,
                onLongPressEnd: isRecording ? (_) => widget.onStopChat() : null,
                child: AnimatedContainer(
                  duration: PremiumTokens.durationNormal,
                  curve: PremiumTokens.easeSpring,
                  width: size,
                  height: size,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: btnColor.withAlpha(isRecording ? 50 : 15),
                    border: Border.all(
                      color: btnColor.withAlpha(isRecording ? 200 : 60),
                      width: isRecording ? 2 : 1,
                    ),
                    boxShadow: [
                      BoxShadow(
                        color: btnColor.withAlpha(isRecording ? 60 : 0),
                        blurRadius: isRecording ? 30 : 0,
                        spreadRadius: isRecording ? 4 : 0,
                      ),
                    ],
                  ),
                  child: Icon(btnIcon, color: btnColor, size: 40),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
