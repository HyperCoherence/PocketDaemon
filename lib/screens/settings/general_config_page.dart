import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../theme/tokens.dart';
import '../../utils.dart';
import 'soul_editor_page.dart';

class GeneralConfigPage extends StatefulWidget {
  final MethodChannel control;
  final VoidCallback onRefresh;
  const GeneralConfigPage({
    super.key,
    required this.control,
    required this.onRefresh,
  });
  @override
  State<GeneralConfigPage> createState() => _GeneralConfigPageState();
}

class _GeneralConfigPageState extends State<GeneralConfigPage> {
  final _geminiApiKeyCtrl = TextEditingController();
  final _xaiApiKeyCtrl = TextEditingController();
  final _modelCtrl = TextEditingController();
  final _promptCtrl = TextEditingController();
  final _delayCtrl = TextEditingController();
  final _ownerNameCtrl = TextEditingController();
  final _agentNameCtrl = TextEditingController();
  final _agentRoleCtrl = TextEditingController();
  bool _loaded = false;
  bool _speakerMonitor = true;
  bool _bargeIn = true;
  bool _assistantButton = false;
  String _provider = 'gemini';
  String _voice = 'Kore';
  String _chatMode = 'conversation';

  static const _geminiVoices = {
    'Zephyr': 'Bright',
    'Kore': 'Firm',
    'Orus': 'Firm',
    'Autonoe': 'Bright',
    'Umbriel': 'Easy-going',
    'Erinome': 'Clear',
    'Laomedeia': 'Upbeat',
    'Schedar': 'Even',
    'Achird': 'Friendly',
    'Sadachbia': 'Lively',
    'Puck': 'Upbeat',
    'Fenrir': 'Excitable',
    'Aoede': 'Breezy',
    'Enceladus': 'Breathy',
    'Algieba': 'Smooth',
    'Algenib': 'Gravelly',
    'Achernar': 'Soft',
    'Gacrux': 'Mature',
    'Zubenelgenubi': 'Casual',
    'Sadaltager': 'Knowledgeable',
    'Charon': 'Informative',
    'Leda': 'Youthful',
    'Callirrhoe': 'Easy-going',
    'Iapetus': 'Clear',
    'Despina': 'Smooth',
    'Rasalgethi': 'Informative',
    'Alnilam': 'Firm',
    'Pulcherrima': 'Forward',
    'Vindemiatrix': 'Gentle',
    'Sulafat': 'Warm',
  };

  static const _xaiVoices = {
    'eve': 'Energetic',
    'ara': 'Warm',
    'rex': 'Clear',
    'sal': 'Balanced',
    'leo': 'Authoritative',
  };

  static const _legacyXaiModels = {'grok-voice-fast-1.0'};

  Map<String, String> get _voiceOptions =>
      _provider == 'xai' ? _xaiVoices : _geminiVoices;

  String get _defaultVoice => _provider == 'xai' ? 'eve' : 'Kore';

  String get _defaultModel => _provider == 'xai'
      ? 'grok-voice-think-fast-1.0'
      : 'gemini-3.1-flash-live-preview';

  bool _modelNeedsDefault(String provider, String model) {
    final trimmed = model.trim();
    if (trimmed.isEmpty) return true;
    if (provider == 'xai') {
      return _legacyXaiModels.contains(trimmed) || trimmed.startsWith('gemini-');
    }
    return trimmed.startsWith('grok-voice-');
  }

  void _selectProvider(String provider) {
    setState(() {
      _provider = provider;
      if (!_voiceOptions.containsKey(_voice)) _voice = _defaultVoice;
      if (_modelNeedsDefault(provider, _modelCtrl.text)) {
        _modelCtrl.text = _defaultModel;
      }
    });
  }

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _geminiApiKeyCtrl.dispose();
    _xaiApiKeyCtrl.dispose();
    _modelCtrl.dispose();
    _promptCtrl.dispose();
    _delayCtrl.dispose();
    _ownerNameCtrl.dispose();
    _agentNameCtrl.dispose();
    _agentRoleCtrl.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final config = await widget.control.invokeMethod('getConfig');
      if (config is Map) {
        final providers = config['providers'] is Map
            ? Map<Object?, Object?>.from(config['providers'] as Map)
            : const <Object?, Object?>{};
        final agents = config['agents'] is Map
            ? Map<Object?, Object?>.from(config['agents'] as Map)
            : const <Object?, Object?>{};
        final gemini = providers['gemini'] is Map
            ? Map<Object?, Object?>.from(providers['gemini'] as Map)
            : const <Object?, Object?>{};
        final xai = providers['xai'] is Map
            ? Map<Object?, Object?>.from(providers['xai'] as Map)
            : const <Object?, Object?>{};
        final voiceAgent = agents['voice'] is Map
            ? Map<Object?, Object?>.from(agents['voice'] as Map)
            : const <Object?, Object?>{};

        _provider =
            voiceAgent['provider']?.toString() ??
            config['voiceProvider']?.toString() ??
            'gemini';
        if (_provider != 'xai') _provider = 'gemini';
        _geminiApiKeyCtrl.text =
            gemini['apiKey']?.toString() ?? config['apiKey']?.toString() ?? '';
        _xaiApiKeyCtrl.text = xai['apiKey']?.toString() ?? '';
        _modelCtrl.text =
            voiceAgent['model']?.toString() ?? config['model']?.toString() ?? '';
        if (_modelNeedsDefault(_provider, _modelCtrl.text)) {
          _modelCtrl.text = _defaultModel;
        }
        _promptCtrl.text = config['systemPrompt']?.toString() ?? '';
        _delayCtrl.text = (config['answerDelay'] ?? 2000).toString();
        _speakerMonitor = config['speakerMonitor'] != false;
        _bargeIn = config['bargeIn'] != false;
        _assistantButton = config['assistantButton'] == true;
        _ownerNameCtrl.text = config['ownerName']?.toString() ?? '';
        _agentNameCtrl.text = config['agentName']?.toString() ?? '';
        _agentRoleCtrl.text = config['agentRole']?.toString() ?? '';
        _voice =
            voiceAgent['voice']?.toString() ?? config['voice']?.toString() ?? '';
        if (!_voiceOptions.containsKey(_voice)) _voice = _defaultVoice;
        _chatMode = config['chatMode']?.toString() ?? 'conversation';
      }
      setState(() => _loaded = true);
    } catch (_) {}
  }

  Future<void> _save() async {
    HapticFeedback.mediumImpact();
    await widget.control.invokeMethod('setConfig', {
      'apiKey': _geminiApiKeyCtrl.text.trim(),
      'geminiApiKey': _geminiApiKeyCtrl.text.trim(),
      'xaiApiKey': _xaiApiKeyCtrl.text.trim(),
      'voiceProvider': _provider,
      'voiceModel': _modelCtrl.text.trim(),
      'systemPrompt': _promptCtrl.text.trim(),
      'answerDelay': int.tryParse(_delayCtrl.text.trim()) ?? 2000,
      'ownerName': _ownerNameCtrl.text.trim(),
      'agentName': _agentNameCtrl.text.trim(),
      'agentRole': _agentRoleCtrl.text.trim(),
      'voice': _voice,
      'providers': {
        'gemini': {'apiKey': _geminiApiKeyCtrl.text.trim()},
        'xai': {'apiKey': _xaiApiKeyCtrl.text.trim()},
      },
      'agents': {
        'voice': {
          'provider': _provider,
          'model': _modelCtrl.text.trim(),
          'voice': _voice,
        },
      },
    });
    widget.onRefresh();
    if (mounted) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Configuration saved')));
    }
  }

  @override
  Widget build(BuildContext context) {
    return settingsScaffold(
      'General',
      ListView(
        padding: const EdgeInsets.all(20),
        children: [
          sectionCard(
            context,
            title: 'Call Handling',
            child: Column(
              children: [
                _toggleRow(
                  icon: Icons.volume_up_rounded,
                  title: 'Speaker Monitor',
                  subtitle: 'Hear agent calls on speaker',
                  value: _speakerMonitor,
                  onChanged: (v) async {
                    HapticFeedback.selectionClick();
                    await widget.control.invokeMethod('setSpeakerMonitor', {
                      'enabled': v,
                    });
                    setState(() => _speakerMonitor = v);
                  },
                ),
                const SizedBox(height: 8),
                _toggleRow(
                  icon: Icons.record_voice_over_rounded,
                  title: 'Barge-in',
                  subtitle: 'Interrupt agent by speaking',
                  value: _bargeIn,
                  onChanged: (v) async {
                    HapticFeedback.selectionClick();
                    await widget.control.invokeMethod('setBargeIn', {
                      'enabled': v,
                    });
                    setState(() => _bargeIn = v);
                  },
                ),
              ],
            ),
          ),
          const SizedBox(height: 14),
          sectionCard(
            context,
            title: 'Voice Chat',
            child: Row(
              children: [
                const Icon(
                  Icons.mic_rounded,
                  size: 20,
                  color: PremiumTokens.textTertiary,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        'Chat Mode',
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w500,
                          color: PremiumTokens.textPrimary,
                        ),
                      ),
                      Text(
                        _chatMode == 'ptt'
                            ? 'Push-to-talk'
                            : 'Continuous conversation',
                        style: const TextStyle(
                          fontSize: 12,
                          color: PremiumTokens.textTertiary,
                        ),
                      ),
                    ],
                  ),
                ),
                SegmentedButton<String>(
                  segments: const [
                    ButtonSegment(value: 'ptt', label: Text('PTT')),
                    ButtonSegment(value: 'conversation', label: Text('Conv')),
                  ],
                  selected: {_chatMode},
                  onSelectionChanged: (v) async {
                    HapticFeedback.selectionClick();
                    final mode = v.first;
                    await widget.control.invokeMethod('setChatMode', {
                      'mode': mode,
                    });
                    setState(() => _chatMode = mode);
                    widget.onRefresh();
                  },
                  style: const ButtonStyle(
                    visualDensity: VisualDensity.compact,
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 14),
          sectionCard(
            context,
            title: 'Device',
            child: _toggleRow(
              icon: Icons.power_settings_new_rounded,
              title: 'Assistant Button',
              subtitle: 'Long-press power opens PocketDaemon',
              value: _assistantButton,
              onChanged: (v) async {
                HapticFeedback.selectionClick();
                await widget.control.invokeMethod('setAssistantButton', {
                  'enabled': v,
                });
                setState(() => _assistantButton = v);
              },
            ),
          ),
          const SizedBox(height: 14),
          if (_loaded)
            sectionCard(
              context,
              title: 'Identity',
              child: Column(
                children: [
                  TextField(
                    controller: _ownerNameCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Owner Name',
                      hintText: 'Your name',
                      isDense: true,
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _agentNameCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Agent Name',
                      hintText: 'optional',
                      isDense: true,
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _agentRoleCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Role',
                      hintText: 'personal AI assistant',
                      isDense: true,
                    ),
                  ),
                  const SizedBox(height: 12),
                  SizedBox(
                    width: double.infinity,
                    child: OutlinedButton.icon(
                      onPressed: () => Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (_) =>
                              SoulEditorPage(control: widget.control),
                        ),
                      ),
                      icon: const Icon(Icons.psychology_rounded, size: 18),
                      label: const Text('Edit Personality (SOUL.md)'),
                    ),
                  ),
                ],
              ),
            ),
          const SizedBox(height: 14),
          if (_loaded)
            sectionCard(
              context,
              title: 'Configuration',
              child: Column(
                children: [
                  Align(
                    alignment: Alignment.centerLeft,
                    child: SegmentedButton<String>(
                      segments: const [
                        ButtonSegment(value: 'gemini', label: Text('Gemini')),
                        ButtonSegment(value: 'xai', label: Text('xAI')),
                      ],
                      selected: {_provider},
                      onSelectionChanged: (v) {
                        HapticFeedback.selectionClick();
                        _selectProvider(v.first);
                      },
                      style: const ButtonStyle(
                        visualDensity: VisualDensity.compact,
                        tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                      ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _geminiApiKeyCtrl,
                    obscureText: true,
                    decoration: const InputDecoration(
                      labelText: 'Gemini API Key',
                      isDense: true,
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _xaiApiKeyCtrl,
                    obscureText: true,
                    decoration: const InputDecoration(
                      labelText: 'xAI API Key',
                      isDense: true,
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _modelCtrl,
                    decoration: InputDecoration(
                      labelText: 'Model',
                      hintText: _defaultModel,
                      isDense: true,
                    ),
                  ),
                  const SizedBox(height: 12),
                  DropdownButtonFormField<String>(
                    key: ValueKey('voice-$_provider'),
                    initialValue: _voiceOptions.containsKey(_voice)
                        ? _voice
                        : _defaultVoice,
                    decoration: const InputDecoration(
                      labelText: 'Voice',
                      isDense: true,
                    ),
                    isExpanded: true,
                    items: _voiceOptions.entries.map((e) {
                      return DropdownMenuItem(
                        value: e.key,
                        child: Text(
                          '${e.key}  —  ${e.value}',
                          style: const TextStyle(fontSize: 14),
                        ),
                      );
                    }).toList(),
                    onChanged: (v) {
                      if (v != null) setState(() => _voice = v);
                    },
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _promptCtrl,
                    maxLines: 3,
                    decoration: const InputDecoration(
                      labelText: 'Call Prompt (unknown callers)',
                      isDense: true,
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _delayCtrl,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: 'Answer Delay (ms)',
                      isDense: true,
                    ),
                  ),
                  const SizedBox(height: 16),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton(
                      onPressed: _save,
                      style: FilledButton.styleFrom(
                        backgroundColor: PremiumTokens.accentPrimary,
                        foregroundColor: Colors.white,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(
                            PremiumTokens.radiusMd,
                          ),
                        ),
                      ),
                      child: const Text('Save'),
                    ),
                  ),
                ],
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
