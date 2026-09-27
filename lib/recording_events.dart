import 'dart:async';

/// Recording playback and transcription events, forwarded from the shell's single
/// platform event stream so sub-pages can listen without opening a second
/// subscription on the native channel (which would replace the shell's sink).
class RecordingEvents {
  RecordingEvents._();

  static final StreamController<Map<String, dynamic>> _controller =
      StreamController<Map<String, dynamic>>.broadcast();

  static Stream<Map<String, dynamic>> get stream => _controller.stream;

  static void push(Map<String, dynamic> event) => _controller.add(event);
}
