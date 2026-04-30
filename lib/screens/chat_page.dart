import 'dart:convert';
import 'dart:io';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import '../models.dart';
import '../theme/tokens.dart';
import '../widgets/typing_dot.dart';

class ChatPage extends StatefulWidget {
  final List<ChatMessage> messages;
  final bool active;
  final bool waiting;
  final void Function(
    String text, {
    String? imageBase64,
    String? imageMimeType,
    String? imagePath,
  })
  onSend;
  final VoidCallback onStartSession;
  final VoidCallback onEndSession;

  const ChatPage({
    super.key,
    required this.messages,
    required this.active,
    required this.waiting,
    required this.onSend,
    required this.onStartSession,
    required this.onEndSession,
  });

  @override
  State<ChatPage> createState() => _ChatPageState();
}

class _ChatPageState extends State<ChatPage> {
  final _inputCtrl = TextEditingController();
  final _scrollCtrl = ScrollController();
  final _focusNode = FocusNode();
  final _picker = ImagePicker();
  String? _pendingImagePath;

  @override
  void dispose() {
    _inputCtrl.dispose();
    _scrollCtrl.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  @override
  void didUpdateWidget(covariant ChatPage old) {
    super.didUpdateWidget(old);
    if (widget.messages.length != old.messages.length) {
      _scrollToBottom();
    }
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollCtrl.hasClients) {
        _scrollCtrl.animateTo(
          _scrollCtrl.position.maxScrollExtent,
          duration: PremiumTokens.durationNormal,
          curve: PremiumTokens.easeOut,
        );
      }
    });
  }

  void _showPickerSheet() {
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
                  _pickImage(ImageSource.camera);
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
                  _pickImage(ImageSource.gallery);
                },
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _pickImage(ImageSource source) async {
    final xfile = await _picker.pickImage(
      source: source,
      maxWidth: 1024,
      maxHeight: 1024,
      imageQuality: 85,
    );
    if (xfile != null) {
      setState(() => _pendingImagePath = xfile.path);
    }
  }

  Future<void> _handleSend() async {
    final text = _inputCtrl.text.trim();
    final imgPath = _pendingImagePath;
    if (text.isEmpty && imgPath == null) return;

    _inputCtrl.clear();
    setState(() => _pendingImagePath = null);

    String? b64;
    String? mime;
    if (imgPath != null) {
      final bytes = await File(imgPath).readAsBytes();
      b64 = base64Encode(bytes);
      mime = imgPath.toLowerCase().endsWith('.png')
          ? 'image/png'
          : 'image/jpeg';
    }

    void doSend() {
      widget.onSend(
        text,
        imageBase64: b64,
        imageMimeType: mime,
        imagePath: imgPath,
      );
    }

    if (!widget.active) {
      widget.onStartSession();
      await Future.delayed(const Duration(milliseconds: 300));
      if (!mounted) return;
      doSend();
    } else {
      doSend();
    }
    _focusNode.requestFocus();
    _scrollToBottom();
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        _buildHeader(),
        Expanded(child: _buildMessageList()),
        _buildInput(),
      ],
    );
  }

  Widget _buildHeader() {
    return ClipRRect(
      child: BackdropFilter(
        filter: ImageFilter.blur(
          sigmaX: PremiumTokens.blurLg,
          sigmaY: PremiumTokens.blurLg,
        ),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          decoration: const BoxDecoration(
            color: PremiumTokens.surfaceGlass,
            border: Border(
              bottom: BorderSide(color: PremiumTokens.border, width: 0.5),
            ),
          ),
          child: Row(
            children: [
              const Icon(
                Icons.chat_rounded,
                size: 20,
                color: PremiumTokens.accentPrimary,
              ),
              const SizedBox(width: 8),
              const Expanded(
                child: Text(
                  'Chat',
                  style: TextStyle(
                    fontFamily: 'Syne',
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                    color: PremiumTokens.textPrimary,
                  ),
                ),
              ),
              if (widget.active)
                GestureDetector(
                  onTap: widget.onEndSession,
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 4,
                    ),
                    decoration: BoxDecoration(
                      color: PremiumTokens.error.withAlpha(25),
                      borderRadius: BorderRadius.circular(
                        PremiumTokens.radiusMd,
                      ),
                      border: Border.all(
                        color: PremiumTokens.error.withAlpha(60),
                      ),
                    ),
                    child: const Text(
                      'End',
                      style: TextStyle(
                        color: PremiumTokens.error,
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                ),
              if (!widget.active)
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 8,
                    vertical: 3,
                  ),
                  decoration: BoxDecoration(
                    color: PremiumTokens.surfaceGlass,
                    borderRadius: BorderRadius.circular(PremiumTokens.radiusMd),
                  ),
                  child: const Text(
                    'offline',
                    style: TextStyle(
                      color: PremiumTokens.textMuted,
                      fontSize: 11,
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildMessageList() {
    if (widget.messages.isEmpty && !widget.waiting) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.chat_bubble_outline,
                size: 48,
                color: PremiumTokens.textMuted.withAlpha(60),
              ),
              const SizedBox(height: 16),
              const Text(
                'Type a message to start chatting with your agent.',
                textAlign: TextAlign.center,
                style: TextStyle(color: PremiumTokens.textMuted, fontSize: 14),
              ),
            ],
          ),
        ),
      );
    }

    final itemCount = widget.messages.length + (widget.waiting ? 1 : 0);
    return ListView.builder(
      controller: _scrollCtrl,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      itemCount: itemCount,
      itemBuilder: (context, index) {
        if (index == widget.messages.length && widget.waiting) {
          return _buildTypingIndicator();
        }
        return _buildBubble(widget.messages[index]);
      },
    );
  }

  Widget _buildBubble(ChatMessage msg) {
    final isUser = msg.sender == 'user';
    final time =
        '${msg.timestamp.hour.toString().padLeft(2, '0')}:${msg.timestamp.minute.toString().padLeft(2, '0')}';
    final bubbleRadius = BorderRadius.only(
      topLeft: const Radius.circular(18),
      topRight: const Radius.circular(18),
      bottomLeft: Radius.circular(isUser ? 18 : 4),
      bottomRight: Radius.circular(isUser ? 4 : 18),
    );

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        mainAxisAlignment: isUser
            ? MainAxisAlignment.end
            : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          if (!isUser) const SizedBox(width: 4),
          Flexible(
            child: Container(
              constraints: BoxConstraints(
                maxWidth: MediaQuery.of(context).size.width * 0.78,
              ),
              decoration: BoxDecoration(
                color: isUser
                    ? PremiumTokens.accentPrimary.withAlpha(25)
                    : PremiumTokens.surfaceGlass,
                borderRadius: bubbleRadius,
                border: Border.all(
                  color: isUser
                      ? PremiumTokens.accentPrimary.withAlpha(40)
                      : PremiumTokens.borderGlass,
                  width: 0.5,
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (msg.imagePath != null)
                    GestureDetector(
                      onTap: () => _openImageViewer(msg.imagePath!),
                      child: ClipRRect(
                        borderRadius: BorderRadius.only(
                          topLeft: bubbleRadius.topLeft,
                          topRight: bubbleRadius.topRight,
                        ),
                        child: ConstrainedBox(
                          constraints: const BoxConstraints(maxHeight: 200),
                          child: Image.file(
                            File(msg.imagePath!),
                            width: double.infinity,
                            fit: BoxFit.cover,
                          ),
                        ),
                      ),
                    ),
                  Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 14,
                      vertical: 10,
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        if (msg.isHistory)
                          Padding(
                            padding: const EdgeInsets.only(bottom: 4),
                            child: Text(
                              msg.sender == 'user' ? 'You' : 'Agent',
                              style: TextStyle(
                                fontSize: 10,
                                fontWeight: FontWeight.w600,
                                color: isUser
                                    ? PremiumTokens.accentPrimary.withAlpha(150)
                                    : PremiumTokens.textMuted,
                              ),
                            ),
                          ),
                        if (msg.text.isNotEmpty)
                          SelectableText(
                            msg.text,
                            style: TextStyle(
                              fontSize: 14,
                              color: msg.isHistory
                                  ? PremiumTokens.textTertiary
                                  : PremiumTokens.textSecondary,
                              height: 1.4,
                            ),
                          ),
                        if (msg.text.isNotEmpty) const SizedBox(height: 4),
                        Text(
                          time,
                          style: const TextStyle(
                            fontSize: 10,
                            color: PremiumTokens.textMuted,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
          if (isUser) const SizedBox(width: 4),
        ],
      ),
    );
  }

  void _openImageViewer(String path) {
    Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => FullScreenImageViewer(path: path)),
    );
  }

  Widget _buildTypingIndicator() {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 4),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            decoration: BoxDecoration(
              color: PremiumTokens.surfaceGlass,
              borderRadius: const BorderRadius.only(
                topLeft: Radius.circular(18),
                topRight: Radius.circular(18),
                bottomRight: Radius.circular(18),
                bottomLeft: Radius.circular(4),
              ),
              border: Border.all(color: PremiumTokens.borderGlass, width: 0.5),
            ),
            child: const Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                TypingDot(delay: 0, color: PremiumTokens.accentPrimary),
                SizedBox(width: 4),
                TypingDot(delay: 150, color: PremiumTokens.accentPrimary),
                SizedBox(width: 4),
                TypingDot(delay: 300, color: PremiumTokens.accentPrimary),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildInput() {
    return ClipRRect(
      child: BackdropFilter(
        filter: ImageFilter.blur(
          sigmaX: PremiumTokens.blurLg,
          sigmaY: PremiumTokens.blurLg,
        ),
        child: Container(
          decoration: const BoxDecoration(
            color: PremiumTokens.surfaceGlass,
            border: Border(
              top: BorderSide(color: PremiumTokens.border, width: 0.5),
            ),
          ),
          child: SafeArea(
            top: false,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (_pendingImagePath != null)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
                    child: Row(
                      children: [
                        ClipRRect(
                          borderRadius: BorderRadius.circular(
                            PremiumTokens.radiusSm,
                          ),
                          child: Image.file(
                            File(_pendingImagePath!),
                            width: 48,
                            height: 48,
                            fit: BoxFit.cover,
                          ),
                        ),
                        const SizedBox(width: 8),
                        const Text(
                          'Image attached',
                          style: TextStyle(
                            fontSize: 12,
                            color: PremiumTokens.textMuted,
                          ),
                        ),
                        const Spacer(),
                        GestureDetector(
                          onTap: () => setState(() => _pendingImagePath = null),
                          child: const Icon(
                            Icons.close_rounded,
                            size: 18,
                            color: PremiumTokens.textMuted,
                          ),
                        ),
                      ],
                    ),
                  ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(6, 8, 8, 8),
                  child: Row(
                    children: [
                      IconButton(
                        icon: const Icon(
                          Icons.add_rounded,
                          color: PremiumTokens.textMuted,
                        ),
                        onPressed: widget.waiting ? null : _showPickerSheet,
                        padding: const EdgeInsets.all(8),
                        constraints: const BoxConstraints(),
                      ),
                      const SizedBox(width: 2),
                      Expanded(
                        child: TextField(
                          controller: _inputCtrl,
                          focusNode: _focusNode,
                          maxLines: 4,
                          minLines: 1,
                          textInputAction: TextInputAction.send,
                          onSubmitted: (_) => _handleSend(),
                          style: const TextStyle(
                            fontSize: 14,
                            color: PremiumTokens.textPrimary,
                          ),
                          decoration: InputDecoration(
                            hintText: widget.active
                                ? 'Message...'
                                : 'Type to start a chat...',
                            hintStyle: const TextStyle(
                              color: PremiumTokens.textMuted,
                            ),
                            filled: true,
                            fillColor: PremiumTokens.surfaceGlass,
                            contentPadding: const EdgeInsets.symmetric(
                              horizontal: 14,
                              vertical: 10,
                            ),
                            border: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(
                                PremiumTokens.radiusXl,
                              ),
                              borderSide: const BorderSide(
                                color: PremiumTokens.borderGlass,
                                width: 0.5,
                              ),
                            ),
                            enabledBorder: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(
                                PremiumTokens.radiusXl,
                              ),
                              borderSide: const BorderSide(
                                color: PremiumTokens.borderGlass,
                                width: 0.5,
                              ),
                            ),
                            focusedBorder: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(
                                PremiumTokens.radiusXl,
                              ),
                              borderSide: const BorderSide(
                                color: PremiumTokens.borderFocus,
                              ),
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(width: 6),
                      Material(
                        color: widget.waiting
                            ? PremiumTokens.surfaceGlass
                            : PremiumTokens.accentPrimary,
                        borderRadius: BorderRadius.circular(
                          PremiumTokens.radiusXl,
                        ),
                        child: InkWell(
                          borderRadius: BorderRadius.circular(
                            PremiumTokens.radiusXl,
                          ),
                          onTap: widget.waiting ? null : _handleSend,
                          child: Padding(
                            padding: const EdgeInsets.all(10),
                            child: Icon(
                              Icons.send_rounded,
                              size: 20,
                              color: widget.waiting
                                  ? PremiumTokens.textMuted
                                  : Colors.black87,
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class FullScreenImageViewer extends StatelessWidget {
  final String path;
  const FullScreenImageViewer({super.key, required this.path});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        iconTheme: const IconThemeData(color: Colors.white70),
      ),
      extendBodyBehindAppBar: true,
      body: Center(
        child: InteractiveViewer(
          minScale: 0.5,
          maxScale: 4.0,
          child: Image.file(File(path)),
        ),
      ),
    );
  }
}
