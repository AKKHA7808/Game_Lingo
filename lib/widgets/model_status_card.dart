import 'package:flutter/material.dart';
import '../models/translation_model_status.dart';

/// Displays on-device ML Kit translation model status and download actions.
class ModelStatusCard extends StatelessWidget {
  final TranslationModelStatus modelStatus;
  final TargetLanguage selectedLanguage;
  final bool allowCellularDownload;
  final ValueChanged<bool> onAllowCellularChanged;
  final ValueChanged<TargetLanguage> onDownloadModel;

  const ModelStatusCard({
    super.key,
    required this.modelStatus,
    required this.selectedLanguage,
    required this.allowCellularDownload,
    required this.onAllowCellularChanged,
    required this.onDownloadModel,
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
                  Icons.offline_bolt_rounded,
                  color: Color(0xFFA78BFA),
                  size: 22,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'On-Device Translation Models',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            const Text(
              'โมเดลแปลภาษาทำงานแบบ On-Device 100% ไม่ส่งข้อมูลออกนอกเครื่อง แนะนำให้ดาวน์โหลดผ่าน Wi-Fi',
              style: TextStyle(
                fontSize: 12.5,
                color: Color(0xFF94A3B8),
              ),
            ),
            const SizedBox(height: 14),
            _ModelItemTile(
              language: TargetLanguage.thai,
              state: modelStatus.jaToThState,
              isSelectedTarget: selectedLanguage == TargetLanguage.thai,
              onDownload: () => onDownloadModel(TargetLanguage.thai),
            ),
            const SizedBox(height: 10),
            _ModelItemTile(
              language: TargetLanguage.english,
              state: modelStatus.jaToEnState,
              isSelectedTarget: selectedLanguage == TargetLanguage.english,
              onDownload: () => onDownloadModel(TargetLanguage.english),
            ),
            const SizedBox(height: 12),
            SwitchListTile.adaptive(
              contentPadding: EdgeInsets.zero,
              dense: true,
              title: const Text(
                'อนุญาตให้ดาวน์โหลดโมเดลผ่านเครือข่ายมือถือ (Cellular Data)',
                style: TextStyle(fontSize: 13, fontWeight: FontWeight.w500),
              ),
              subtitle: Text(
                allowCellularDownload
                    ? 'ดาวน์โหลดได้ทั้ง Wi-Fi และเครือข่ายมือถือ'
                    : 'ค่าเริ่มต้น: ดาวน์โหลดเฉพาะเมื่อเชื่อมต่อ Wi-Fi เท่านั้น',
                style: const TextStyle(fontSize: 11.5, color: Color(0xFF94A3B8)),
              ),
              value: allowCellularDownload,
              onChanged: onAllowCellularChanged,
            ),
            const Divider(height: 18),
            const Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                Icon(
                  Icons.translate_rounded,
                  size: 13,
                  color: Color(0xFF94A3B8),
                ),
                SizedBox(width: 5),
                Text(
                  'Translated by Google (ML Kit On-Device)',
                  style: TextStyle(
                    fontSize: 11,
                    fontStyle: FontStyle.italic,
                    color: Color(0xFF94A3B8),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _ModelItemTile extends StatelessWidget {
  final TargetLanguage language;
  final ModelDownloadState state;
  final bool isSelectedTarget;
  final VoidCallback onDownload;

  const _ModelItemTile({
    required this.language,
    required this.state,
    required this.isSelectedTarget,
    required this.onDownload,
  });

  @override
  Widget build(BuildContext context) {
    final (badgeColor, badgeIcon) = switch (state) {
      ModelDownloadState.downloaded => (
        const Color(0xFF34D399),
        Icons.check_circle_rounded,
      ),
      ModelDownloadState.downloading => (
        const Color(0xFF38BDF8),
        Icons.downloading_rounded,
      ),
      ModelDownloadState.failed => (
        const Color(0xFFF87171),
        Icons.error_outline_rounded,
      ),
      ModelDownloadState.notDownloaded => (
        const Color(0xFF94A3B8),
        Icons.cloud_download_outlined,
      ),
    };

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: isSelectedTarget
            ? const Color(0xFF6366F1).withValues(alpha: 0.12)
            : const Color(0xFF0F172A).withValues(alpha: 0.6),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: isSelectedTarget
              ? const Color(0xFF6366F1).withValues(alpha: 0.5)
              : const Color(0xFF334155),
        ),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text(
                      language.modelTitle,
                      style: const TextStyle(
                        fontWeight: FontWeight.w600,
                        fontSize: 13.5,
                      ),
                    ),
                    if (isSelectedTarget) ...[
                      const SizedBox(width: 8),
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 7,
                          vertical: 2,
                        ),
                        decoration: BoxDecoration(
                          color: const Color(0xFF6366F1).withValues(alpha: 0.25),
                          borderRadius: BorderRadius.circular(6),
                        ),
                        child: const Text(
                          'Active Target',
                          style: TextStyle(
                            fontSize: 10.5,
                            fontWeight: FontWeight.w600,
                            color: Color(0xFFC7D2FE),
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
                const SizedBox(height: 5),
                Row(
                  children: [
                    Icon(badgeIcon, size: 14, color: badgeColor),
                    const SizedBox(width: 5),
                    Text(
                      state.displayLabel,
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: badgeColor,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          if (state == ModelDownloadState.downloading)
            const SizedBox(
              width: 22,
              height: 22,
              child: CircularProgressIndicator(strokeWidth: 2.4),
            )
          else if (state != ModelDownloadState.downloaded)
            FilledButton.tonalIcon(
              onPressed: onDownload,
              icon: const Icon(Icons.download_rounded, size: 17),
              label: Text(
                state == ModelDownloadState.failed ? 'Retry' : 'Download',
              ),
            ),
        ],
      ),
    );
  }
}

