import 'package:flutter/services.dart';
import '../models/permission_status.dart';
import '../models/translation_model_status.dart';
import '../models/translation_result.dart';

/// Exception thrown when communicating with the native Android layer fails.
class GameLingoNativeException implements Exception {
  final String code;
  final String message;

  const GameLingoNativeException({
    required this.code,
    required this.message,
  });

  @override
  String toString() => 'GameLingoNativeException($code): $message';
}

/// Manages MethodChannel calls from Flutter to Kotlin Native (`com.gamelingo.app/native`).
class GameLingoChannel {
  static const String channelName = 'com.gamelingo.app/native';

  final MethodChannel _channel;

  GameLingoChannel({MethodChannel? channel})
      : _channel = channel ?? const MethodChannel(channelName);

  /// Checks current Android permissions (Overlay, MediaProjection status, Notification).
  Future<PermissionStatusModel> checkOverlayPermission() async {
    try {
      final result = await _channel.invokeMethod<dynamic>(
        'checkOverlayPermission',
      );
      if (result is bool) {
        return PermissionStatusModel(
          overlayPermission: result
              ? PermissionGrantState.granted
              : PermissionGrantState.notGranted,
        );
      }
      if (result is Map) {
        return PermissionStatusModel.fromMap(result);
      }
      return const PermissionStatusModel();
    } on MissingPluginException {
      throw const GameLingoNativeException(
        code: 'CHANNEL_UNAVAILABLE',
        message: 'ไม่สามารถเชื่อมต่อกับระบบ Native Android ได้ (MethodChannel)',
      );
    } on PlatformException catch (e) {
      throw GameLingoNativeException(
        code: e.code,
        message: e.message ?? 'เกิดข้อผิดพลาดในการตรวจสอบสิทธิ์ Overlay',
      );
    }
  }

  /// Opens the Android Settings page for `ACTION_MANAGE_OVERLAY_PERMISSION`.
  Future<bool> requestOverlayPermission() async {
    try {
      final granted = await _channel.invokeMethod<bool>(
        'requestOverlayPermission',
      );
      return granted ?? false;
    } on MissingPluginException {
      throw const GameLingoNativeException(
        code: 'CHANNEL_UNAVAILABLE',
        message: 'ไม่สามารถเปิดหน้าตั้งค่าสิทธิ์ผ่านระบบ Native ได้',
      );
    } on PlatformException catch (e) {
      throw GameLingoNativeException(
        code: e.code,
        message: e.message ?? 'ไม่สามารถขอสิทธิ์แสดงผลทับแอปอื่นได้',
      );
    }
  }

  /// Starts the Floating Bubble overlay service.
  Future<bool> startFloatingBubble() async {
    try {
      final started = await _channel.invokeMethod<bool>('startFloatingBubble');
      return started ?? false;
    } on MissingPluginException {
      throw const GameLingoNativeException(
        code: 'CHANNEL_UNAVAILABLE',
        message: 'ไม่สามารถเชื่อมต่อกับ Native Service เพื่อเปิด Bubble ได้',
      );
    } on PlatformException catch (e) {
      throw GameLingoNativeException(
        code: e.code,
        message: e.message ?? 'ไม่สามารถเริ่มการทำงานของ Floating Bubble ได้',
      );
    }
  }

  /// Stops the Floating Bubble overlay service.
  Future<bool> stopFloatingBubble() async {
    try {
      final stopped = await _channel.invokeMethod<bool>('stopFloatingBubble');
      return stopped ?? false;
    } on MissingPluginException {
      throw const GameLingoNativeException(
        code: 'CHANNEL_UNAVAILABLE',
        message: 'ไม่สามารถเชื่อมต่อกับ Native Service เพื่อปิด Bubble ได้',
      );
    } on PlatformException catch (e) {
      throw GameLingoNativeException(
        code: e.code,
        message: e.message ?? 'ไม่สามารถหยุดการทำงานของ Floating Bubble ได้',
      );
    }
  }

  /// Requests MediaProjection permission and starts the Screen Capture session.
  Future<bool> startScreenCapture() async {
    try {
      final started = await _channel.invokeMethod<bool>('startScreenCapture');
      return started ?? false;
    } on MissingPluginException {
      throw const GameLingoNativeException(
        code: 'CHANNEL_UNAVAILABLE',
        message: 'ไม่สามารถเชื่อมต่อกับระบบจับภาพหน้าจอ Native ได้',
      );
    } on PlatformException catch (e) {
      throw GameLingoNativeException(
        code: e.code,
        message: e.message ?? 'ไม่สามารถเริ่มการจับภาพหน้าจอได้',
      );
    }
  }

  /// Stops the active MediaProjection session and releases resources.
  Future<bool> stopScreenCapture() async {
    try {
      final stopped = await _channel.invokeMethod<bool>('stopScreenCapture');
      return stopped ?? false;
    } on MissingPluginException {
      throw const GameLingoNativeException(
        code: 'CHANNEL_UNAVAILABLE',
        message: 'ไม่สามารถเชื่อมต่อกับระบบจับภาพหน้าจอ Native ได้',
      );
    } on PlatformException catch (e) {
      throw GameLingoNativeException(
        code: e.code,
        message: e.message ?? 'ไม่สามารถหยุดการจับภาพหน้าจอได้',
      );
    }
  }

  /// Updates the target translation language (`th` or `en`) in the native layer.
  Future<void> setTargetLanguage(TargetLanguage language) async {
    try {
      await _channel.invokeMethod<void>('setTargetLanguage', {
        'targetLanguage': language.code,
      });
    } on MissingPluginException {
      throw const GameLingoNativeException(
        code: 'CHANNEL_UNAVAILABLE',
        message: 'ไม่สามารถซิงค์ภาษาเป้าหมายกับระบบ Native ได้',
      );
    } on PlatformException catch (e) {
      throw GameLingoNativeException(
        code: e.code,
        message: e.message ?? 'ไม่สามารถตั้งค่าภาษาเป้าหมายได้',
      );
    }
  }

  /// Queries the current download status of on-device translation models (`th` and `en`).
  Future<TranslationModelStatus> getTranslationModelStatus() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'getTranslationModelStatus',
      );
      if (result != null) {
        return TranslationModelStatus.fromMap(result);
      }
      return const TranslationModelStatus();
    } on MissingPluginException {
      throw const GameLingoNativeException(
        code: 'CHANNEL_UNAVAILABLE',
        message: 'ไม่สามารถตรวจสอบสถานะโมเดลแปลภาษาจากระบบ Native ได้',
      );
    } on PlatformException catch (e) {
      throw GameLingoNativeException(
        code: e.code,
        message: e.message ?? 'เกิดข้อผิดพลาดในการตรวจสอบสถานะโมเดลแปลภาษา',
      );
    }
  }

  /// Downloads the ML Kit translation model for [language].
  /// By default, recommends Wi-Fi unless [allowCellular] is true.
  Future<bool> downloadTranslationModel(
    TargetLanguage language, {
    bool allowCellular = false,
  }) async {
    try {
      final downloaded = await _channel.invokeMethod<bool>(
        'downloadTranslationModel',
        {
          'targetLanguage': language.code,
          'allowCellular': allowCellular,
        },
      );
      return downloaded ?? false;
    } on MissingPluginException {
      throw const GameLingoNativeException(
        code: 'CHANNEL_UNAVAILABLE',
        message: 'ไม่สามารถเชื่อมต่อกับระบบดาวน์โหลดโมเดล Native ได้',
      );
    } on PlatformException catch (e) {
      throw GameLingoNativeException(
        code: e.code,
        message: e.message ?? 'การดาวน์โหลดโมเดลแปลภาษาล้มเหลว',
      );
    }
  }

  /// Triggers a one-shot screen capture, Japanese OCR, and translation cycle.
  Future<TranslationResult?> translateScreen() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'translateScreen',
      );
      if (result != null) {
        return TranslationResult.fromMap(result);
      }
      return null;
    } on MissingPluginException {
      throw const GameLingoNativeException(
        code: 'CHANNEL_UNAVAILABLE',
        message: 'ไม่สามารถสั่งแปลหน้าจอผ่านระบบ Native ได้',
      );
    } on PlatformException catch (e) {
      throw GameLingoNativeException(
        code: e.code,
        message: e.message ?? 'เกิดข้อผิดพลาดระหว่างการแปลหน้าจอ',
      );
    }
  }

  /// Closes the floating translation panel overlay if currently visible.
  Future<void> closeTranslationPanel() async {
    try {
      await _channel.invokeMethod<void>('closeTranslationPanel');
    } on MissingPluginException {
      throw const GameLingoNativeException(
        code: 'CHANNEL_UNAVAILABLE',
        message: 'ไม่สามารถปิดหน้าต่างคำแปลผ่านระบบ Native ได้',
      );
    } on PlatformException catch (e) {
      throw GameLingoNativeException(
        code: e.code,
        message: e.message ?? 'ไม่สามารถปิดหน้าต่างคำแปลได้',
      );
    }
  }
}

