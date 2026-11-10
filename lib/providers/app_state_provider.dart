import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/permission_status.dart';
import '../models/translation_model_status.dart';
import '../models/translation_result.dart';
import '../services/gamelingo_channel.dart';
import '../services/gamelingo_event_service.dart';

/// Central application state provider for GameLingo.
class AppStateProvider extends ChangeNotifier {
  static const String _prefTargetLangKey = 'gamelingo_target_language';
  static const String _prefAllowCellularKey = 'gamelingo_allow_cellular';

  final GameLingoChannel _channel;
  final GameLingoEventService _eventService;
  StreamSubscription<GameLingoNativeEvent>? _eventSub;

  TargetLanguage _targetLanguage = TargetLanguage.thai;
  bool _allowCellularDownload = false;
  PermissionStatusModel _permissionStatus = const PermissionStatusModel();
  TranslationModelStatus _modelStatus = const TranslationModelStatus();

  bool _isBubbleRunning = false;
  String _bubbleVisualState = 'stopped';
  bool _isScreenCaptureActive = false;
  bool _isProcessing = false;
  String? _statusMessage;
  String? _errorMessage;
  TranslationResult? _lastTranslationResult;
  Map<String, dynamic>? _lastCaptureMetadata;
  String? _lastOcrText;
  Map<String, dynamic>? _lastOcrMetadata;

  AppStateProvider({
    GameLingoChannel? channel,
    GameLingoEventService? eventService,
  })  : _channel = channel ?? GameLingoChannel(),
        _eventService = eventService ?? GameLingoEventService();

  TargetLanguage get targetLanguage => _targetLanguage;
  bool get allowCellularDownload => _allowCellularDownload;
  PermissionStatusModel get permissionStatus => _permissionStatus;
  TranslationModelStatus get modelStatus => _modelStatus;
  bool get isBubbleRunning => _isBubbleRunning;
  String get bubbleVisualState => _bubbleVisualState;
  bool get isScreenCaptureActive => _isScreenCaptureActive;
  bool get isProcessing => _isProcessing;
  String? get statusMessage => _statusMessage;
  String? get errorMessage => _errorMessage;
  TranslationResult? get lastTranslationResult => _lastTranslationResult;
  Map<String, dynamic>? get lastCaptureMetadata => _lastCaptureMetadata;
  String? get lastOcrText => _lastOcrText;
  Map<String, dynamic>? get lastOcrMetadata => _lastOcrMetadata;

  /// Initializes saved preferences, starts native event listening, and syncs state.
  Future<void> initialize() async {
    await _loadPreferences();
    _eventService.startListening();
    _eventSub = _eventService.events.listen(_handleNativeEvent);
    await refreshAllStatuses();
  }

  Future<void> _loadPreferences() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final savedLangCode = prefs.getString(_prefTargetLangKey);
      if (savedLangCode != null) {
        _targetLanguage = TargetLanguage.fromCode(savedLangCode);
      }
      _allowCellularDownload = prefs.getBool(_prefAllowCellularKey) ?? false;
      notifyListeners();
    } catch (_) {
      // Fallback to defaults if SharedPreferences fails in test environment.
    }
  }

  /// Refreshes permission and model statuses from the native Android layer.
  Future<void> refreshAllStatuses() async {
    await checkPermissions();
    await refreshModelStatus();
    try {
      await _channel.setTargetLanguage(_targetLanguage);
    } on GameLingoNativeException {
      // Non-fatal during initial sync if native channel is not attached.
    }
  }

  Future<void> checkPermissions() async {
    try {
      final status = await _channel.checkOverlayPermission();
      _permissionStatus = status;
      _isScreenCaptureActive = status.isScreenCaptureGranted;
      _errorMessage = null;
      notifyListeners();
    } on GameLingoNativeException catch (e) {
      _errorMessage = e.message;
      notifyListeners();
    }
  }

  Future<void> requestOverlayPermission() async {
    clearMessages();
    try {
      await _channel.requestOverlayPermission();
      await checkPermissions();
    } on GameLingoNativeException catch (e) {
      _errorMessage = e.message;
      notifyListeners();
    }
  }

  Future<void> setTargetLanguage(TargetLanguage language) async {
    if (_targetLanguage == language) return;
    _targetLanguage = language;
    clearMessages();
    notifyListeners();

    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_prefTargetLangKey, language.code);
    } catch (_) {}

    try {
      await _channel.setTargetLanguage(language);
    } on GameLingoNativeException catch (e) {
      _errorMessage = e.message;
      notifyListeners();
    }
  }

  Future<void> setAllowCellularDownload(bool allow) async {
    _allowCellularDownload = allow;
    notifyListeners();
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool(_prefAllowCellularKey, allow);
    } catch (_) {}
  }

  Future<void> refreshModelStatus() async {
    try {
      final status = await _channel.getTranslationModelStatus();
      _modelStatus = status;
      notifyListeners();
    } on GameLingoNativeException catch (e) {
      _errorMessage = e.message;
      notifyListeners();
    }
  }

  Future<void> downloadModel(TargetLanguage language) async {
    clearMessages();
    _updateSingleModelState(language, ModelDownloadState.downloading);
    try {
      final success = await _channel.downloadTranslationModel(
        language,
        allowCellular: _allowCellularDownload,
      );
      if (success) {
        _updateSingleModelState(language, ModelDownloadState.downloaded);
        _statusMessage = 'ดาวน์โหลด ${language.modelTitle} สำเร็จแล้ว';
        await refreshModelStatus();
      } else {
        _updateSingleModelState(language, ModelDownloadState.failed);
        _errorMessage = 'ไม่สามารถดาวน์โหลด ${language.modelTitle} ได้';
      }
    } on GameLingoNativeException catch (e) {
      _updateSingleModelState(language, ModelDownloadState.failed);
      _errorMessage = e.message;
    }
    notifyListeners();
  }

  void _updateSingleModelState(
    TargetLanguage language,
    ModelDownloadState state,
  ) {
    if (language == TargetLanguage.thai) {
      _modelStatus = _modelStatus.copyWith(
        jaToThState: state,
        clearError: state != ModelDownloadState.failed,
      );
    } else {
      _modelStatus = _modelStatus.copyWith(
        jaToEnState: state,
        clearError: state != ModelDownloadState.failed,
      );
    }
    notifyListeners();
  }

  Future<void> startFloatingBubble() async {
    clearMessages();
    await checkPermissions();
    if (!_permissionStatus.isOverlayGranted) {
      _errorMessage =
          'กรุณาอนุญาตสิทธิ์แสดงผลทับแอปอื่น (Overlay Permission) ก่อนเริ่มใช้งาน Bubble';
      notifyListeners();
      return;
    }

    try {
      final started = await _channel.startFloatingBubble();
      _isBubbleRunning = started;
      if (started) {
        _statusMessage = 'เปิดใช้งาน Floating Bubble แล้ว';
      }
      notifyListeners();
    } on GameLingoNativeException catch (e) {
      _errorMessage = e.message;
      notifyListeners();
    }
  }

  Future<void> stopFloatingBubble() async {
    clearMessages();
    try {
      final stopped = await _channel.stopFloatingBubble();
      if (stopped) {
        _isBubbleRunning = false;
        _statusMessage = 'ปิดการทำงานของ Floating Bubble แล้ว';
      }
      notifyListeners();
    } on GameLingoNativeException catch (e) {
      _errorMessage = e.message;
      notifyListeners();
    }
  }

  Future<void> startScreenCapture() async {
    clearMessages();
    try {
      final started = await _channel.startScreenCapture();
      _isScreenCaptureActive = started;
      if (started) {
        _permissionStatus = _permissionStatus.copyWith(
          screenCapturePermission: PermissionGrantState.granted,
        );
        _statusMessage = 'เชื่อมต่อระบบจับภาพหน้าจอ (MediaProjection) สำเร็จ';
      }
      notifyListeners();
    } on GameLingoNativeException catch (e) {
      _errorMessage = e.message;
      notifyListeners();
    }
  }

  Future<void> stopScreenCapture() async {
    clearMessages();
    try {
      final stopped = await _channel.stopScreenCapture();
      if (stopped) {
        _isScreenCaptureActive = false;
        _permissionStatus = _permissionStatus.copyWith(
          screenCapturePermission: PermissionGrantState.actionRequired,
        );
        _statusMessage = 'หยุดการจับภาพหน้าจอแล้ว';
      }
      notifyListeners();
    } on GameLingoNativeException catch (e) {
      _errorMessage = e.message;
      notifyListeners();
    }
  }

  /// Triggers an on-demand single-frame capture + Japanese OCR via `translateScreen`.
  Future<void> testSingleFrameCapture() async {
    clearMessages();
    try {
      final result = await _channel.translateScreen();
      if (result != null) {
        _lastTranslationResult = result;
        if (result.originalText.isNotEmpty) {
          _lastOcrText = result.originalText;
        }
      }
      notifyListeners();
    } on GameLingoNativeException catch (e) {
      _errorMessage = e.message;
      notifyListeners();
    }
  }

  /// Closes the floating translation panel overlay if currently visible.
  Future<void> closeTranslationPanel() async {
    try {
      await _channel.closeTranslationPanel();
    } on GameLingoNativeException catch (e) {
      _errorMessage = e.message;
      notifyListeners();
    }
  }

  void clearMessages() {
    _errorMessage = null;
    _statusMessage = null;
  }

  void _handleNativeEvent(GameLingoNativeEvent event) {
    switch (event.type) {
      case GameLingoEventType.permissionStatusChanged:
        _permissionStatus = PermissionStatusModel.fromMap(event.data);
        _isScreenCaptureActive = _permissionStatus.isScreenCaptureGranted;
        break;
      case GameLingoEventType.bubbleStatusChanged:
        _isBubbleRunning = (event.data['isRunning'] as bool?) ?? false;
        _bubbleVisualState =
            (event.data['bubbleState'] as String?) ??
            (_isBubbleRunning ? 'active' : 'stopped');
        _isProcessing = (event.data['isLoading'] as bool?) ?? false;
        if (_bubbleVisualState == 'tapped') {
          _statusMessage = 'แตะปุ่ม Floating Bubble แล้ว — กำลังเริ่มกระบวนการแปล...';
        } else if (!_isBubbleRunning &&
            event.data['reason'] == 'bubble_close_button') {
          _statusMessage = 'ปิด Floating Bubble จากปุ่มลอยบนหน้าจอแล้ว';
        } else if (!_isBubbleRunning &&
            event.data['reason'] == 'notification_stop_action') {
          _statusMessage = 'ปิด Floating Bubble จากแถบแจ้งเตือนแล้ว';
        }
        break;
      case GameLingoEventType.captureStarted:
        _isProcessing = true;
        _isScreenCaptureActive = true;
        _statusMessage = 'กำลังซ่อนปุ่ม Bubble ชั่วคราวและจับภาพหน้าจอเกม...';
        break;
      case GameLingoEventType.captureCompleted:
        _isProcessing = false;
        _lastCaptureMetadata = event.data;
        final w = event.data['width'] ?? 0;
        final h = event.data['height'] ?? 0;
        final count = event.data['captureCountInSession'] ?? 1;
        _statusMessage =
            'จับภาพหน้าจอสำเร็จ (${w}x$h px, ครั้งที่ $count ในเซสชันนี้)';
        break;
      case GameLingoEventType.ocrStarted:
        _isProcessing = true;
        _statusMessage = 'กำลังตรวจจับข้อความภาษาญี่ปุ่น (ML Kit Japanese OCR)...';
        break;
      case GameLingoEventType.ocrCompleted:
        _isProcessing = false;
        _lastOcrMetadata = event.data;
        final text = (event.data['originalText'] as String?) ?? '';
        _lastOcrText = text;
        final lines = event.data['lineCount'] ?? 0;
        final ms = event.data['processingTimeMs'] ?? 0;
        _statusMessage =
            'อ่านข้อความภาษาญี่ปุ่นสำเร็จ ($lines บรรทัด, ใช้เวลา $ms ms)';
        _errorMessage = null;
        break;
      case GameLingoEventType.translationStarted:
        _isProcessing = true;
        _statusMessage = 'กำลังแปลข้อความแบบ On-Device...';
        break;
      case GameLingoEventType.translationCompleted:
        _isProcessing = false;
        _lastTranslationResult = TranslationResult.fromMap(event.data);
        _statusMessage = 'แปลข้อความสำเร็จ';
        _errorMessage = null;
        break;
      case GameLingoEventType.translationFailed:
        _isProcessing = false;
        _errorMessage =
            event.errorMessage ??
            (event.data['message'] as String?) ??
            'การแปลข้อความล้มเหลว';
        break;
      case GameLingoEventType.captureStopped:
        _isProcessing = false;
        _isScreenCaptureActive = false;
        _permissionStatus = _permissionStatus.copyWith(
          screenCapturePermission: PermissionGrantState.actionRequired,
        );
        _statusMessage = 'เซสชันการจับภาพหน้าจอสิ้นสุดลง';
        break;
      case GameLingoEventType.unknown:
        break;
    }
    notifyListeners();
  }

  @override
  void dispose() {
    _eventSub?.cancel();
    _eventService.dispose();
    super.dispose();
  }
}

