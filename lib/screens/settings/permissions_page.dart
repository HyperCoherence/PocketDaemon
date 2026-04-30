import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../theme/tokens.dart';
import '../../utils.dart';

class PermissionsPage extends StatelessWidget {
  final Map<String, bool> permissions;
  final bool allPermsGranted;
  final MethodChannel control;

  const PermissionsPage({
    super.key,
    required this.permissions,
    required this.allPermsGranted,
    required this.control,
  });

  @override
  Widget build(BuildContext context) {
    return settingsScaffold(
      'Permissions',
      Padding(
        padding: const EdgeInsets.all(20),
        child: sectionCard(
          context,
          title: 'App Permissions',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              ...permissions.entries.map(
                (e) => Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Row(
                    children: [
                      Container(
                        width: 20,
                        height: 20,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          color:
                              (e.value
                                      ? PremiumTokens.success
                                      : PremiumTokens.textMuted)
                                  .withAlpha(15),
                        ),
                        child: Icon(
                          e.value ? Icons.check_rounded : Icons.circle_outlined,
                          color: e.value
                              ? PremiumTokens.success
                              : PremiumTokens.textMuted,
                          size: 14,
                        ),
                      ),
                      const SizedBox(width: 10),
                      Text(
                        e.key,
                        style: TextStyle(
                          fontSize: 13,
                          color: e.value
                              ? PremiumTokens.textSecondary
                              : PremiumTokens.textMuted,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              if (!allPermsGranted) ...[
                const SizedBox(height: 12),
                SizedBox(
                  width: double.infinity,
                  height: 44,
                  child: FilledButton(
                    onPressed: () => control.invokeMethod('requestPermissions'),
                    style: FilledButton.styleFrom(
                      backgroundColor: PremiumTokens.accentPrimary,
                      foregroundColor: Colors.white,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(
                          PremiumTokens.radiusMd,
                        ),
                      ),
                    ),
                    child: const Text('Grant Permissions'),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
