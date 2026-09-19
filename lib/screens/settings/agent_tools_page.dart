import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../theme/tokens.dart';
import '../../utils.dart';

const toolLabels = <String, String>{
  'hangUp': 'Hang Up',
  'leave_message': 'Leave Message',
  'search_memory': 'Search Memory',
  'get_location': 'Get Location',
  'get_notes': 'Get Notes',
  'search_contacts': 'Search Contacts',
  'send_sms': 'Send SMS',
  'add_contact': 'Add Contact',
  'dial_contact': 'Dial Contact',
  'dial_number': 'Dial Number',
  'open_maps': 'Open Maps',
  'play_youtube': 'Play YouTube',
  'ask_expert': 'Ask Expert',
  'ask_fable': 'Ask Fable',
  'schedule_task': 'Schedule Task',
  'list_scheduled_tasks': 'List Tasks',
  'cancel_scheduled_task': 'Cancel Task',
  'update_scheduled_task': 'Update Task',
  'take_photo': 'Take Photo',
  'use_skill': 'Use Skill',
  'google_search': 'Google Search',
};

const toolDescriptions = <String, String>{
  'hangUp': 'End the phone call (always on)',
  'leave_message': 'Save a note for the owner',
  'search_memory': 'Query stored memories',
  'get_location': 'Access GPS location',
  'get_notes': 'Retrieve saved notes',
  'search_contacts': 'Find contacts without calling',
  'send_sms': 'Send SMS after confirmation',
  'add_contact': 'Save a contact to the phone',
  'dial_contact': 'Search contacts and call',
  'dial_number': 'Call a phone number directly',
  'open_maps': 'Open Google Maps with an address',
  'play_youtube': 'Play a YouTube video (auto-closes after time limit)',
  'ask_expert': 'Consult a stronger model for hard questions',
  'ask_fable': 'Ask Claude Fable with live web search (Anthropic key)',
  'schedule_task': 'Schedule tasks for future execution',
  'list_scheduled_tasks': 'Show active scheduled tasks',
  'cancel_scheduled_task': 'Cancel scheduled tasks',
  'update_scheduled_task': 'Edit scheduled tasks',
  'take_photo': 'Capture a camera photo',
  'use_skill': 'Load skill instructions',
  'google_search': 'Real-time web search (uses quota)',
};

const agentLabels = <String, String>{
  'call': 'PocketDaemon',
  'trusted': 'Trusted Caller Agent',
  'chat': 'Chat Agent',
};

const agentIcons = <String, IconData>{
  'call': Icons.phone_outlined,
  'trusted': Icons.verified_user_outlined,
  'chat': Icons.chat_outlined,
};

const alwaysOnTools = {'hangUp'};

class AgentToolsPage extends StatefulWidget {
  final MethodChannel control;
  const AgentToolsPage({super.key, required this.control});
  @override
  State<AgentToolsPage> createState() => _AgentToolsPageState();
}

class _AgentToolsPageState extends State<AgentToolsPage> {
  Map<String, Map<String, bool>> _config = {};
  bool _loaded = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final raw = await widget.control.invokeMethod('getAgentToolConfig');
      if (raw is Map) {
        final parsed = <String, Map<String, bool>>{};
        for (final entry in raw.entries) {
          final agent = entry.key.toString();
          if (entry.value is Map) {
            parsed[agent] = (entry.value as Map).map(
              (k, v) => MapEntry(k.toString(), v == true),
            );
          }
        }
        setState(() {
          _config = parsed;
          _loaded = true;
        });
      }
    } catch (_) {}
  }

  Future<void> _toggle(String agent, String tool, bool enabled) async {
    HapticFeedback.selectionClick();
    await widget.control.invokeMethod('setToolEnabled', {
      'agentType': agent,
      'toolName': tool,
      'enabled': enabled,
    });
    setState(() {
      _config[agent] = Map.from(_config[agent] ?? {})..[tool] = enabled;
    });
  }

  @override
  Widget build(BuildContext context) {
    return settingsScaffold(
      'Agent Tools',
      !_loaded
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(20),
              children: [
                for (final agent in ['call', 'trusted', 'chat']) ...[
                  _buildAgentSection(context, agent),
                  const SizedBox(height: 14),
                ],
              ],
            ),
    );
  }

  Widget _buildAgentSection(BuildContext context, String agent) {
    final tools = _config[agent] ?? {};
    return sectionCard(
      context,
      title: agentLabels[agent] ?? agent,
      child: Column(
        children: [
          for (final entry in tools.entries)
            _buildToolRow(agent, entry.key, entry.value),
        ],
      ),
    );
  }

  Widget _buildToolRow(String agent, String tool, bool enabled) {
    final locked = alwaysOnTools.contains(tool);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Icon(
            tool == 'google_search'
                ? Icons.travel_explore_rounded
                : agentIcons[agent] ?? Icons.extension_rounded,
            size: 18,
            color: locked
                ? PremiumTokens.textMuted
                : enabled
                ? PremiumTokens.accentPrimary
                : PremiumTokens.textMuted,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  toolLabels[tool] ?? tool,
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w500,
                    color: locked
                        ? PremiumTokens.textMuted
                        : PremiumTokens.textSecondary,
                  ),
                ),
                Text(
                  toolDescriptions[tool] ?? '',
                  style: const TextStyle(
                    fontSize: 11,
                    color: PremiumTokens.textMuted,
                  ),
                ),
              ],
            ),
          ),
          Switch(
            value: enabled,
            onChanged: locked ? null : (v) => _toggle(agent, tool, v),
          ),
        ],
      ),
    );
  }
}
