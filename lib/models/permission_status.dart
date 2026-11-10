/// Enum representing the state of an Android permission required by GameLingo.
enum PermissionGrantState {
  granted,
  notGranted,
  actionRequired;

  String get displayLabel {
    switch (this) {
      case PermissionGrantState.granted:
        return 'Granted';
      case PermissionGrantState.notGranted:
        return 'Not Granted';
      case PermissionGrantState.actionRequired:
        return 'Action Required';
    }
  }

  static PermissionGrantState fromString(String? value) {
    switch (value?.toLowerCase()) {
      case 'granted':
        return PermissionGrantState.granted;
      case 'action_required':
      case 'actionrequired':
        return PermissionGrantState.actionRequired;
      case 'not_granted':
      case 'notgranted':
      default:
        return PermissionGrantState.notGranted;
    }
  }
}

/// Holds the current permission status across required Android capabilities.
class PermissionStatusModel {
  final PermissionGrantState overlayPermission;
  final PermissionGrantState screenCapturePermission;
  final PermissionGrantState notificationPermission;

  const PermissionStatusModel({
    this.overlayPermission = PermissionGrantState.notGranted,
    this.screenCapturePermission = PermissionGrantState.actionRequired,
    this.notificationPermission = PermissionGrantState.notGranted,
  });

  bool get isOverlayGranted =>
      overlayPermission == PermissionGrantState.granted;

  bool get isScreenCaptureGranted =>
      screenCapturePermission == PermissionGrantState.granted;

  bool get isNotificationGranted =>
      notificationPermission == PermissionGrantState.granted;

  bool get canStartBubble => isOverlayGranted;

  factory PermissionStatusModel.fromMap(Map<dynamic, dynamic> map) {
    final overlayRaw = map['overlayPermission'];
    final captureRaw = map['screenCapturePermission'];
    final notifRaw = map['notificationPermission'];

    PermissionGrantState parseField(
      dynamic raw, {
      PermissionGrantState fallback = PermissionGrantState.notGranted,
    }) {
      if (raw is bool) {
        return raw
            ? PermissionGrantState.granted
            : PermissionGrantState.notGranted;
      }
      if (raw is String) {
        return PermissionGrantState.fromString(raw);
      }
      return fallback;
    }

    return PermissionStatusModel(
      overlayPermission: parseField(overlayRaw),
      screenCapturePermission: parseField(
        captureRaw,
        fallback: PermissionGrantState.actionRequired,
      ),
      notificationPermission: parseField(notifRaw),
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'overlayPermission': overlayPermission.name,
      'screenCapturePermission': screenCapturePermission.name,
      'notificationPermission': notificationPermission.name,
    };
  }

  PermissionStatusModel copyWith({
    PermissionGrantState? overlayPermission,
    PermissionGrantState? screenCapturePermission,
    PermissionGrantState? notificationPermission,
  }) {
    return PermissionStatusModel(
      overlayPermission: overlayPermission ?? this.overlayPermission,
      screenCapturePermission:
          screenCapturePermission ?? this.screenCapturePermission,
      notificationPermission:
          notificationPermission ?? this.notificationPermission,
    );
  }
}

