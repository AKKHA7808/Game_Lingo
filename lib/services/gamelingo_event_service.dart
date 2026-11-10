import 'dart:async';
import 'package:flutter/services.dart';

/// Supported event types emitted from Kotlin Native to Flutter.
enum GameLingoEventType {
  permissionStatusChanged,
  bubbleStatusChanged,
  captureStarted,
  captureCompleted,
  ocrStarted,
  ocrCompleted,
  translationStarted,
  translationCompleted,
  translationFailed,
  captureStopped,
  unknown;

  static GameLingoEventType fromString(String? name) {
    switch (name) {
      case 'permissionStatusChanged':
        return GameLingoEventType.permissionStatusChanged;
      case 'bubbleStatusChanged':
        return GameLingoEventType.bubbleStatusChanged;
      case 'captureStarted':
        return GameLingoEventType.captureStarted;
      case 'captureCompleted':
        return GameLingoEventType.captureCompleted;
      case 'ocrStarted':
        return GameLingoEventType.ocrStarted;
      case 'ocrCompleted':
        return GameLingoEventType.ocrCompleted;
      case 'translationStarted':
        return GameLingoEventType.translationStarted;
      case 'translationCompleted':
        return GameLingoEventType.translationCompleted;
      case 'translationFailed':
        return GameLingoEventType.translationFailed;
      case 'captureStopped':
        return GameLingoEventType.captureStopped;
      default:
        return GameLingoEventType.unknown;
    }
  }
}

/// Structured event payload emitted by the native Android layer.
class GameLingoNativeEvent {
  final GameLingoEventType type;
  final Map<String, dynamic> data;
  final String? errorMessage;
  final DateTime timestamp;

  const GameLingoNativeEvent({
    required this.type,
    this.data = const {},
    this.errorMessage,
    required this.timestamp,
  });

  factory GameLingoNativeEvent.fromDynamic(dynamic raw) {
    if (raw is Map) {
      final eventName = raw['event'] as String?;
      final rawData = raw['data'];
      final dataMap = <String, dynamic>{};
      if (rawData is Map) {
        rawData.forEach((key, value) {
          dataMap[key.toString()] = value;
        });
      }
      final rawTs = raw['timestamp'];
      final timestamp = rawTs is int
          ? DateTime.fromMillisecondsSinceEpoch(rawTs)
          : DateTime.now();

      return GameLingoNativeEvent(
        type: GameLingoEventType.fromString(eventName),
        data: dataMap,
        errorMessage: raw['error'] as String?,
        timestamp: timestamp,
      );
    }
    return GameLingoNativeEvent(
      type: GameLingoEventType.unknown,
      timestamp: DateTime.now(),
    );
  }
}

/// Listens to the native EventChannel (`com.gamelingo.app/events`) and broadcasts
/// structured [GameLingoNativeEvent] updates to Flutter subscribers.
class GameLingoEventService {
  static const String eventChannelName = 'com.gamelingo.app/events';

  final EventChannel _eventChannel;
  StreamSubscription<dynamic>? _subscription;
  final StreamController<GameLingoNativeEvent> _controller =
      StreamController<GameLingoNativeEvent>.broadcast();

  GameLingoEventService({EventChannel? eventChannel})
      : _eventChannel =
            eventChannel ?? const EventChannel(eventChannelName);

  Stream<GameLingoNativeEvent> get events => _controller.stream;

  void startListening() {
    if (_subscription != null) return;
    try {
      _subscription = _eventChannel.receiveBroadcastStream().listen(
        (dynamic rawEvent) {
          final parsed = GameLingoNativeEvent.fromDynamic(rawEvent);
          if (!_controller.isClosed) {
            _controller.add(parsed);
          }
        },
        onError: (Object error) {
          if (!_controller.isClosed) {
            _controller.add(
              GameLingoNativeEvent(
                type: GameLingoEventType.translationFailed,
                errorMessage: error.toString(),
                timestamp: DateTime.now(),
              ),
            );
          }
        },
      );
    } on MissingPluginException {
      // Safe fallback when running in widget tests or before native stream is attached.
    }
  }

  Future<void> dispose() async {
    await _subscription?.cancel();
    _subscription = null;
    await _controller.close();
  }
}

