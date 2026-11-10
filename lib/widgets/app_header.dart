import 'package:flutter/material.dart';

/// Displays the GameLingo brand logo, title, and concise description.
class AppHeader extends StatelessWidget {
  final bool isBubbleActive;
  final bool isCaptureActive;

  const AppHeader({
    super.key,
    required this.isBubbleActive,
    required this.isCaptureActive,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          colors: [
            Color(0xFF1E1B4B),
            Color(0xFF312E81),
            Color(0xFF1E293B),
          ],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: const Color(0xFF6366F1).withValues(alpha: 0.35),
          width: 1.2,
        ),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 58,
            height: 58,
            decoration: BoxDecoration(
              color: const Color(0xFF6366F1).withValues(alpha: 0.2),
              borderRadius: BorderRadius.circular(16),
              border: Border.all(
                color: const Color(0xFF818CF8).withValues(alpha: 0.5),
              ),
            ),
            child: const Stack(
              alignment: Alignment.center,
              children: [
                Icon(
                  Icons.translate_rounded,
                  size: 30,
                  color: Color(0xFFA5B4FC),
                ),
                Positioned(
                  right: 6,
                  bottom: 6,
                  child: Icon(
                    Icons.sports_esports_rounded,
                    size: 15,
                    color: Color(0xFF38BDF8),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text(
                      'GameLingo',
                      style: theme.textTheme.headlineSmall?.copyWith(
                        fontWeight: FontWeight.w800,
                        color: Colors.white,
                        letterSpacing: 0.3,
                      ),
                    ),
                    const SizedBox(width: 10),
                    _StatusBadge(
                      isActive: isBubbleActive,
                      activeText: 'Bubble Active',
                      inactiveText: 'Standby',
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                Text(
                  'สแกนข้อความภาษาญี่ปุ่นจากหน้าจอเกมและแปลเป็นภาษาไทยหรืออังกฤษผ่านหน้าต่างลอยทันทีโดยไม่ต้องออกจากเกม',
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: const Color(0xFFCBD5E1),
                    height: 1.4,
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

class _StatusBadge extends StatelessWidget {
  final bool isActive;
  final String activeText;
  final String inactiveText;

  const _StatusBadge({
    required this.isActive,
    required this.activeText,
    required this.inactiveText,
  });

  @override
  Widget build(BuildContext context) {
    final color = isActive
        ? const Color(0xFF34D399)
        : const Color(0xFF94A3B8);

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.16),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: color.withValues(alpha: 0.45)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 7,
            height: 7,
            decoration: BoxDecoration(
              color: color,
              shape: BoxShape.circle,
            ),
          ),
          const SizedBox(width: 6),
          Text(
            isActive ? activeText : inactiveText,
            style: TextStyle(
              fontSize: 11.5,
              fontWeight: FontWeight.w600,
              color: color,
            ),
          ),
        ],
      ),
    );
  }
}

