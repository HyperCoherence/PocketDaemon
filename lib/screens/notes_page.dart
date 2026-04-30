import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../theme/tokens.dart';
import '../widgets/glass_card.dart';
import '../utils.dart';

class NotesPage extends StatelessWidget {
  final List<Map<String, dynamic>> notes;
  final ValueChanged<String> onDismissNote;

  const NotesPage({
    super.key,
    required this.notes,
    required this.onDismissNote,
  });

  @override
  Widget build(BuildContext context) {
    return CustomScrollView(
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
          sliver: SliverToBoxAdapter(
            child: Row(
              children: [
                Text(
                  'Notes',
                  style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                    fontFamily: 'Syne',
                    fontWeight: FontWeight.w700,
                    letterSpacing: -0.5,
                    color: PremiumTokens.textPrimary,
                  ),
                ),
                const SizedBox(width: 8),
                if (notes.isNotEmpty)
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 2,
                    ),
                    decoration: BoxDecoration(
                      color: PremiumTokens.accentPrimary.withAlpha(20),
                      borderRadius: BorderRadius.circular(
                        PremiumTokens.radiusMd,
                      ),
                    ),
                    child: Text(
                      '${notes.length}',
                      style: const TextStyle(
                        fontSize: 13,
                        color: PremiumTokens.accentPrimary,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ),
        const SliverPadding(padding: EdgeInsets.only(top: 12)),
        if (notes.isEmpty)
          SliverFillRemaining(
            hasScrollBody: false,
            child: Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    Icons.sticky_note_2_outlined,
                    size: 36,
                    color: PremiumTokens.textMuted.withAlpha(60),
                  ),
                  const SizedBox(height: 10),
                  const Text(
                    'No notes yet',
                    style: TextStyle(
                      fontSize: 15,
                      color: PremiumTokens.textMuted,
                    ),
                  ),
                ],
              ),
            ),
          )
        else
          SliverPadding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            sliver: SliverList(
              delegate: SliverChildBuilderDelegate(
                (_, i) => _buildNoteCard(context, notes[i]),
                childCount: notes.length,
              ),
            ),
          ),
        const SliverPadding(padding: EdgeInsets.only(bottom: 16)),
      ],
    );
  }

  Widget _buildNoteCard(BuildContext context, Map<String, dynamic> n) {
    final id = n['id']?.toString() ?? '';
    final isChat = n['source'] == 'chat';
    final accentColor = isChat
        ? PremiumTokens.accentSecondary
        : PremiumTokens.accentPrimary;

    return Dismissible(
      key: ValueKey(id),
      direction: DismissDirection.endToStart,
      background: Container(
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: 20),
        margin: const EdgeInsets.only(bottom: 10),
        decoration: BoxDecoration(
          color: PremiumTokens.error.withAlpha(30),
          borderRadius: BorderRadius.circular(PremiumTokens.radiusLg),
        ),
        child: const Icon(
          Icons.delete_outline_rounded,
          color: PremiumTokens.error,
          size: 24,
        ),
      ),
      onDismissed: (_) => onDismissNote(id),
      child: GestureDetector(
        onTap: () {
          HapticFeedback.selectionClick();
          Navigator.push(
            context,
            slideRoute(NoteDetailPage(note: n, onDismiss: onDismissNote)),
          );
        },
        child: Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: GlassCard(
            padding: EdgeInsets.zero,
            child: IntrinsicHeight(
              child: Row(
                children: [
                  Container(
                    width: 4,
                    decoration: BoxDecoration(
                      color: accentColor,
                      borderRadius: const BorderRadius.only(
                        topLeft: Radius.circular(PremiumTokens.radiusLg),
                        bottomLeft: Radius.circular(PremiumTokens.radiusLg),
                      ),
                    ),
                  ),
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.all(14),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Icon(
                                isChat
                                    ? Icons.chat_bubble_outline_rounded
                                    : Icons.phone_outlined,
                                size: 15,
                                color: PremiumTokens.textMuted,
                              ),
                              const SizedBox(width: 6),
                              Text(
                                n['source'] ?? '',
                                style: const TextStyle(
                                  fontWeight: FontWeight.w600,
                                  fontSize: 14,
                                  color: PremiumTokens.textTertiary,
                                ),
                              ),
                              const Spacer(),
                              Text(
                                n['date'] ?? '',
                                style: const TextStyle(
                                  fontSize: 13,
                                  color: PremiumTokens.textMuted,
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 10),
                          Text(
                            (n['text'] ?? '').toString(),
                            style: const TextStyle(
                              fontSize: 15,
                              color: PremiumTokens.textSecondary,
                              height: 1.45,
                            ),
                            maxLines: 4,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class NoteDetailPage extends StatelessWidget {
  final Map<String, dynamic> note;
  final ValueChanged<String> onDismiss;

  const NoteDetailPage({
    super.key,
    required this.note,
    required this.onDismiss,
  });

  @override
  Widget build(BuildContext context) {
    final isChat = note['source'] == 'chat';
    final accentColor = isChat
        ? PremiumTokens.accentSecondary
        : PremiumTokens.accentPrimary;
    final text = (note['text'] ?? '').toString();
    final source = (note['source'] ?? '').toString();
    final date = (note['date'] ?? '').toString();
    final id = note['id']?.toString() ?? '';

    return Scaffold(
      backgroundColor: Colors.transparent,
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(8, 8, 8, 0),
              child: Row(
                children: [
                  IconButton(
                    icon: const Icon(Icons.arrow_back_rounded, size: 22),
                    onPressed: () => Navigator.pop(context),
                    style: IconButton.styleFrom(
                      foregroundColor: PremiumTokens.textTertiary,
                    ),
                  ),
                  const Spacer(),
                  IconButton(
                    icon: Icon(
                      Icons.delete_outline_rounded,
                      size: 21,
                      color: PremiumTokens.error.withAlpha(180),
                    ),
                    onPressed: () {
                      HapticFeedback.lightImpact();
                      onDismiss(id);
                      Navigator.pop(context);
                    },
                    style: IconButton.styleFrom(
                      backgroundColor: PremiumTokens.error.withAlpha(15),
                    ),
                  ),
                ],
              ),
            ),
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(28, 12, 28, 48),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Container(
                          padding: const EdgeInsets.all(11),
                          decoration: BoxDecoration(
                            color: accentColor.withAlpha(18),
                            borderRadius: BorderRadius.circular(
                              PremiumTokens.radiusLg,
                            ),
                          ),
                          child: Icon(
                            isChat
                                ? Icons.chat_bubble_outline_rounded
                                : Icons.phone_outlined,
                            size: 22,
                            color: accentColor,
                          ),
                        ),
                        const SizedBox(width: 16),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                source,
                                style: const TextStyle(
                                  fontFamily: 'Syne',
                                  fontSize: 16,
                                  fontWeight: FontWeight.w600,
                                  color: PremiumTokens.textPrimary,
                                ),
                              ),
                              const SizedBox(height: 3),
                              Text(
                                date,
                                style: const TextStyle(
                                  fontSize: 13,
                                  color: PremiumTokens.textMuted,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 22),
                      child: Container(
                        width: double.infinity,
                        height: 1,
                        decoration: BoxDecoration(
                          gradient: LinearGradient(
                            colors: [
                              accentColor.withAlpha(60),
                              PremiumTokens.borderGlass,
                              Colors.transparent,
                            ],
                          ),
                        ),
                      ),
                    ),
                    SelectableText(
                      text,
                      style: const TextStyle(
                        fontSize: 18,
                        height: 1.75,
                        color: PremiumTokens.textSecondary,
                        letterSpacing: 0.15,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
