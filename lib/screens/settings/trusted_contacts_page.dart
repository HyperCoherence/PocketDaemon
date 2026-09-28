import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../theme/tokens.dart';
import '../../utils.dart';

class TrustedContactsPage extends StatefulWidget {
  final MethodChannel control;
  const TrustedContactsPage({super.key, required this.control});
  @override
  State<TrustedContactsPage> createState() => _TrustedContactsPageState();
}

class _TrustedContactsPageState extends State<TrustedContactsPage> {
  List<Map<String, String>> _contacts = [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final raw = await widget.control.invokeMethod('getTrustedContacts');
      if (raw is List) {
        _contacts = raw.map((e) {
          final m = Map<String, dynamic>.from(e as Map);
          return m.map((k, v) => MapEntry(k, v?.toString() ?? ''));
        }).toList();
        setState(() {});
      }
    } catch (_) {}
  }

  Future<void> _add(Map<String, String> contact) async {
    HapticFeedback.mediumImpact();
    await widget.control.invokeMethod('addTrustedContact', contact);
    await _load();
  }

  Future<void> _remove(String number) async {
    HapticFeedback.lightImpact();
    await widget.control.invokeMethod('removeTrustedContact', {
      'number': number,
    });
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    return settingsScaffold(
      'Trusted Contacts',
      Padding(
        padding: const EdgeInsets.all(20),
        child: sectionCard(
          context,
          title: 'Trusted Contacts',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Trusted callers get a full-capability agent with memory, search, and GPS access.',
                style: TextStyle(fontSize: 12, color: PremiumTokens.textMuted),
              ),
              const SizedBox(height: 12),
              if (_contacts.isEmpty)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 8),
                  child: Text(
                    'No trusted contacts configured.',
                    style: TextStyle(
                      fontSize: 13,
                      color: PremiumTokens.textMuted,
                    ),
                  ),
                )
              else
                ...List.generate(_contacts.length, (i) {
                  final ct = _contacts[i];
                  return Container(
                    margin: const EdgeInsets.only(bottom: 8),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 10,
                    ),
                    decoration: BoxDecoration(
                      color: PremiumTokens.surfaceGlass,
                      borderRadius: BorderRadius.circular(
                        PremiumTokens.radiusMd,
                      ),
                      border: Border.all(
                        color: PremiumTokens.borderGlass,
                        width: 0.5,
                      ),
                    ),
                    child: Row(
                      children: [
                        Container(
                          width: 36,
                          height: 36,
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            color: PremiumTokens.accentPrimary.withAlpha(15),
                          ),
                          child: const Icon(
                            Icons.verified_user_rounded,
                            size: 18,
                            color: PremiumTokens.accentPrimary,
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                ct['name'] ?? ct['number'] ?? '',
                                style: const TextStyle(
                                  fontSize: 14,
                                  fontWeight: FontWeight.w500,
                                  color: PremiumTokens.textPrimary,
                                ),
                              ),
                              if ((ct['relation'] ?? '').isNotEmpty)
                                Text(
                                  ct['relation']!,
                                  style: const TextStyle(
                                    fontSize: 12,
                                    color: PremiumTokens.textTertiary,
                                  ),
                                ),
                              Text(
                                ct['number'] ?? '',
                                style: const TextStyle(
                                  fontSize: 12,
                                  color: PremiumTokens.textMuted,
                                  fontFamily: 'monospace',
                                ),
                              ),
                            ],
                          ),
                        ),
                        IconButton(
                          icon: const Icon(
                            Icons.close_rounded,
                            size: 18,
                            color: PremiumTokens.textMuted,
                          ),
                          onPressed: () => _remove(ct['number'] ?? ''),
                        ),
                      ],
                    ),
                  );
                }),
              const SizedBox(height: 4),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  icon: const Icon(Icons.person_add_rounded, size: 18),
                  label: const Text('Add from Contacts'),
                  onPressed: () => _showContactPicker(context),
                  style: OutlinedButton.styleFrom(
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(
                        PremiumTokens.radiusMd,
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _showContactPicker(BuildContext context) async {
    final picked = await Navigator.push<Map<String, String>>(
      context,
      slideRoute<Map<String, String>>(
        PhoneContactPickerPage(control: widget.control),
      ),
    );
    if (picked == null) return;
    if (!context.mounted) return;
    await _showRelationDialog(
      context,
      picked['name'] ?? '',
      picked['phone'] ?? '',
    );
  }

  Future<void> _showRelationDialog(
    BuildContext ctx,
    String name,
    String phone,
  ) async {
    final relationCtrl = TextEditingController();
    final promptCtrl = TextEditingController();

    final result = await showDialog<bool>(
      context: ctx,
      builder: (dlgCtx) => AlertDialog(
        title: Text(name, style: const TextStyle(fontFamily: 'Syne')),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                phone,
                style: const TextStyle(
                  fontSize: 13,
                  color: PremiumTokens.textMuted,
                  fontFamily: 'monospace',
                ),
              ),
              const SizedBox(height: 16),
              TextField(
                controller: relationCtrl,
                decoration: const InputDecoration(
                  labelText: 'Relation',
                  hintText: 'Family member',
                  isDense: true,
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: promptCtrl,
                maxLines: 2,
                decoration: const InputDecoration(
                  labelText: 'Custom Prompt (optional)',
                  hintText: 'Be warm and personal...',
                  isDense: true,
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dlgCtx, false),
            child: const Text(
              'Cancel',
              style: TextStyle(color: PremiumTokens.textMuted),
            ),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dlgCtx, true),
            style: FilledButton.styleFrom(
              backgroundColor: PremiumTokens.accentPrimary,
              foregroundColor: Colors.white,
            ),
            child: const Text('Add'),
          ),
        ],
      ),
    );

    if (result == true) {
      await _add({
        'number': phone,
        'name': name,
        'relation': relationCtrl.text.trim(),
        'prompt': promptCtrl.text.trim(),
      });
    }
  }
}

class PhoneContactPickerPage extends StatefulWidget {
  final MethodChannel control;
  const PhoneContactPickerPage({super.key, required this.control});
  @override
  State<PhoneContactPickerPage> createState() => _PhoneContactPickerPageState();
}

class _PhoneContactPickerPageState extends State<PhoneContactPickerPage> {
  List<Map<String, String>> _all = [];
  List<Map<String, String>> _filtered = [];
  final _searchCtrl = TextEditingController();
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _loadContacts();
    _searchCtrl.addListener(_applyFilter);
  }

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadContacts() async {
    try {
      final raw = await widget.control.invokeMethod('getPhoneContacts');
      if (raw is List) {
        _all = raw.map((e) {
          final m = Map<String, dynamic>.from(e as Map);
          return <String, String>{
            'name': m['name']?.toString() ?? '',
            'phone': m['phone']?.toString() ?? '',
          };
        }).toList();
        _filtered = List.from(_all);
      }
    } catch (_) {}
    setState(() => _loading = false);
  }

  void _applyFilter() {
    final q = _searchCtrl.text.toLowerCase();
    setState(() {
      if (q.isEmpty) {
        _filtered = List.from(_all);
      } else {
        _filtered = _all
            .where(
              (ct) =>
                  ct['name']!.toLowerCase().contains(q) ||
                  ct['phone']!.contains(q),
            )
            .toList();
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.transparent,
      appBar: AppBar(
        title: const Text(
          'Select Contact',
          style: TextStyle(fontFamily: 'Syne', fontWeight: FontWeight.w600),
        ),
      ),
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
              child: TextField(
                controller: _searchCtrl,
                decoration: InputDecoration(
                  hintText: 'Search contacts...',
                  prefixIcon: const Icon(Icons.search_rounded, size: 20),
                  filled: true,
                  fillColor: PremiumTokens.surfaceGlass,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
                    borderSide: const BorderSide(
                      color: PremiumTokens.borderGlass,
                      width: 0.5,
                    ),
                  ),
                  enabledBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
                    borderSide: const BorderSide(
                      color: PremiumTokens.borderGlass,
                      width: 0.5,
                    ),
                  ),
                  isDense: true,
                  contentPadding: const EdgeInsets.symmetric(vertical: 12),
                ),
              ),
            ),
            Expanded(
              child: _loading
                  ? const Center(child: CircularProgressIndicator())
                  : _filtered.isEmpty
                  ? const Center(
                      child: Text(
                        'No contacts found',
                        style: TextStyle(color: PremiumTokens.textMuted),
                      ),
                    )
                  : ListView.builder(
                      itemCount: _filtered.length,
                      itemBuilder: (_, i) {
                        final ct = _filtered[i];
                        return ListTile(
                          leading: CircleAvatar(
                            backgroundColor: PremiumTokens.accentPrimary
                                .withAlpha(20),
                            child: Text(
                              (ct['name'] ?? '?').isNotEmpty
                                  ? ct['name']![0].toUpperCase()
                                  : '?',
                              style: const TextStyle(
                                color: PremiumTokens.accentPrimary,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                          title: Text(
                            ct['name'] ?? '',
                            style: const TextStyle(
                              fontSize: 14,
                              color: PremiumTokens.textPrimary,
                            ),
                          ),
                          subtitle: Text(
                            ct['phone'] ?? '',
                            style: const TextStyle(
                              fontSize: 12,
                              color: PremiumTokens.textMuted,
                              fontFamily: 'monospace',
                            ),
                          ),
                          onTap: () => Navigator.pop(context, ct),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
    );
  }
}
