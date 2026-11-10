import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import '../providers/app_state_provider.dart';
import '../widgets/app_header.dart';
import '../widgets/language_selector.dart';
import '../widgets/model_status_card.dart';
import '../widgets/permission_status_card.dart';
import 'permission_screen.dart';
import 'settings_screen.dart';

/// Main dashboard screen of GameLingo.
class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final appState = context.watch<AppStateProvider>();

    return Scaffold(
      appBar: AppBar(
        title: const Row(
          children: [
            Icon(Icons.translate_rounded, color: Color(0xFF818CF8), size: 22),
            SizedBox(width: 8),
            Text(
              'GameLingo',
              style: TextStyle(fontWeight: FontWeight.w700),
            ),
          ],
        ),
        actions: [
          IconButton(
            onPressed: () {
              Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => const PermissionScreen(),
                ),
              );
            },
            icon: const Icon(Icons.verified_user_outlined),
            tooltip: 'Permission Setup',
          ),
          IconButton(
            onPressed: () {
              Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => const SettingsScreen(),
                ),
              );
            },
            icon: const Icon(Icons.settings_outlined),
            tooltip: 'Settings',
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: appState.refreshAllStatuses,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            AppHeader(
              isBubbleActive: appState.isBubbleRunning,
              isCaptureActive: appState.isScreenCaptureActive,
            ),
            if (appState.errorMessage != null) ...[
              const SizedBox(height: 12),
              _BannerAlert(
                message: appState.errorMessage!,
                isError: true,
                onDismiss: appState.clearMessages,
              ),
            ],
            if (appState.statusMessage != null &&
                appState.errorMessage == null) ...[
              const SizedBox(height: 12),
              _BannerAlert(
                message: appState.statusMessage!,
                isError: false,
                onDismiss: appState.clearMessages,
              ),
            ],
            const SizedBox(height: 14),
            _BubbleControlSection(appState: appState),
            if (appState.lastOcrText != null &&
                appState.lastOcrText!.isNotEmpty) ...[
              const SizedBox(height: 14),
              _JapaneseOcrResultCard(
                japaneseText: appState.lastOcrText!,
                translatedText: appState.lastTranslationResult?.translatedText,
                targetLanguageLabel: appState.targetLanguage.pairLabel,
                metadata: appState.lastOcrMetadata,
              ),
            ],
            if (appState.lastCaptureMetadata != null) ...[
              const SizedBox(height: 14),
              _CaptureVerificationCard(metadata: appState.lastCaptureMetadata!),
            ],
            const SizedBox(height: 14),
            LanguageSelector(
              selectedLanguage: appState.targetLanguage,
              onLanguageChanged: appState.setTargetLanguage,
            ),
            const SizedBox(height: 14),
            PermissionStatusCard(
              permissionStatus: appState.permissionStatus,
              isScreenCaptureActive: appState.isScreenCaptureActive,
              onRefreshPermissions: appState.checkPermissions,
              onOpenPermissionSetup: () {
                Navigator.of(context).push(
                  MaterialPageRoute<void>(
                    builder: (_) => const PermissionScreen(),
                  ),
                );
              },
            ),
            const SizedBox(height: 14),
            ModelStatusCard(
              modelStatus: appState.modelStatus,
              selectedLanguage: appState.targetLanguage,
              allowCellularDownload: appState.allowCellularDownload,
              onAllowCellularChanged: appState.setAllowCellularDownload,
              onDownloadModel: appState.downloadModel,
            ),
            const SizedBox(height: 24),
          ],
        ),
      ),
    );
  }
}

class _BubbleControlSection extends StatelessWidget {
  final AppStateProvider appState;

  const _BubbleControlSection({required this.appState});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final activeLangModelState =
        appState.modelStatus.stateForLanguage(appState.targetLanguage);

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(
                  Icons.bubble_chart_rounded,
                  color: Color(0xFF818CF8),
                  size: 22,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'Floating Game Translator Controls',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _InfoChip(
                  label: 'Target',
                  value: appState.targetLanguage.pairLabel,
                ),
                _InfoChip(
                  label: 'Overlay',
                  value: appState.permissionStatus.overlayPermission.displayLabel,
                ),
                _InfoChip(
                  label: 'Bubble State',
                  value: appState.isBubbleRunning
                      ? appState.bubbleVisualState.toUpperCase()
                      : 'STOPPED',
                ),
                _InfoChip(
                  label: 'Screen Capture',
                  value: appState.isScreenCaptureActive ? 'Active' : 'Standby',
                ),
                _InfoChip(
                  label: 'Model',
                  value: activeLangModelState.displayLabel,
                ),
              ],
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: FilledButton.icon(
                    onPressed: appState.isBubbleRunning
                        ? null
                        : appState.startFloatingBubble,
                    icon: const Icon(Icons.play_arrow_rounded),
                    label: const Text('Start Floating Bubble'),
                    style: FilledButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 14),
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: appState.isBubbleRunning
                        ? appState.stopFloatingBubble
                        : null,
                    icon: const Icon(Icons.stop_rounded),
                    label: const Text('Stop Floating Bubble'),
                    style: OutlinedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 14),
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: appState.isScreenCaptureActive
                        ? appState.stopScreenCapture
                        : appState.startScreenCapture,
                    icon: Icon(
                      appState.isScreenCaptureActive
                          ? Icons.stop_screen_share_rounded
                          : Icons.screenshot_monitor_rounded,
                      size: 18,
                    ),
                    label: Text(
                      appState.isScreenCaptureActive
                          ? 'Stop Screen Capture'
                          : 'Start Screen Capture',
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: FilledButton.tonalIcon(
                    onPressed: appState.isScreenCaptureActive &&
                            !appState.isProcessing
                        ? appState.testSingleFrameCapture
                        : null,
                    icon: const Icon(Icons.document_scanner_outlined, size: 18),
                    label: const Text('Scan Japanese OCR'),
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

class _JapaneseOcrResultCard extends StatelessWidget {
  final String japaneseText;
  final String? translatedText;
  final String targetLanguageLabel;
  final Map<String, dynamic>? metadata;

  const _JapaneseOcrResultCard({
    required this.japaneseText,
    this.translatedText,
    required this.targetLanguageLabel,
    this.metadata,
  });

  @override
  Widget build(BuildContext context) {
    final lineCount = metadata?['lineCount'] ?? japaneseText.split('\n').length;
    final jpCharCount = metadata?['japaneseCharCount'] ?? japaneseText.length;
    final timeMs = metadata?['processingTimeMs'] ?? 0;
    final hasTranslation =
        translatedText != null && translatedText!.trim().isNotEmpty;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(
                  Icons.text_fields_rounded,
                  color: Color(0xFF38BDF8),
                  size: 20,
                ),
                const SizedBox(width: 8),
                const Expanded(
                  child: Text(
                    'OCR & On-Device Translation Result',
                    style: TextStyle(
                      fontWeight: FontWeight.w700,
                      fontSize: 14,
                    ),
                  ),
                ),
                IconButton(
                  onPressed: () {
                    final copyPayload = hasTranslation
                        ? '$japaneseText\n\n$translatedText'
                        : japaneseText;
                    Clipboard.setData(ClipboardData(text: copyPayload));
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(
                        content: Text('คัดลอกข้อความแล้ว'),
                        duration: Duration(seconds: 2),
                      ),
                    );
                  },
                  icon: const Icon(Icons.copy_rounded, size: 18),
                  tooltip: 'คัดลอกข้อความ',
                  visualDensity: VisualDensity.compact,
                ),
              ],
            ),
            const SizedBox(height: 8),
            const Text(
              'Japanese (OCR):',
              style: TextStyle(
                fontSize: 11.5,
                fontWeight: FontWeight.w600,
                color: Color(0xFF94A3B8),
              ),
            ),
            const SizedBox(height: 4),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: const Color(0xFF0F172A),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: const Color(0xFF334155)),
              ),
              child: SelectableText(
                japaneseText,
                style: const TextStyle(
                  fontSize: 14.5,
                  color: Colors.white,
                  height: 1.5,
                ),
              ),
            ),
            if (hasTranslation) ...[
              const SizedBox(height: 10),
              Text(
                'Translation ($targetLanguageLabel):',
                style: const TextStyle(
                  fontSize: 11.5,
                  fontWeight: FontWeight.w600,
                  color: Color(0xFFA5B4FC),
                ),
              ),
              const SizedBox(height: 4),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: const Color(0xFF1E1B4B).withValues(alpha: 0.65),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(
                    color: const Color(0xFF6366F1).withValues(alpha: 0.45),
                  ),
                ),
                child: SelectableText(
                  translatedText!,
                  style: const TextStyle(
                    fontSize: 14.5,
                    color: Color(0xFFE0E7FF),
                    height: 1.5,
                  ),
                ),
              ),
              const SizedBox(height: 6),
              const Align(
                alignment: Alignment.centerRight,
                child: Text(
                  'Translated by Google',
                  style: TextStyle(
                    fontSize: 11,
                    fontStyle: FontStyle.italic,
                    color: Color(0xFF94A3B8),
                  ),
                ),
              ),
            ],
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _InfoChip(label: 'Lines', value: '$lineCount'),
                _InfoChip(label: 'JP Chars', value: '$jpCharCount'),
                if (timeMs != 0) _InfoChip(label: 'OCR Latency', value: '$timeMs ms'),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _CaptureVerificationCard extends StatelessWidget {
  final Map<String, dynamic> metadata;

  const _CaptureVerificationCard({required this.metadata});

  @override
  Widget build(BuildContext context) {
    final width = metadata['width'] ?? 0;
    final height = metadata['height'] ?? 0;
    final byteCount = (metadata['byteCount'] as int?) ?? 0;
    final kb = (byteCount / 1024).toStringAsFixed(1);
    final pixelHex = (metadata['samplePixelHex'] as String?) ?? 'N/A';
    final count = metadata['captureCountInSession'] ?? 1;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(
                  Icons.fact_check_rounded,
                  color: Color(0xFF34D399),
                  size: 20,
                ),
                SizedBox(width: 8),
                Text(
                  'Last Captured Frame Verification (In-Memory)',
                  style: TextStyle(
                    fontWeight: FontWeight.w700,
                    fontSize: 14,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _InfoChip(label: 'Resolution', value: '${width}x$height px'),
                _InfoChip(label: 'Buffer Size', value: '$kb KB'),
                _InfoChip(label: 'Center Pixel', value: pixelHex),
                _InfoChip(label: 'Session Captures', value: '#$count'),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _InfoChip extends StatelessWidget {
  final String label;
  final String value;

  const _InfoChip({
    required this.label,
    required this.value,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: const Color(0xFF0F172A),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: const Color(0xFF334155)),
      ),
      child: RichText(
        text: TextSpan(
          style: const TextStyle(fontSize: 12),
          children: [
            TextSpan(
              text: '$label: ',
              style: const TextStyle(color: Color(0xFF94A3B8)),
            ),
            TextSpan(
              text: value,
              style: const TextStyle(
                color: Color(0xFFE2E8F0),
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _BannerAlert extends StatelessWidget {
  final String message;
  final bool isError;
  final VoidCallback onDismiss;

  const _BannerAlert({
    required this.message,
    required this.isError,
    required this.onDismiss,
  });

  @override
  Widget build(BuildContext context) {
    final color = isError ? const Color(0xFFF87171) : const Color(0xFF38BDF8);

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.14),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: color.withValues(alpha: 0.45)),
      ),
      child: Row(
        children: [
          Icon(
            isError ? Icons.error_outline_rounded : Icons.info_outline_rounded,
            color: color,
            size: 20,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              message,
              style: TextStyle(
                fontSize: 12.5,
                color: color,
                fontWeight: FontWeight.w500,
              ),
            ),
          ),
          IconButton(
            onPressed: onDismiss,
            icon: Icon(Icons.close_rounded, size: 18, color: color),
            visualDensity: VisualDensity.compact,
          ),
        ],
      ),
    );
  }
}

