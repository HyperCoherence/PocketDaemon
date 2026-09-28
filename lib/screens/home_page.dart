import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';
import '../agent_activity.dart';
import '../models.dart';
import '../theme/tokens.dart';
import '../widgets/agent_edge_glow.dart';
import '../widgets/ambient_field.dart';
import '../widgets/breathing_dot.dart';
import '../widgets/glass_card.dart';
import '../widgets/kinetic_text.dart';
import '../widgets/pressable.dart';
import '../widgets/tool_tag_layer.dart';
import '../widgets/voice_orb.dart';
import 'camera_viewfinder_page.dart';

class HomePage extends StatefulWidget {
  final bool agentEnabled;
  final bool configured;
  final bool allPermsGranted;
  final String callStatus;
  final bool takenOver;
  final ChatState chatState;
  final ChatMode chatMode;
  final List<TranscriptLine> transcript;
  final AgentActivity activity;

  /// The owner's mic is muted in the running conversation.
  final bool muted;
  final VoidCallback onToggleMute;
  final String agentName;
  final ValueChanged<bool> onToggleAgent;
  final VoidCallback onStartChat;
  final VoidCallback onStopChat;
  final VoidCallback onCancelChat;
  final VoidCallback onEndConversation;
  final VoidCallback onTakeOver;
  final VoidCallback onHangUp;
  final VoidCallback onHandToAgent;
  final VoiceImageSender onSendImage;
  final MethodChannel control;

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
    required this.activity,
    this.muted = false,
    required this.onToggleMute,
    this.agentName = '',
    required this.onToggleAgent,
    required this.onStartChat,
    required this.onStopChat,
    required this.onCancelChat,
    required this.onEndConversation,
    required this.onTakeOver,
    required this.onHangUp,
    required this.onHandToAgent,
    required this.onSendImage,
    required this.control,
  });

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage>
    with SingleTickerProviderStateMixin {
  static const _logoAsset = 'assets/images/logo_pocketdaemon.svg';

  bool _orbPressed = false;

  /// Height kept free under the orb for the live transcript card.
  static const _transcriptMaxHeight = 132.0;
  static const _transcriptReserve = _transcriptMaxHeight + 20 + 12;

  /// Plays the screen's entrance: header drops in, orb blooms, controls rise.
  late final AnimationController _enter = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1100),
  )..forward();

  @override
  void dispose() {
    _enter.dispose();
    super.dispose();
  }

  /// One beat of the entrance, between [start] and [end] of the timeline.
  Widget _entrance(
    double start,
    double end,
    Widget child, {
    Offset from = const Offset(0, 24),
    double scaleFrom = 1,
  }) {
    final move = CurvedAnimation(
      parent: _enter,
      curve: Interval(start, end, curve: Curves.easeOutBack),
    );
    final fade = CurvedAnimation(
      parent: _enter,
      curve: Interval(start, end, curve: Curves.easeOut),
    );
    return AnimatedBuilder(
      animation: _enter,
      child: child,
      builder: (context, child) => Opacity(
        opacity: fade.value,
        child: Transform.translate(
          offset: from * (1 - move.value),
          child: Transform.scale(
            scale: scaleFrom + (1 - scaleFrom) * move.value,
            child: child,
          ),
        ),
      ),
    );
  }

  String get _name =>
      widget.agentName.isNotEmpty ? widget.agentName : 'PocketDaemon';

  bool get _inCall => widget.callStatus.isNotEmpty;

  bool get _hasSession => _inCall || widget.chatState != ChatState.idle;

  bool get _conversation => widget.chatMode == ChatMode.conversation;

  /// The camera needs a live session to send into. Conversation mode can start
  /// one on demand; push-to-talk only has a session while a turn is under way.
  bool get _cameraAvailable =>
      !_inCall && (widget.chatState != ChatState.idle || _conversation);

  AgentPhase _phase(bool speaking) {
    if (_inCall) return AgentPhase.call;
    if (widget.chatState == ChatState.idle && !widget.configured) {
      return AgentPhase.offline;
    }
    return phaseFor(widget.chatState, speaking: speaking, muted: widget.muted);
  }

  Future<void> _openCamera() async {
    if (!_cameraAvailable) return;
    HapticFeedback.lightImpact();
    if (widget.chatState == ChatState.idle) {
      // Connect now so the photo goes out the moment the session is ready.
      widget.onStartChat();
    }
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        fullscreenDialog: true,
        builder: (_) => CameraViewfinderPage(
          control: widget.control,
          agentName: widget.agentName,
          onSendImage: widget.onSendImage,
        ),
      ),
    );
  }

  void _endSession() {
    if (widget.chatState == ChatState.idle) return;
    if (_conversation) {
      widget.onEndConversation();
    } else {
      widget.onCancelChat();
    }
  }

  void _onOrbTap() {
    if (!_conversation || _inCall) return;
    switch (widget.chatState) {
      case ChatState.idle:
        if (!widget.configured) {
          _explainSetup();
        } else {
          widget.onStartChat();
        }
      case ChatState.connecting:
        HapticFeedback.lightImpact();
        widget.onCancelChat();
      case ChatState.conversing:
      case ChatState.waiting:
        widget.onEndConversation();
      case ChatState.recording:
        break;
    }
  }

  void _explainSetup() {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        const SnackBar(
          content: Text('Add an API key in Settings to talk to the agent.'),
        ),
      );
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<bool>(
      valueListenable: widget.activity.speaking,
      builder: (context, speaking, _) {
        final phase = _phase(speaking);
        return Stack(
          children: [
            Positioned.fill(
              child: AmbientField(
                color: phase.color,
                energy: phase.isLive ? 0.7 : 0.2,
              ),
            ),
            Positioned.fill(
              child: AgentEdgeGlow(
                color: phase.color,
                intensity: phase.isLive
                    ? 1.0
                    : widget.agentEnabled
                    ? 0.35
                    : 0.15,
              ),
            ),
            Column(
              children: [
                _entrance(
                  0,
                  0.45,
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
                    child: _buildHeader(context),
                  ),
                  from: const Offset(0, -20),
                ),
                // The orb is laid out against a fixed reserve for the
                // transcript, so it stays put while lines come and go.
                Expanded(
                  child: Stack(
                    children: [
                      Positioned(
                        top: 0,
                        left: 0,
                        right: 0,
                        bottom: _transcriptReserve,
                        child: _entrance(
                          0.1,
                          0.75,
                          _buildStage(context, phase),
                          from: Offset.zero,
                          scaleFrom: 0.7,
                        ),
                      ),
                      Positioned(
                        left: 0,
                        right: 0,
                        bottom: 0,
                        child: _buildTranscript(context),
                      ),
                    ],
                  ),
                ),
                _entrance(
                  0.35,
                  1,
                  _inCall
                      ? _buildCallControls(context)
                      : _buildVoiceControls(context),
                  from: const Offset(0, 40),
                ),
                const SizedBox(height: 14),
              ],
            ),
          ],
        );
      },
    );
  }

  // ── Header ──

  Widget _buildHeader(BuildContext context) {
    final Color dotColor = _inCall
        ? PremiumTokens.phaseCall
        : widget.agentEnabled
        ? PremiumTokens.phaseListening
        : PremiumTokens.textMuted;
    final status = _inCall
        ? widget.callStatus
        : widget.agentEnabled
        ? 'Screening calls'
        : 'Auto-answer off';
    final narrow = MediaQuery.sizeOf(context).width < 380;

    return Row(
      children: [
        Semantics(
          label: 'PocketDaemon logo',
          image: true,
          child: Container(
            width: 40,
            height: 40,
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [
                  PremiumTokens.accentPrimary.withValues(alpha: 0.22),
                  PremiumTokens.accentSecondary.withValues(alpha: 0.10),
                ],
              ),
              border: Border.all(color: PremiumTokens.borderGlass),
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
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.baseline,
                textBaseline: TextBaseline.alphabetic,
                children: [
                  Flexible(
                    child: Text(
                      _name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontFamily: 'Syne',
                        fontSize: narrow ? 18 : 21,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.2,
                        color: PremiumTokens.textPrimary,
                      ),
                    ),
                  ),
                  if (kAppVersion.isNotEmpty) ...[
                    const SizedBox(width: 8),
                    Text(
                      'v$kAppVersion',
                      style: const TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w500,
                        color: PremiumTokens.textMuted,
                      ),
                    ),
                  ],
                ],
              ),
              const SizedBox(height: 3),
              Row(
                children: [
                  BreathingDot(
                    color: dotColor,
                    animate: _inCall || widget.agentEnabled,
                  ),
                  const SizedBox(width: 7),
                  Flexible(
                    child: AnimatedSwitcher(
                      duration: PremiumTokens.durationNormal,
                      child: Text(
                        status,
                        key: ValueKey(status),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: dotColor == PremiumTokens.textMuted
                              ? PremiumTokens.textTertiary
                              : dotColor,
                          fontSize: 13,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
        const SizedBox(width: 10),
        _AutoAnswerPill(
          enabled: widget.agentEnabled,
          available: widget.allPermsGranted && widget.configured,
          onChanged: widget.onToggleAgent,
        ),
      ],
    );
  }

  // ── Stage: orb, tool tags, phase label ──

  Widget _buildStage(BuildContext context, AgentPhase phase) {
    return LayoutBuilder(
      builder: (context, box) {
        // Leave room under the orb's ring for the label and hint.
        const labelSpace = 64.0;
        final byHeight = (box.maxHeight / 2 - labelSpace) / 0.36;
        final size = [
          box.maxWidth * 0.92,
          byHeight,
          360.0,
        ].reduce((a, b) => a < b ? a : b).clamp(150.0, 360.0);
        final ringRadius = size * 0.33;

        return Stack(
          clipBehavior: Clip.none,
          alignment: Alignment.center,
          children: [
            Positioned.fill(
              child: ToolTagLayer(
                tools: widget.activity.tools,
                orbRadius: ringRadius,
              ),
            ),
            _buildOrb(phase, size),
            Positioned(
              left: 16,
              right: 16,
              top: box.maxHeight / 2 + ringRadius + 14,
              child: _PhaseLabel(
                phase: phase,
                title: _phaseTitle(phase),
                hint: _phaseHint(phase),
              ),
            ),
          ],
        );
      },
    );
  }

  String _phaseTitle(AgentPhase phase) {
    if (phase == AgentPhase.call) {
      return widget.takenOver ? "You're on the call" : 'Agent on call';
    }
    return phase.label;
  }

  String _phaseHint(AgentPhase phase) {
    switch (phase) {
      case AgentPhase.offline:
        return 'Add an API key in Settings';
      case AgentPhase.standby:
        return _conversation ? 'Tap to talk to $_name' : 'Hold to talk';
      case AgentPhase.connecting:
        return _conversation ? 'Tap to cancel' : 'Keep holding…';
      case AgentPhase.listening:
        return _conversation ? 'Go ahead · tap to end' : 'Release to send';
      case AgentPhase.muted:
        return 'Your mic is off · tap to end';
      case AgentPhase.thinking:
        return 'Working on it';
      case AgentPhase.speaking:
        return _conversation ? 'Talk over me to interrupt' : '';
      case AgentPhase.call:
        return widget.takenOver ? 'Your mic is live' : 'Your mic is muted';
    }
  }

  Widget _buildOrb(AgentPhase phase, double size) {
    final orb = Listener(
      onPointerDown: (_) => setState(() => _orbPressed = true),
      onPointerUp: (_) => setState(() => _orbPressed = false),
      onPointerCancel: (_) => setState(() => _orbPressed = false),
      child: Stack(
        alignment: Alignment.center,
        children: [
          VoiceOrb(
            phase: phase,
            size: size,
            activity: widget.activity,
            pressed: _orbPressed,
          ),
          AnimatedSwitcher(
            duration: PremiumTokens.durationSlow,
            transitionBuilder: (child, anim) => ScaleTransition(
              scale: anim,
              child: FadeTransition(opacity: anim, child: child),
            ),
            child: _orbIcon(phase, size),
          ),
        ],
      ),
    );

    final String label = switch (widget.chatState) {
      ChatState.idle => _conversation ? 'Start conversation' : 'Hold to talk',
      ChatState.connecting => 'Cancel connecting',
      _ => _conversation ? 'End conversation' : 'Release to send',
    };

    if (_inCall) return orb;
    if (_conversation) {
      return Semantics(
        button: true,
        label: label,
        child: GestureDetector(onTap: _onOrbTap, child: orb),
      );
    }

    final bool isIdle = widget.chatState == ChatState.idle;
    final bool isRecording = widget.chatState == ChatState.recording;
    final bool canStart = isIdle && widget.configured;
    return Semantics(
      button: true,
      label: label,
      child: GestureDetector(
        onTap: isIdle && !widget.configured ? _explainSetup : null,
        onTapDown: canStart ? (_) => widget.onStartChat() : null,
        onTapUp: isRecording ? (_) => widget.onStopChat() : null,
        onTapCancel: isRecording ? widget.onStopChat : null,
        onLongPressStart: canStart ? (_) => widget.onStartChat() : null,
        onLongPressEnd: isRecording ? (_) => widget.onStopChat() : null,
        child: orb,
      ),
    );
  }

  Widget _orbIcon(AgentPhase phase, double size) {
    final IconData? icon = switch (phase) {
      AgentPhase.offline => Icons.key_off_rounded,
      AgentPhase.standby =>
        _conversation ? Icons.graphic_eq_rounded : Icons.mic_rounded,
      AgentPhase.muted => Icons.mic_off_rounded,
      AgentPhase.call =>
        widget.takenOver ? Icons.mic_rounded : Icons.support_agent_rounded,
      _ => null,
    };
    if (icon == null) return const SizedBox.shrink(key: ValueKey('none'));
    return Icon(
      icon,
      key: ValueKey(icon),
      size: (size * 0.16).clamp(28.0, 52.0),
      color: Colors.white.withValues(alpha: 0.92),
      shadows: [
        Shadow(color: phase.color.withValues(alpha: 0.9), blurRadius: 18),
      ],
    );
  }

  // ── Live transcript ──

  Widget _buildTranscript(BuildContext context) {
    final lines = widget.transcript;
    final show = _hasSession && lines.isNotEmpty;
    final recent = show
        ? lines.sublist(lines.length > 4 ? lines.length - 4 : 0)
        : const <TranscriptLine>[];

    return AnimatedSize(
      duration: PremiumTokens.durationSlow,
      curve: PremiumTokens.easeOut,
      alignment: Alignment.bottomCenter,
      child: !show
          ? const SizedBox(width: double.infinity)
          : Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
              child: GlassCard(
                padding: const EdgeInsets.fromLTRB(14, 10, 14, 10),
                borderRadius: BorderRadius.circular(PremiumTokens.radiusXl),
                child: ConstrainedBox(
                  constraints: const BoxConstraints(
                    maxHeight: _transcriptMaxHeight,
                  ),
                  child: ShaderMask(
                    shaderCallback: (r) => const LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: [Colors.transparent, Colors.white],
                      stops: [0.0, 0.28],
                    ).createShader(r),
                    blendMode: BlendMode.dstIn,
                    child: ListView.builder(
                      reverse: true,
                      shrinkWrap: true,
                      padding: const EdgeInsets.only(top: 18),
                      itemCount: recent.length,
                      // Rows keep their state as new lines push them up, so
                      // only the new row plays its entrance.
                      findChildIndexCallback: (key) {
                        final at = recent.indexOf(
                          (key as ObjectKey).value as TranscriptLine,
                        );
                        return at < 0 ? null : recent.length - 1 - at;
                      },
                      itemBuilder: (_, i) {
                        final line = recent[recent.length - 1 - i];
                        return _SlideIn(
                          key: ObjectKey(line),
                          child: _TranscriptRow(
                            line: line,
                            inCall: _inCall,
                            latest: i == 0,
                          ),
                        );
                      },
                    ),
                  ),
                ),
              ),
            ),
    );
  }

  // ── Bottom controls ──

  Widget _buildVoiceControls(BuildContext context) {
    final active = widget.chatState != ChatState.idle;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 28),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          _RoundAction(
            icon: Icons.photo_camera_rounded,
            label: 'Camera',
            semanticLabel: 'Show something with the camera',
            color: PremiumTokens.accentPrimary,
            enabled: _cameraAvailable,
            onTap: _openCamera,
          ),
          AnimatedSwitcher(
            duration: PremiumTokens.durationSlow,
            switchInCurve: Curves.easeOutBack,
            transitionBuilder: (child, anim) => ScaleTransition(
              scale: anim,
              child: FadeTransition(opacity: anim, child: child),
            ),
            child: active && _conversation
                ? _RoundAction(
                    key: ValueKey('mute-${widget.muted}'),
                    icon: widget.muted
                        ? Icons.mic_off_rounded
                        : Icons.mic_rounded,
                    label: widget.muted ? 'Unmute' : 'Mute',
                    semanticLabel: widget.muted
                        ? 'Unmute microphone'
                        : 'Mute microphone',
                    color: widget.muted
                        ? PremiumTokens.error
                        : PremiumTokens.phaseListening,
                    enabled: widget.chatState != ChatState.connecting,
                    onTap: widget.onToggleMute,
                  )
                : _ModeBadge(conversation: _conversation),
          ),
          SpringReveal(
            visible: active,
            from: const Offset(-110, -170),
            child: _RoundAction(
              icon: Icons.close_rounded,
              label: 'End',
              semanticLabel: 'End the conversation',
              color: PremiumTokens.error,
              enabled: active,
              onTap: _endSession,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildCallControls(BuildContext context) {
    final agentLabel = widget.agentName.isNotEmpty ? widget.agentName : 'Agent';
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: widget.takenOver
          ? Row(
              children: [
                if (widget.configured) ...[
                  Expanded(
                    child: _PillButton(
                      icon: Icons.support_agent_rounded,
                      label: 'Hand to $agentLabel',
                      color: PremiumTokens.accentPrimary,
                      filled: false,
                      onTap: widget.onHandToAgent,
                    ),
                  ),
                  const SizedBox(width: 10),
                ],
                Expanded(
                  child: _PillButton(
                    icon: Icons.call_end_rounded,
                    label: 'Hang up',
                    color: PremiumTokens.error,
                    onTap: widget.onHangUp,
                  ),
                ),
              ],
            )
          : _PillButton(
              icon: Icons.phone_forwarded_rounded,
              label: 'Take over · unmute mic',
              color: PremiumTokens.phaseCall,
              onTap: widget.onTakeOver,
            ),
    );
  }
}

// ── Pieces ──

class _AutoAnswerPill extends StatelessWidget {
  const _AutoAnswerPill({
    required this.enabled,
    required this.available,
    required this.onChanged,
  });

  final bool enabled;
  final bool available;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    final color = enabled
        ? PremiumTokens.phaseListening
        : PremiumTokens.textMuted;
    return Semantics(
      toggled: enabled,
      enabled: available,
      label: 'Auto-answer calls',
      child: Pressable(
        depth: 0.1,
        onTap: () {
          if (available) {
            onChanged(!enabled);
          } else {
            ScaffoldMessenger.of(context)
              ..hideCurrentSnackBar()
              ..showSnackBar(
                const SnackBar(
                  content: Text(
                    'Grant permissions and add an API key in Settings first.',
                  ),
                ),
              );
          }
        },
        child: AnimatedContainer(
          duration: PremiumTokens.durationNormal,
          curve: PremiumTokens.easeOut,
          height: 44,
          padding: const EdgeInsets.only(left: 12, right: 5),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(999),
            color: color.withValues(alpha: enabled ? 0.12 : 0.05),
            border: Border.all(
              color: color.withValues(alpha: enabled ? 0.45 : 0.2),
            ),
            boxShadow: [
              if (enabled)
                BoxShadow(color: color.withValues(alpha: 0.25), blurRadius: 16),
            ],
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                enabled
                    ? Icons.phone_in_talk_rounded
                    : Icons.phone_disabled_rounded,
                size: 18,
                color: color,
              ),
              const SizedBox(width: 8),
              // A small track-and-knob switch; the whole pill is the target.
              AnimatedContainer(
                duration: PremiumTokens.durationNormal,
                curve: PremiumTokens.easeSpring,
                width: 40,
                height: 24,
                padding: const EdgeInsets.all(3),
                alignment: enabled
                    ? Alignment.centerRight
                    : Alignment.centerLeft,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(999),
                  color: enabled
                      ? color.withValues(alpha: 0.9)
                      : Colors.white.withValues(alpha: 0.1),
                ),
                child: Container(
                  width: 18,
                  height: 18,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: enabled
                        ? const Color(0xFF052E1C)
                        : PremiumTokens.textMuted,
                  ),
                ),
              ),
              const SizedBox(width: 2),
            ],
          ),
        ),
      ),
    );
  }
}

class _PhaseLabel extends StatelessWidget {
  const _PhaseLabel({
    required this.phase,
    required this.title,
    required this.hint,
  });

  final AgentPhase phase;
  final String title;
  final String hint;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        TweenAnimationBuilder<Color?>(
          tween: ColorTween(end: phase.color),
          duration: const Duration(milliseconds: 500),
          builder: (context, color, _) => KineticText(
            title.toUpperCase(),
            style: TextStyle(
              fontFamily: 'Syne',
              fontSize: 15,
              fontWeight: FontWeight.w700,
              letterSpacing: 3,
              color: Color.lerp(color, Colors.white, 0.25),
              shadows: [
                Shadow(color: color!.withValues(alpha: 0.6), blurRadius: 14),
              ],
            ),
          ),
        ),
        const SizedBox(height: 4),
        AnimatedSwitcher(
          duration: PremiumTokens.durationNormal,
          child: Text(
            hint,
            key: ValueKey(hint),
            textAlign: TextAlign.center,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontSize: 13.5,
              color: PremiumTokens.textTertiary,
            ),
          ),
        ),
      ],
    );
  }
}

class _TranscriptRow extends StatelessWidget {
  const _TranscriptRow({
    required this.line,
    required this.inCall,
    required this.latest,
  });

  final TranscriptLine line;
  final bool inCall;
  final bool latest;

  @override
  Widget build(BuildContext context) {
    if (line.speaker == 'tool') {
      final info = ToolInfo.of(line.text);
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          children: [
            const SizedBox(width: 52),
            Icon(info.icon, size: 14, color: info.color),
            const SizedBox(width: 6),
            Flexible(
              child: Text(
                info.label,
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  letterSpacing: 0.4,
                  color: info.color.withValues(alpha: 0.9),
                ),
              ),
            ),
          ],
        ),
      );
    }

    final isAgent = line.speaker == 'model' || line.speaker == 'agent';
    final who = isAgent
        ? 'AGENT'
        : inCall
        ? 'CALLER'
        : 'YOU';
    final whoColor = isAgent
        ? PremiumTokens.phaseSpeaking
        : inCall
        ? PremiumTokens.phaseCall
        : PremiumTokens.phaseListening;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 52,
            child: Padding(
              padding: const EdgeInsets.only(top: 2),
              child: Text(
                who,
                style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 1,
                  color: whoColor.withValues(alpha: 0.85),
                ),
              ),
            ),
          ),
          Expanded(
            child: line.imagePath != null
                ? _PhotoLine(line: line)
                : Text(
                    line.text,
                    style: TextStyle(
                      fontSize: 14,
                      height: 1.35,
                      color: latest
                          ? PremiumTokens.textPrimary
                          : PremiumTokens.textTertiary,
                    ),
                  ),
          ),
        ],
      ),
    );
  }
}

/// Plays once when a transcript row first appears: rises and fades in.
class _SlideIn extends StatelessWidget {
  const _SlideIn({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0, end: 1),
      duration: const Duration(milliseconds: 450),
      curve: Curves.easeOutCubic,
      child: child,
      builder: (context, v, child) => Opacity(
        opacity: v,
        child: Transform.translate(
          offset: Offset(0, 12 * (1 - v)),
          child: child,
        ),
      ),
    );
  }
}

class _PhotoLine extends StatelessWidget {
  const _PhotoLine({required this.line});

  final TranscriptLine line;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(PremiumTokens.radiusMd),
          child: Image.file(
            File(line.imagePath!),
            width: 64,
            height: 48,
            fit: BoxFit.cover,
            cacheWidth: 192,
            errorBuilder: (_, _, _) => Container(
              width: 64,
              height: 48,
              color: PremiumTokens.surfaceGlass,
              child: const Icon(
                Icons.broken_image_outlined,
                size: 16,
                color: PremiumTokens.textMuted,
              ),
            ),
          ),
        ),
        const SizedBox(width: 10),
        Flexible(
          child: Text(
            line.text,
            style: const TextStyle(
              fontSize: 14,
              color: PremiumTokens.textSecondary,
            ),
          ),
        ),
      ],
    );
  }
}

class _ModeBadge extends StatelessWidget {
  const _ModeBadge({required this.conversation});

  final bool conversation;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(999),
        color: PremiumTokens.surfaceGlass,
        border: Border.all(color: PremiumTokens.border),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            conversation
                ? Icons.all_inclusive_rounded
                : Icons.touch_app_rounded,
            size: 14,
            color: PremiumTokens.textTertiary,
          ),
          const SizedBox(width: 6),
          Text(
            conversation ? 'Conversation' : 'Push to talk',
            style: const TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              letterSpacing: 0.3,
              color: PremiumTokens.textTertiary,
            ),
          ),
        ],
      ),
    );
  }
}

class _RoundAction extends StatefulWidget {
  const _RoundAction({
    super.key,
    required this.icon,
    required this.label,
    required this.semanticLabel,
    required this.color,
    required this.enabled,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final String semanticLabel;
  final Color color;
  final bool enabled;
  final VoidCallback onTap;

  @override
  State<_RoundAction> createState() => _RoundActionState();
}

class _RoundActionState extends State<_RoundAction> {
  @override
  Widget build(BuildContext context) {
    final enabled = widget.enabled;
    final color = enabled ? widget.color : PremiumTokens.textMuted;
    return Semantics(
      button: true,
      enabled: enabled,
      label: widget.semanticLabel,
      excludeSemantics: true,
      child: Pressable(
        enabled: enabled,
        depth: 0.14,
        onTap: widget.onTap,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            AnimatedContainer(
              duration: PremiumTokens.durationNormal,
              curve: PremiumTokens.easeOut,
              width: 56,
              height: 56,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: color.withValues(alpha: enabled ? 0.14 : 0.05),
                border: Border.all(
                  color: color.withValues(alpha: enabled ? 0.55 : 0.18),
                ),
                boxShadow: [
                  if (enabled)
                    BoxShadow(
                      color: color.withValues(alpha: 0.28),
                      blurRadius: 18,
                    ),
                ],
              ),
              child: Icon(widget.icon, color: color, size: 24),
            ),
            const SizedBox(height: 6),
            Text(
              widget.label,
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                letterSpacing: 0.3,
                color: color.withValues(alpha: enabled ? 0.9 : 0.6),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _PillButton extends StatelessWidget {
  const _PillButton({
    required this.icon,
    required this.label,
    required this.color,
    required this.onTap,
    this.filled = true,
  });

  final IconData icon;
  final String label;
  final Color color;
  final VoidCallback onTap;
  final bool filled;

  @override
  Widget build(BuildContext context) {
    final fg = filled ? const Color(0xFF0B0F1A) : color;
    return Semantics(
      button: true,
      label: label,
      excludeSemantics: true,
      child: Pressable(
        onTap: onTap,
        depth: 0.05,
        child: Container(
          height: 54,
          width: double.infinity,
          decoration: ShapeDecoration(
            shape: StadiumBorder(
              side: filled
                  ? BorderSide.none
                  : BorderSide(color: color.withValues(alpha: 0.5)),
            ),
            color: filled ? color : color.withValues(alpha: 0.1),
            shadows: [
              BoxShadow(
                color: color.withValues(alpha: filled ? 0.35 : 0.12),
                blurRadius: 20,
              ),
            ],
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(icon, color: fg, size: 20),
              const SizedBox(width: 8),
              Flexible(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: fg,
                    fontSize: 15,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
