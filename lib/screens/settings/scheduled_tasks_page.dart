import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../theme/tokens.dart';
import '../../utils.dart';

class ScheduledTasksPage extends StatefulWidget {
  final MethodChannel control;
  const ScheduledTasksPage({super.key, required this.control});
  @override
  State<ScheduledTasksPage> createState() => _ScheduledTasksPageState();
}

class _ScheduledTasksPageState extends State<ScheduledTasksPage> {
  List<Map<String, dynamic>> _tasks = [];
  bool _loaded = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final raw = await widget.control.invokeMethod('getScheduledTasks');
      if (raw is List) {
        setState(() {
          _tasks = raw.map((e) => Map<String, dynamic>.from(e as Map)).toList();
          _loaded = true;
        });
      }
    } catch (_) {
      setState(() => _loaded = true);
    }
  }

  Future<void> _toggleActive(String id, bool active) async {
    HapticFeedback.selectionClick();
    await widget.control.invokeMethod('setScheduledTaskActive', {
      'id': id,
      'active': active,
    });
    setState(() {
      final idx = _tasks.indexWhere((t) => t['id'] == id);
      if (idx >= 0) _tasks[idx]['active'] = active;
    });
  }

  Future<void> _remove(String id) async {
    HapticFeedback.mediumImpact();
    await widget.control.invokeMethod('removeScheduledTask', {'id': id});
    setState(() => _tasks.removeWhere((t) => t['id'] == id));
  }

  String _formatFireTime(int? ms) {
    if (ms == null || ms == 0) return 'unknown';
    final dt = DateTime.fromMillisecondsSinceEpoch(ms);
    final now = DateTime.now();
    final diff = dt.difference(now);

    if (diff.isNegative) return 'overdue';

    final h = dt.hour.toString().padLeft(2, '0');
    final m = dt.minute.toString().padLeft(2, '0');
    final time = '$h:$m';

    if (diff.inDays == 0 && dt.day == now.day) return 'Today $time';
    if (diff.inDays <= 1 && dt.day == now.day + 1) {
      return 'Tomorrow $time';
    }
    final mon = [
      'Jan',
      'Feb',
      'Mar',
      'Apr',
      'May',
      'Jun',
      'Jul',
      'Aug',
      'Sep',
      'Oct',
      'Nov',
      'Dec',
    ][dt.month - 1];
    return '${dt.day} $mon $time';
  }

  String _formatInterval(int? minutes) {
    if (minutes == null) return '';
    if (minutes < 60) return 'every ${minutes}m';
    if (minutes < 1440) {
      final h = minutes ~/ 60;
      final m = minutes % 60;
      return m > 0 ? 'every ${h}h ${m}m' : 'every ${h}h';
    }
    final d = minutes ~/ 1440;
    return d == 1 ? 'daily' : 'every ${d}d';
  }

  @override
  Widget build(BuildContext context) {
    return settingsScaffold(
      'Scheduled Tasks',
      !_loaded
          ? const Center(child: CircularProgressIndicator())
          : _tasks.isEmpty
          ? Center(
              child: Padding(
                padding: const EdgeInsets.all(32),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      Icons.schedule_rounded,
                      size: 48,
                      color: PremiumTokens.textMuted.withAlpha(60),
                    ),
                    const SizedBox(height: 16),
                    const Text(
                      'No scheduled tasks yet.\nAsk the voice or text agent to schedule one.',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: PremiumTokens.textMuted,
                        fontSize: 14,
                      ),
                    ),
                  ],
                ),
              ),
            )
          : ListView(
              padding: const EdgeInsets.all(20),
              children: [
                _buildGroup(
                  context,
                  'Active',
                  _tasks.where((t) => t['active'] == true).toList(),
                ),
                const SizedBox(height: 14),
                _buildGroup(
                  context,
                  'Inactive',
                  _tasks.where((t) => t['active'] != true).toList(),
                ),
              ],
            ),
    );
  }

  Widget _buildGroup(
    BuildContext context,
    String title,
    List<Map<String, dynamic>> tasks,
  ) {
    if (tasks.isEmpty) return const SizedBox.shrink();
    return sectionCard(
      context,
      title: '$title (${tasks.length})',
      child: Column(
        children: [
          for (int i = 0; i < tasks.length; i++) ...[
            if (i > 0) const Divider(height: 1, color: PremiumTokens.border),
            _buildTaskRow(tasks[i]),
          ],
        ],
      ),
    );
  }

  Widget _buildTaskRow(Map<String, dynamic> task) {
    final active = task['active'] == true;
    final recurring = task['recurring'] == true;
    final nextFire = task['nextFireMs'] as int?;
    final interval = task['intervalMinutes'] as int?;
    final createdBy = task['createdBy']?.toString() ?? '';

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            recurring ? Icons.repeat_rounded : Icons.schedule_rounded,
            size: 18,
            color: active
                ? PremiumTokens.accentPrimary
                : PremiumTokens.textMuted,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  task['description']?.toString() ?? 'Untitled',
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w500,
                    color: active
                        ? PremiumTokens.textPrimary
                        : PremiumTokens.textMuted,
                  ),
                ),
                const SizedBox(height: 3),
                Row(
                  children: [
                    Text(
                      _formatFireTime(nextFire),
                      style: const TextStyle(
                        fontSize: 11,
                        color: PremiumTokens.textMuted,
                      ),
                    ),
                    if (recurring && interval != null) ...[
                      const Text(
                        ' · ',
                        style: TextStyle(
                          fontSize: 11,
                          color: PremiumTokens.textMuted,
                        ),
                      ),
                      Text(
                        _formatInterval(interval),
                        style: const TextStyle(
                          fontSize: 11,
                          color: PremiumTokens.accentSecondary,
                        ),
                      ),
                    ],
                    if (createdBy.isNotEmpty) ...[
                      const Text(
                        ' · ',
                        style: TextStyle(
                          fontSize: 11,
                          color: PremiumTokens.textMuted,
                        ),
                      ),
                      Text(
                        createdBy,
                        style: const TextStyle(
                          fontSize: 11,
                          color: PremiumTokens.textMuted,
                        ),
                      ),
                    ],
                  ],
                ),
              ],
            ),
          ),
          Switch(
            value: active,
            onChanged: (v) => _toggleActive(task['id'], v),
            materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
          ),
          SizedBox(
            width: 32,
            height: 32,
            child: IconButton(
              padding: EdgeInsets.zero,
              iconSize: 18,
              icon: Icon(
                Icons.delete_outline,
                color: PremiumTokens.error.withAlpha(180),
              ),
              onPressed: () => _confirmDelete(task),
            ),
          ),
        ],
      ),
    );
  }

  void _confirmDelete(Map<String, dynamic> task) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Remove task?', style: TextStyle(fontFamily: 'Syne')),
        content: Text(task['description']?.toString() ?? ''),
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
              _remove(task['id']);
            },
            child: const Text(
              'Remove',
              style: TextStyle(color: PremiumTokens.error),
            ),
          ),
        ],
      ),
    );
  }
}
