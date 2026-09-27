import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../theme/tokens.dart';
import '../../utils.dart';
import '../../widgets/premium_button.dart';
import 'recording_library_page.dart';

class RecordingsPage extends StatefulWidget {
  final MethodChannel control;
  const RecordingsPage({super.key, required this.control});
  @override
  State<RecordingsPage> createState() => _RecordingsPageState();
}

class _RecordingsPageState extends State<RecordingsPage> {
  bool _loaded = false;
  bool _recordAgentCalls = true;
  bool _recordAgentConversations = false;
  bool _recordPhoneCalls = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final config = await widget.control.invokeMethod('getConfig');
      if (config is Map) {
        _recordAgentCalls = config['recordAgentCalls'] == true;
        _recordAgentConversations = config['recordAgentConversations'] == true;
        _recordPhoneCalls = config['recordPhoneCalls'] == true;
      }
      setState(() => _loaded = true);
    } catch (_) {}
  }

  Future<void> _setRecording(String key, bool value) async {
    HapticFeedback.selectionClick();
    await widget.control.invokeMethod('setRecording', {
      'key': key,
      'enabled': value,
    });
  }

  @override
  Widget build(BuildContext context) {
    if (!_loaded) {
      return settingsScaffold(
        'Recordings',
        const Center(child: CircularProgressIndicator()),
      );
    }

    return settingsScaffold(
      'Recordings',
      ListView(
        padding: const EdgeInsets.all(20),
        children: [
          PremiumButton(
            label: 'Open Recording Library',
            icon: Icons.library_music_rounded,
            onPressed: () => Navigator.push(
              context,
              slideRoute(RecordingLibraryPage(control: widget.control)),
            ),
          ),
          const SizedBox(height: 14),
          sectionCard(
            context,
            title: 'Agent Recordings',
            child: Column(
              children: [
                _toggleRow(
                  icon: Icons.call_rounded,
                  title: 'Agent Calls',
                  subtitle: 'Record calls handled by the agent',
                  value: _recordAgentCalls,
                  onChanged: (v) {
                    _setRecording('recordAgentCalls', v);
                    setState(() => _recordAgentCalls = v);
                  },
                ),
                const SizedBox(height: 8),
                _toggleRow(
                  icon: Icons.chat_rounded,
                  title: 'Agent Conversations',
                  subtitle: 'Record voice chat sessions',
                  value: _recordAgentConversations,
                  onChanged: (v) {
                    _setRecording('recordAgentConversations', v);
                    setState(() => _recordAgentConversations = v);
                  },
                ),
              ],
            ),
          ),
          const SizedBox(height: 14),
          sectionCard(
            context,
            title: 'Phone Recordings',
            child: _toggleRow(
              icon: Icons.phone_in_talk_rounded,
              title: 'Phone Calls',
              subtitle: 'Record your own calls (requires root)',
              value: _recordPhoneCalls,
              onChanged: (v) {
                _setRecording('recordPhoneCalls', v);
                setState(() => _recordPhoneCalls = v);
              },
            ),
          ),
          const SizedBox(height: 20),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4),
            child: Text(
              'Recordings are saved to /sdcard/PocketDaemon/recordings/',
              style: TextStyle(fontSize: 12, color: PremiumTokens.textMuted),
            ),
          ),
        ],
      ),
    );
  }

  Widget _toggleRow({
    required IconData icon,
    required String title,
    required String subtitle,
    required bool value,
    required ValueChanged<bool> onChanged,
  }) {
    return Row(
      children: [
        Icon(icon, size: 20, color: PremiumTokens.textTertiary),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w500,
                  color: PremiumTokens.textPrimary,
                ),
              ),
              Text(
                subtitle,
                style: const TextStyle(
                  fontSize: 12,
                  color: PremiumTokens.textTertiary,
                ),
              ),
            ],
          ),
        ),
        Switch(value: value, onChanged: onChanged),
      ],
    );
  }
}
