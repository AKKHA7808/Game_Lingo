import 'package:flutter/material.dart';
import '../models/permission_status.dart';

/// Card summarizing Overlay, Screen Capture, and Notification permission states.
class PermissionStatusCard extends StatelessWidget {
  final PermissionStatusModel permissionStatus;
  final bool isScreenCaptureActive;
  final VoidCallback onOpenPermissionSetup;
  final VoidCallback onRefreshPermissions;

  const PermissionStatusCard({
    super.key,
    required this.permissionStatus,
    required this.isScreenCaptureActive,
    required this.onOpenPermissionSetup,
    required this.onRefreshPermissions,
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
                  Icons.verified_user_rounded,
                  color: Color(0xFF818CF8),
                  size: 22,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'System & Permission Status',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                IconButton(
                  onPressed: onRefreshPermissions,
                  icon: const Icon(Icons.refresh_rounded, size: 20),
                  tooltip: 'ตรวจสอบสถานะใหม่',
                  visualDensity: VisualDensity.compact,
                ),
              ],
            ),
            const SizedBox(height: 12),
            _PermissionRow(
              icon: Icons.layers_rounded,
              title: 'Display over other apps (Overlay)',
              subtitle: 'สำหรับแสดง Bubble และหน้าต่างแปลทับเกม',
              state: permissionStatus.overlayPermission,
            ),
            const Divider(height: 20),
            _PermissionRow(
              icon: Icons.screenshot_monitor_rounded,
              title: 'Screen Capture (MediaProjection)',
              subtitle: isScreenCaptureActive
                  ? 'เซสชันการจับภาพหน้าจอพร้อมทำงาน'
                  : 'ขออนุญาตเมื่อเริ่มจับภาพหน้าจอเกม',
              state: isScreenCaptureActive
                  ? PermissionGrantState.granted
                  : permissionStatus.screenCapturePermission,
            ),
            const Divider(height: 20),
            _PermissionRow(
              icon: Icons.notifications_active_rounded,
              title: 'Foreground Service Notification',
              subtitle: 'แสดงสถานะการทำงานของ MediaProjection Service',
              state: permissionStatus.notificationPermission,
            ),
            const SizedBox(height: 14),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: onOpenPermissionSetup,
                icon: const Icon(Icons.tune_rounded, size: 18),
                label: const Text('ตั้งค่าสิทธิ์การใช้งาน (Permission Setup)'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _PermissionRow extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final PermissionGrantState state;

  const _PermissionRow({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.state,
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

    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        Icon(icon, size: 20, color: const Color(0xFF94A3B8)),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: const TextStyle(
                  fontWeight: FontWeight.w600,
                  fontSize: 13.5,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                subtitle,
                style: const TextStyle(
                  fontSize: 12,
                  color: Color(0xFF94A3B8),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(width: 8),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
          decoration: BoxDecoration(
            color: badgeColor.withValues(alpha: 0.14),
            borderRadius: BorderRadius.circular(999),
            border: Border.all(color: badgeColor.withValues(alpha: 0.4)),
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
    );
  }
}

