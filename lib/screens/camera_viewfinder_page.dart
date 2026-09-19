import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';
import '../theme/tokens.dart';

/// Sends an image into the live voice session. [path] is the local file the
/// transcript shows as a thumbnail.
typedef VoiceImageSender =
    void Function(
      String imageBase64,
      String mimeType,
      String? caption,
      String? path,
    );

/// Full-screen in-app viewfinder for showing something to the agent
/// mid-conversation.
///
/// The preview is a native Camera2 stream rendered into a Flutter texture, so
/// the voice session keeps running underneath. The shutter captures, sends the
/// photo into the session, and closes the page; the user just keeps talking.
class CameraViewfinderPage extends StatefulWidget {
  final MethodChannel control;
  final String agentName;
  final VoiceImageSender onSendImage;

  const CameraViewfinderPage({
    super.key,
    required this.control,
    required this.agentName,
    required this.onSendImage,
  });

  @override
  State<CameraViewfinderPage> createState() => _CameraViewfinderPageState();
}

class _CameraViewfinderPageState extends State<CameraViewfinderPage>
    with WidgetsBindingObserver {
  final _picker = ImagePicker();
  int? _textureId;
  int _previewWidth = 0;
  int _previewHeight = 0;
  String _facing = 'back';
  bool _opening = true;
  bool _capturing = false;
  bool _pickingFromGallery = false;
  bool _flash = false;
  bool _left = false;
  String? _error;

  String get _agentLabel =>
      widget.agentName.isNotEmpty ? widget.agentName : 'the agent';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _open(_facing);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    if (!_left) {
      _left = true;
      widget.control.invokeMethod('closeViewfinder');
    }
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (_pickingFromGallery) return;
    // The native side closes the camera when the activity stops; leave with it
    // instead of showing a dead preview.
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.hidden) {
      _leave();
    }
  }

  Future<void> _open(String facing) async {
    setState(() {
      _opening = true;
      _error = null;
      _textureId = null;
    });
    Map<String, dynamic> res;
    try {
      final raw = await widget.control.invokeMethod('openViewfinder', {
        'facing': facing,
      });
      res = Map<String, dynamic>.from(raw as Map);
    } on PlatformException catch (e) {
      res = {'error': e.message ?? e.code};
    }
    if (!mounted) return;
    final err = res['error'];
    setState(() {
      _opening = false;
      if (err != null) {
        _error = err.toString();
      } else {
        _textureId = (res['textureId'] as num).toInt();
        _previewWidth = (res['previewWidth'] as num).toInt();
        _previewHeight = (res['previewHeight'] as num).toInt();
        _facing = res['facing']?.toString() ?? facing;
      }
    });
  }

  Future<void> _flip() async {
    if (_opening || _capturing) return;
    HapticFeedback.selectionClick();
    await widget.control.invokeMethod('closeViewfinder');
    if (!mounted) return;
    await _open(_facing == 'back' ? 'front' : 'back');
  }

  Future<void> _capture() async {
    if (_capturing || _textureId == null) return;
    HapticFeedback.mediumImpact();
    setState(() {
      _capturing = true;
      _flash = true;
    });
    Map<String, dynamic> res;
    try {
      final raw = await widget.control.invokeMethod('captureViewfinderPhoto');
      res = Map<String, dynamic>.from(raw as Map);
    } on PlatformException catch (e) {
      res = {'error': e.message ?? e.code};
    }
    if (!mounted) return;
    final err = res['error'];
    if (err != null) {
      setState(() {
        _capturing = false;
        _flash = false;
      });
      _notify('Photo failed: $err');
      return;
    }
    if (res['status'] == 'saved') {
      _notify('Photo saved, but no conversation is running to show it to.');
    }
    await _leave();
  }

  Future<void> _pickFromGallery() async {
    if (_capturing) return;
    _pickingFromGallery = true;
    XFile? xfile;
    try {
      xfile = await _picker.pickImage(
        source: ImageSource.gallery,
        maxWidth: 1024,
        maxHeight: 1024,
        imageQuality: 85,
      );
    } finally {
      _pickingFromGallery = false;
    }
    if (!mounted) return;
    if (xfile == null) {
      // The picker stopped the activity, which closed the camera natively;
      // bring the preview back.
      await _open(_facing);
      return;
    }
    final bytes = await File(xfile.path).readAsBytes();
    final mime = xfile.path.toLowerCase().endsWith('.png')
        ? 'image/png'
        : 'image/jpeg';
    widget.onSendImage(base64Encode(bytes), mime, null, xfile.path);
    await _leave();
  }

  Future<void> _leave() async {
    if (_left) return;
    _left = true;
    try {
      await widget.control.invokeMethod('closeViewfinder');
    } catch (_) {}
    if (mounted) Navigator.of(context).pop();
  }

  void _notify(String message) {
    ScaffoldMessenger.maybeOf(
      context,
    )?.showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        fit: StackFit.expand,
        children: [
          if (_textureId != null)
            _buildPreview(context)
          else if (_error != null)
            _buildError()
          else
            const Center(
              child: CircularProgressIndicator(
                color: PremiumTokens.accentPrimary,
              ),
            ),
          IgnorePointer(
            child: AnimatedOpacity(
              opacity: _flash ? 0.8 : 0.0,
              duration: PremiumTokens.durationFast,
              child: Container(color: Colors.white),
            ),
          ),
          SafeArea(
            child: Column(
              children: [_buildTopBar(), const Spacer(), _buildBottomBar()],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPreview(BuildContext context) {
    // Frames arrive in sensor (landscape) coordinates and the engine rotates
    // them upright for the device orientation, so a portrait screen shows the
    // buffer with its sides swapped.
    final portrait = MediaQuery.orientationOf(context) == Orientation.portrait;
    final w = (portrait ? _previewHeight : _previewWidth).toDouble();
    final h = (portrait ? _previewWidth : _previewHeight).toDouble();
    return ClipRect(
      child: FittedBox(
        fit: BoxFit.cover,
        clipBehavior: Clip.hardEdge,
        child: SizedBox(
          width: w,
          height: h,
          child: Texture(textureId: _textureId!),
        ),
      ),
    );
  }

  Widget _buildTopBar() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
      child: Row(
        children: [
          _roundButton(
            icon: Icons.close_rounded,
            label: 'Close camera',
            onTap: _leave,
          ),
          Expanded(
            child: Text(
              'Show $_agentLabel',
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontFamily: 'Syne',
                fontSize: 15,
                fontWeight: FontWeight.w600,
                color: Colors.white,
              ),
            ),
          ),
          _roundButton(
            icon: Icons.cameraswitch_rounded,
            label: 'Switch camera',
            onTap: _textureId != null && !_capturing ? _flip : null,
          ),
        ],
      ),
    );
  }

  Widget _buildBottomBar() {
    final ready = _textureId != null && !_capturing;
    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 0, 24, 20),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            'Take a photo and keep talking. $_agentLabel will see it.',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 12, color: Colors.white.withAlpha(180)),
          ),
          const SizedBox(height: 18),
          Row(
            children: [
              _roundButton(
                icon: Icons.photo_library_rounded,
                label: 'Choose from gallery',
                onTap: _capturing ? null : _pickFromGallery,
              ),
              Expanded(child: Center(child: _buildShutter(ready))),
              const SizedBox(width: 48, height: 48),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildShutter(bool ready) {
    return Semantics(
      button: true,
      label: 'Take photo',
      child: GestureDetector(
        onTap: ready ? _capture : null,
        child: AnimatedContainer(
          duration: PremiumTokens.durationFast,
          width: 76,
          height: 76,
          padding: const EdgeInsets.all(5),
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            border: Border.all(
              color: Colors.white.withAlpha(ready ? 255 : 110),
              width: 4,
            ),
          ),
          child: AnimatedContainer(
            duration: PremiumTokens.durationFast,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: ready ? Colors.white : Colors.white.withAlpha(90),
            ),
            child: _capturing
                ? const Padding(
                    padding: EdgeInsets.all(18),
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.black54,
                    ),
                  )
                : null,
          ),
        ),
      ),
    );
  }

  Widget _roundButton({
    required IconData icon,
    required String label,
    VoidCallback? onTap,
  }) {
    final enabled = onTap != null;
    return Semantics(
      button: true,
      enabled: enabled,
      label: label,
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          width: 48,
          height: 48,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: Colors.black.withAlpha(110),
            border: Border.all(
              color: Colors.white.withAlpha(enabled ? 60 : 25),
              width: 0.5,
            ),
          ),
          child: Icon(
            icon,
            color: Colors.white.withAlpha(enabled ? 255 : 90),
            size: 24,
          ),
        ),
      ),
    );
  }

  Widget _buildError() {
    final message = _error ?? 'Camera unavailable';
    final permission = message.contains('permission');
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(
              Icons.no_photography_rounded,
              color: PremiumTokens.textMuted,
              size: 40,
            ),
            const SizedBox(height: 12),
            Text(
              message,
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: PremiumTokens.textSecondary,
                fontSize: 14,
              ),
            ),
            if (permission) ...[
              const SizedBox(height: 6),
              const Text(
                'Grant the camera permission under Settings > Permissions.',
                textAlign: TextAlign.center,
                style: TextStyle(color: PremiumTokens.textMuted, fontSize: 12),
              ),
            ],
            const SizedBox(height: 18),
            TextButton(
              onPressed: () => _open(_facing),
              child: const Text(
                'Try again',
                style: TextStyle(color: PremiumTokens.accentPrimary),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
