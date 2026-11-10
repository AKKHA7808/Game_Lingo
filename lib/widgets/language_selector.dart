import 'package:flutter/material.dart';
import '../models/translation_model_status.dart';

/// Segmented control allowing the user to select Japanese -> Thai or Japanese -> English.
class LanguageSelector extends StatelessWidget {
  final TargetLanguage selectedLanguage;
  final ValueChanged<TargetLanguage> onLanguageChanged;

  const LanguageSelector({
    super.key,
    required this.selectedLanguage,
    required this.onLanguageChanged,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(
                  Icons.language_rounded,
                  color: Color(0xFF38BDF8),
                  size: 22,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'Target Translation Language',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 4,
                  ),
                  decoration: BoxDecoration(
                    color: const Color(0xFF6366F1).withValues(alpha: 0.18),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(
                    selectedLanguage.pairLabel,
                    style: const TextStyle(
                      fontSize: 11.5,
                      fontWeight: FontWeight.w600,
                      color: Color(0xFFA5B4FC),
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            const Text(
              'เลือกภาษาปลายทางสำหรับการแปลข้อความภาษาญี่ปุ่นจากหน้าจอเกม (บันทึกอัตโนมัติ)',
              style: TextStyle(
                fontSize: 12.5,
                color: Color(0xFF94A3B8),
              ),
            ),
            const SizedBox(height: 14),
            SizedBox(
              width: double.infinity,
              child: SegmentedButton<TargetLanguage>(
                segments: const [
                  ButtonSegment<TargetLanguage>(
                    value: TargetLanguage.thai,
                    icon: Icon(Icons.translate_rounded, size: 18),
                    label: Text('Japanese → Thai'),
                  ),
                  ButtonSegment<TargetLanguage>(
                    value: TargetLanguage.english,
                    icon: Icon(Icons.g_translate_rounded, size: 18),
                    label: Text('Japanese → English'),
                  ),
                ],
                selected: {selectedLanguage},
                onSelectionChanged: (selection) {
                  if (selection.isNotEmpty) {
                    onLanguageChanged(selection.first);
                  }
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

