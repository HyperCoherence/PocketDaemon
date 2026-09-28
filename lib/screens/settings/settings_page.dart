import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../models.dart';
import '../../theme/tokens.dart';
import '../../utils.dart';
import 'permissions_page.dart';
import 'general_config_page.dart';
import 'agent_tools_page.dart';
import 'scheduled_tasks_page.dart';
import 'trusted_contacts_page.dart';
import 'memory_page.dart';
import 'recordings_page.dart';
import 'recording_library_page.dart';

class SettingsPage extends StatelessWidget {
  final Map<String, bool> permissions;
  final bool allPermsGranted;
  final MethodChannel control;
  final VoidCallback onRefresh;

  const SettingsPage({
    super.key,
    required this.permissions,
    required this.allPermsGranted,
    required this.control,
    required this.onRefresh,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(
                'Settings',
                style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                  fontFamily: 'Syne',
                  fontWeight: FontWeight.w700,
                  letterSpacing: -0.5,
                  color: PremiumTokens.textPrimary,
                ),
              ),
              const Spacer(),
              ClipRRect(
                borderRadius: BorderRadius.circular(PremiumTokens.radiusSm),
                child: BackdropFilter(
                  filter: ImageFilter.blur(sigmaX: 8, sigmaY: 8),
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 2,
                    ),
                    decoration: BoxDecoration(
                      color: PremiumTokens.surfaceGlass,
                      borderRadius: BorderRadius.circular(
                        PremiumTokens.radiusSm,
                      ),
                      border: Border.all(
                        color: PremiumTokens.borderGlass,
                        width: 0.5,
                      ),
                    ),
                    child: Text(
                      'v$kAppVersion',
                      style: const TextStyle(
                        fontSize: 11,
                        color: PremiumTokens.textMuted,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 20),
          Expanded(
            child: ListView(
              children: [
                SettingsRow(
                  icon: Icons.shield_outlined,
                  title: 'Permissions',
                  subtitle: allPermsGranted ? 'All granted' : 'Some missing',
                  accentColor: allPermsGranted
                      ? PremiumTokens.success
                      : PremiumTokens.warning,
                  onTap: () => Navigator.push(
                    context,
                    slideRoute(
                      PermissionsPage(
                        permissions: permissions,
                        allPermsGranted: allPermsGranted,
                        control: control,
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 8),
                SettingsRow(
                  icon: Icons.tune_rounded,
                  title: 'General',
                  subtitle: 'API key, model, prompt, delay',
                  onTap: () => Navigator.push(
                    context,
                    slideRoute(
                      GeneralConfigPage(control: control, onRefresh: onRefresh),
                    ),
                  ),
                ),
                const SizedBox(height: 8),
                SettingsRow(
                  icon: Icons.extension_rounded,
                  title: 'Agent Tools',
                  subtitle: 'Toggle tools per agent',
                  onTap: () => Navigator.push(
                    context,
                    slideRoute(AgentToolsPage(control: control)),
                  ),
                ),
                const SizedBox(height: 8),
                SettingsRow(
                  icon: Icons.schedule_rounded,
                  title: 'Scheduled Tasks',
                  subtitle: 'Manage agent-scheduled tasks',
                  onTap: () => Navigator.push(
                    context,
                    slideRoute(ScheduledTasksPage(control: control)),
                  ),
                ),
                const SizedBox(height: 8),
                SettingsRow(
                  icon: Icons.verified_user_outlined,
                  title: 'Trusted Contacts',
                  subtitle: 'Manage trusted callers',
                  onTap: () => Navigator.push(
                    context,
                    slideRoute(TrustedContactsPage(control: control)),
                  ),
                ),
                const SizedBox(height: 8),
                SettingsRow(
                  icon: Icons.psychology_rounded,
                  title: 'Persistent Memory',
                  subtitle: 'Facts, extraction & session history',
                  onTap: () => Navigator.push(
                    context,
                    slideRoute(MemoryPage(control: control)),
                  ),
                ),
                const SizedBox(height: 8),
                SettingsRow(
                  icon: Icons.fiber_manual_record_rounded,
                  title: 'Recordings',
                  subtitle: 'Agent calls, conversations, phone calls',
                  accentColor: PremiumTokens.error,
                  onTap: () => Navigator.push(
                    context,
                    slideRoute(RecordingsPage(control: control)),
                  ),
                ),
                const SizedBox(height: 8),
                SettingsRow(
                  icon: Icons.library_music_rounded,
                  title: 'Recording Library',
                  subtitle: 'Play, review and transcribe recordings',
                  accentColor: PremiumTokens.accentPrimary,
                  onTap: () => Navigator.push(
                    context,
                    slideRoute(RecordingLibraryPage(control: control)),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class SettingsRow extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final Color accentColor;
  final VoidCallback onTap;

  const SettingsRow({
    super.key,
    required this.icon,
    required this.title,
    required this.subtitle,
    this.accentColor = PremiumTokens.textMuted,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () {
        HapticFeedback.selectionClick();
        onTap();
      },
      child: ClipRRect(
        borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
        child: BackdropFilter(
          filter: ImageFilter.blur(
            sigmaX: PremiumTokens.blurMd,
            sigmaY: PremiumTokens.blurMd,
          ),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [Color(0x14FFFFFF), Color(0x05FFFFFF)],
              ),
              borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
              border: const Border(
                top: BorderSide(
                  color: PremiumTokens.borderGlassTop,
                  width: 0.5,
                ),
                left: BorderSide(color: PremiumTokens.borderGlass, width: 0.5),
                right: BorderSide(color: PremiumTokens.borderGlass, width: 0.5),
                bottom: BorderSide(
                  color: PremiumTokens.borderGlass,
                  width: 0.5,
                ),
              ),
            ),
            child: Row(
              children: [
                Container(
                  width: 38,
                  height: 38,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: accentColor.withAlpha(15),
                  ),
                  child: Icon(icon, size: 18, color: accentColor),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: const TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                          color: PremiumTokens.textPrimary,
                        ),
                      ),
                      const SizedBox(height: 2),
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
                const Icon(
                  Icons.chevron_right_rounded,
                  size: 20,
                  color: PremiumTokens.textMuted,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
