import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/permission_status.dart';
import '../providers/app_state_provider.dart';

/// Guides the user through setting up Overlay, Screen Capture, and Notification permissions.
class PermissionScreen extends StatelessWidget {
  const PermissionScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final appState = context.watch<AppStateProvider>();
    final perms = appState.permissionStatus;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Permission Setup'),
        actions: [
          IconButton(
            onPressed: appState.checkPermissions,
            icon: const Icon(Icons.refresh_rounded),
            tooltip: 'ตรวจสอบสถานะอีกครั้ง',
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: const Color(0xFF1E293B),
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: const Color(0xFF334155)),
            ),
            child: const Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  Icons.shield_outlined,
                  color: Color(0xFF818CF8),
                  size: 24,
                ),
                SizedBox(width: 12),
                Expanded(
                  child: Text(
                    'GameLingo ต้องการสิทธิ์เฉพาะที่จำเป็นสำหรับการลอยปุ่ม Bubble เหนือเกมและการจับภาพหน้าจอเมื่อคุณแตะแปลเท่านั้น ภาพและข้อความทั้งหมดประมวลผลภายในเครื่อง (On-Device)',
                    style: TextStyle(
                      fontSize: 13,
                      color: Color(0xFFCBD5E1),
                      height: 1.45,
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _PermissionSetupItemCard(
            stepNumber: '1',
            title: 'Display over other apps',
            permissionName: 'android.permission.SYSTEM_ALERT_WINDOW',
            description:
                'จำเป็นสำหรับแสดง Floating Bubble และหน้าต่างแปลภาษาทับบนหน้าจอเกมแบบ Full Screen',
            state: perms.overlayPermission,
            actionLabel: perms.isOverlayGranted
                ? 'เปิดหน้าตั้งค่า Android Overlay'
                : 'อนุญาตสิทธิ์ในหน้าตั้งค่า Android',
            onActionPressed: appState.requestOverlayPermission,
          ),
          const SizedBox(height: 12),
          _PermissionSetupItemCard(
            stepNumber: '2',
            title: 'Screen Capture / MediaProjection',
            permissionName: 'android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION',
            description:
                'ขออนุญาตจับภาพหน้าจอผ่าน Android System Dialog เมื่อเริ่มเซสชันแปลเกม (บน Android 14+ ระบบจะถามยืนยันต่อเซสชัน)',
            state: appState.isScreenCaptureActive
                ? PermissionGrantState.granted
                : perms.screenCapturePermission,
            actionLabel: appState.isScreenCaptureActive
                ? 'หยุดเซสชันการจับภาพหน้าจอ'
                : 'ขออนุญาตจับภาพหน้าจอ (MediaProjection)',
            onActionPressed: appState.isScreenCaptureActive
                ? appState.stopScreenCapture
                : appState.startScreenCapture,
          ),
          const SizedBox(height: 12),
          _PermissionSetupItemCard(
            stepNumber: '3',
            title: 'Notification Permission',
            permissionName: 'android.permission.POST_NOTIFICATIONS',
            description:
                'จำเป็นบน Android 13+ เพื่อแสดง Notification ของ Foreground Service ขณะที่ Bubble และระบบจับภาพหน้าจอกำลังทำงาน',
            state: perms.notificationPermission,
            actionLabel: 'ตรวจสอบสิทธิ์การแจ้งเตือน',
            onActionPressed: appState.checkPermissions,
          ),
        ],
      ),
    );
  }
}

class _PermissionSetupItemCard extends StatelessWidget {
  final String stepNumber;
  final String title;
  final String permissionName;
  final String description;
  final PermissionGrantState state;
  final String actionLabel;
  final VoidCallback onActionPressed;

  const _PermissionSetupItemCard({
    required this.stepNumber,
    required this.title,
    required this.permissionName,
    required this.description,
    required this.state,
    required this.actionLabel,
    required this.onActionPressed,
  });

  @override
  Widget build(BuildContext context) {
    final (badgeColor, badgeIcon) = switch (state) {
      PermissionGrantState.granted => (
        const Color(0xFF34D399),
        Icons.check_circle_rounded,
      ),
      PermissionGrantState.actionRequired => (
        const Color(0xFFFBBF24),
        Icons.info_rounded,
      ),
      PermissionGrantState.notGranted => (
        const Color(0xFFF87171),
        Icons.warning_amber_rounded,
      ),
    };

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                CircleAvatar(
                  radius: 14,
                  backgroundColor: const Color(0xFF6366F1).withValues(alpha: 0.2),
                  child: Text(
                    stepNumber,
                    style: const TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.bold,
                      color: Color(0xFFA5B4FC),
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    title,
                    style: const TextStyle(
                      fontSize: 15,
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
                    color: badgeColor.withValues(alpha: 0.14),
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(color: badgeColor.withValues(alpha: 0.45)),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(badgeIcon, size: 13, color: badgeColor),
                      const SizedBox(width: 5),
                      Text(
                        state.displayLabel,
                        style: TextStyle(
                          fontSize: 11.5,
                          fontWeight: FontWeight.w600,
                          color: badgeColor,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              permissionName,
              style: const TextStyle(
                fontSize: 11.5,
                fontFamily: 'monospace',
                color: Color(0xFF64748B),
              ),
            ),
            const SizedBox(height: 8),
            Text(
              description,
              style: const TextStyle(
                fontSize: 13,
                color: Color(0xFFCBD5E1),
                height: 1.4,
              ),
            ),
            const SizedBox(height: 14),
            SizedBox(
              width: double.infinity,
              child: FilledButton.tonalIcon(
                onPressed: onActionPressed,
                icon: const Icon(Icons.open_in_new_rounded, size: 17),
                label: Text(actionLabel),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

