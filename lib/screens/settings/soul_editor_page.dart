import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../theme/tokens.dart';
import '../../utils.dart';

class SoulEditorPage extends StatefulWidget {
  final MethodChannel control;
  const SoulEditorPage({super.key, required this.control});
  @override
  State<SoulEditorPage> createState() => _SoulEditorPageState();
}

class _SoulEditorPageState extends State<SoulEditorPage> {
  final _ctrl = TextEditingController();
  bool _loaded = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final text = await widget.control.invokeMethod('getSoul');
      _ctrl.text = text?.toString() ?? '';
      setState(() => _loaded = true);
    } catch (_) {}
  }

  Future<void> _save() async {
    HapticFeedback.mediumImpact();
    await widget.control.invokeMethod('setSoul', {'text': _ctrl.text});
    if (mounted) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Personality saved')));
    }
  }

  @override
  Widget build(BuildContext context) {
    return settingsScaffold(
      'Personality',
      Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              'SOUL.md defines how the agent behaves, speaks, and presents itself. '
              'Use {{ownerName}}, {{agentName}}, and {{role}} as placeholders.',
              style: TextStyle(fontSize: 12, color: PremiumTokens.textMuted),
            ),
            const SizedBox(height: 12),
            if (_loaded)
              Expanded(
                child: TextField(
                  controller: _ctrl,
                  maxLines: null,
                  expands: true,
                  textAlignVertical: TextAlignVertical.top,
                  style: const TextStyle(
                    fontSize: 13,
                    color: PremiumTokens.textSecondary,
                    height: 1.5,
                  ),
                  decoration: const InputDecoration(isDense: true),
                ),
              ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: _save,
              style: FilledButton.styleFrom(
                backgroundColor: PremiumTokens.accentPrimary,
                foregroundColor: Colors.white,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(PremiumTokens.radiusMd),
                ),
              ),
              child: const Text('Save'),
            ),
          ],
        ),
      ),
    );
  }
}
