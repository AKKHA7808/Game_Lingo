import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/translation_model_status.dart';
import '../providers/app_state_provider.dart';
import '../widgets/language_selector.dart';
import '../widgets/model_status_card.dart';

/// Settings screen for language preferences, model downloads, and privacy info.
class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final appState = context.watch<AppStateProvider>();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Settings & Translation Models'),
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          LanguageSelector(
            selectedLanguage: appState.targetLanguage,
            onLanguageChanged: appState.setTargetLanguage,
          ),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
              child: Row(
                children: [
                  const Icon(
                    Icons.swap_horiz_rounded,
                    color: Color(0xFF818CF8),
                  ),
                  const SizedBox(width: 12),
                  const Expanded(
                    child: Text(
                      'เลือกคู่ภาษาแบบ Dropdown',
                      style: TextStyle(
                        fontWeight: FontWeight.w600,
                        fontSize: 13.5,
                      ),
                    ),
                  ),
                  DropdownButton<TargetLanguage>(
                    value: appState.targetLanguage,
                    underline: const SizedBox.shrink(),
                    borderRadius: BorderRadius.circular(12),
                    items: TargetLanguage.values.map((lang) {
                      return DropdownMenuItem<TargetLanguage>(
                        value: lang,
                        child: Text(lang.pairLabel),
                      );
                    }).toList(),
                    onChanged: (lang) {
                      if (lang != null) {
                        appState.setTargetLanguage(lang);
                      }
                    },
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          ModelStatusCard(
            modelStatus: appState.modelStatus,
            selectedLanguage: appState.targetLanguage,
            allowCellularDownload: appState.allowCellularDownload,
            onAllowCellularChanged: appState.setAllowCellularDownload,
            onDownloadModel: appState.downloadModel,
          ),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(18),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Icon(
                        Icons.privacy_tip_outlined,
                        color: Color(0xFF34D399),
                        size: 20,
                      ),
                      const SizedBox(width: 10),
                      Text(
                        'Performance & Privacy Guarantee',
                        style: Theme.of(context).textTheme.titleSmall?.copyWith(
                              fontWeight: FontWeight.w700,
                            ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  const Text(
                    '• ประมวลผลเฉพาะเมื่อแตะปุ่ม Floating Bubble เท่านั้น (ไม่รัน OCR ทุกเฟรม)\n'
                    '• ใช้ Google ML Kit Text Recognition v2 (Japanese) และ On-Device Translation\n'
                    '• ไม่บันทึกภาพหน้าจอเกมลงพื้นที่เก็บข้อมูลถาวรและไม่ส่งภาพขึ้นเซิร์ฟเวอร์',
                    style: TextStyle(
                      fontSize: 12.5,
                      color: Color(0xFFCBD5E1),
                      height: 1.5,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

