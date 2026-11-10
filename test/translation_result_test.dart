import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gamelingo/models/permission_status.dart';
import 'package:gamelingo/models/translation_model_status.dart';
import 'package:gamelingo/models/translation_result.dart';
import 'package:gamelingo/providers/app_state_provider.dart';
import 'package:gamelingo/services/gamelingo_channel.dart';
import 'package:gamelingo/services/gamelingo_event_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  group('TranslationResult Model Tests', () {
    test('serializes and deserializes TranslationResult accurately', () {
      final now = DateTime.fromMillisecondsSinceEpoch(1730000000000);
      final original = TranslationResult(
        originalText: '冒険を始めよう！',
        translatedText: 'มาเริ่มการผจญภัยกันเถอะ!',
        sourceLanguage: 'ja',
        targetLanguage: 'th',
        timestamp: now,
        isSuccess: true,
      );

      final map = original.toMap();
      final restored = TranslationResult.fromMap(map);

      expect(restored.originalText, '冒険を始めよう！');
      expect(restored.translatedText, 'มาเริ่มการผจญภัยกันเถอะ!');
      expect(restored.sourceLanguage, 'ja');
      expect(restored.targetLanguage, 'th');
      expect(restored.timestamp, now);
      expect(restored.isSuccess, isTrue);
      expect(restored.errorMessage, isNull);
    });

    test('handles error state in TranslationResult', () {
      final failed = TranslationResult.fromMap({
        'originalText': '',
        'translatedText': '',
        'targetLanguage': 'en',
        'timestamp': 1730000000000,
        'isSuccess': false,
        'errorMessage': 'ไม่พบข้อความภาษาญี่ปุ่นบนหน้าจอ',
      });

      expect(failed.isSuccess, isFalse);
      expect(failed.targetLanguage, 'en');
      expect(failed.errorMessage, 'ไม่พบข้อความภาษาญี่ปุ่นบนหน้าจอ');
    });
  });

  group('PermissionStatusModel & TranslationModelStatus Tests', () {
    test('parses PermissionStatusModel from native map', () {
      final model = PermissionStatusModel.fromMap({
        'overlayPermission': 'granted',
        'screenCapturePermission': 'action_required',
        'notificationPermission': 'not_granted',
      });

      expect(model.isOverlayGranted, isTrue);
      expect(model.canStartBubble, isTrue);
      expect(
        model.screenCapturePermission,
        PermissionGrantState.actionRequired,
      );
      expect(model.isNotificationGranted, isFalse);
    });

    test('parses TranslationModelStatus and checks language readiness', () {
      final status = TranslationModelStatus.fromMap({
        'th': 'downloaded',
        'en': 'not_downloaded',
      });

      expect(status.isLanguageModelReady(TargetLanguage.thai), isTrue);
      expect(status.isLanguageModelReady(TargetLanguage.english), isFalse);
    });

    test('parses bubbleStatusChanged event from FloatingBubbleService', () {
      final event = GameLingoNativeEvent.fromDynamic({
        'event': 'bubbleStatusChanged',
        'data': {
          'isRunning': true,
          'bubbleState': 'tapped',
          'isLoading': true,
          'x': 1020,
          'y': 450,
        },
        'timestamp': 1730000000000,
      });

      expect(event.type, GameLingoEventType.bubbleStatusChanged);
      expect(event.data['isRunning'], isTrue);
      expect(event.data['bubbleState'], 'tapped');
      expect(event.data['isLoading'], isTrue);
      expect(event.data['x'], 1020);
      expect(event.data['y'], 450);
    });

    test('parses captureCompleted event metadata from ScreenCaptureManager', () {
      final event = GameLingoNativeEvent.fromDynamic({
        'event': 'captureCompleted',
        'data': {
          'width': 1080,
          'height': 2400,
          'byteCount': 10368000,
          'samplePixelHex': '#FF1E293B',
          'captureCountInSession': 3,
          'isSuccess': true,
          'isFlagSecureSuspected': false,
        },
        'timestamp': 1730000005000,
      });

      expect(event.type, GameLingoEventType.captureCompleted);
      expect(event.data['width'], 1080);
      expect(event.data['height'], 2400);
      expect(event.data['captureCountInSession'], 3);
      expect(event.data['samplePixelHex'], '#FF1E293B');
      expect(event.data['isFlagSecureSuspected'], isFalse);
    });

    test('validates Hiragana, Katakana, Kanji and filters multi-line OCR text', () {
      expect(JapaneseOcrTextProcessor.containsJapaneseScript('ひらがな'), isTrue);
      expect(JapaneseOcrTextProcessor.containsJapaneseScript('カタカナ'), isTrue);
      expect(JapaneseOcrTextProcessor.containsJapaneseScript('漢字の冒険'), isTrue);
      expect(JapaneseOcrTextProcessor.containsJapaneseScript('FPS 60 / 120'), isFalse);

      final cleaned = JapaneseOcrTextProcessor.cleanAndMergeJapaneseLines([
        '  ',
        'FPS: 60',
        '勇者よ、目覚める時だ。',
        '勇者よ、目覚める時だ。', // duplicate line filtered
        'ステータスを確認しますか？',
      ]);

      expect(cleaned, '勇者よ、目覚める時だ。\nステータスを確認しますか？');
      expect(JapaneseOcrTextProcessor.countJapaneseCharacters(cleaned), 21);
    });

    test('parses ocrCompleted event payload from JapaneseOcrManager', () {
      final event = GameLingoNativeEvent.fromDynamic({
        'event': 'ocrCompleted',
        'data': {
          'originalText': 'クエストをクリアしました！\n報酬を受け取ってください。',
          'lineCount': 2,
          'blockCount': 1,
          'japaneseCharCount': 24,
          'processingTimeMs': 142,
          'isSuccess': true,
        },
        'timestamp': 1730000010000,
      });

      expect(event.type, GameLingoEventType.ocrCompleted);
      expect(
        event.data['originalText'],
        'クエストをクリアしました！\n報酬を受け取ってください。',
      );
      expect(event.data['lineCount'], 2);
      expect(event.data['japaneseCharCount'], 24);
      expect(event.data['processingTimeMs'], 142);
    });

    test('parses translationCompleted and translationFailed events from TranslationManager', () {
      final completed = GameLingoNativeEvent.fromDynamic({
        'event': 'translationCompleted',
        'data': {
          'originalText': '冒険を始めよう！',
          'translatedText': 'มาเริ่มการผจญภัยกันเถอะ!',
          'sourceLanguage': 'ja',
          'targetLanguage': 'th',
          'processingTimeMs': 85,
          'isSuccess': true,
        },
        'timestamp': 1730000020000,
      });

      expect(completed.type, GameLingoEventType.translationCompleted);
      final parsedResult = TranslationResult.fromMap(completed.data);
      expect(parsedResult.originalText, '冒険を始めよう！');
      expect(parsedResult.translatedText, 'มาเริ่มการผจญภัยกันเถอะ!');
      expect(parsedResult.targetLanguage, 'th');
      expect(parsedResult.isSuccess, isTrue);

      final failed = GameLingoNativeEvent.fromDynamic({
        'event': 'translationFailed',
        'data': {
          'errorCode': 'MODEL_NOT_DOWNLOADED',
          'targetLanguage': 'th',
          'isSuccess': false,
        },
        'error': 'ยังไม่ได้ดาวน์โหลดโมเดลแปลภาษา (Japanese → Thai)',
        'timestamp': 1730000025000,
      });

      expect(failed.type, GameLingoEventType.translationFailed);
      expect(failed.errorMessage, contains('Japanese → Thai'));
    });
  });

  group('Phase 7 Full Workflow & Error Handling Integration Tests', () {
    const channel = MethodChannel(GameLingoChannel.channelName);

    setUp(() {
      TestWidgetsFlutterBinding.ensureInitialized();
      SharedPreferences.setMockInitialValues({});
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    test('blocks startFloatingBubble when overlay permission is denied', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall call) async {
        if (call.method == 'checkOverlayPermission') {
          return {
            'overlayPermission': 'not_granted',
            'screenCapturePermission': 'action_required',
            'notificationPermission': 'granted',
          };
        }
        return null;
      });

      final provider = AppStateProvider();
      await provider.startFloatingBubble();

      expect(provider.isBubbleRunning, isFalse);
      expect(provider.errorMessage, contains('Overlay Permission'));
      provider.dispose();
    });

    test('executes full workflow: permissions -> bubble -> capture -> OCR -> translation -> close panel -> stop', () async {
      bool panelClosed = false;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall call) async {
        switch (call.method) {
          case 'checkOverlayPermission':
            return {
              'overlayPermission': 'granted',
              'screenCapturePermission': 'granted',
              'notificationPermission': 'granted',
            };
          case 'getTranslationModelStatus':
            return {
              'th': 'downloaded',
              'en': 'downloaded',
            };
          case 'setTargetLanguage':
            return null;
          case 'startFloatingBubble':
            return true;
          case 'stopFloatingBubble':
            return true;
          case 'translateScreen':
            return {
              'originalText': '魔王を倒して世界を救え！',
              'translatedText': 'จงปราบจอมมารและกอบกู้โลก!',
              'sourceLanguage': 'ja',
              'targetLanguage': 'th',
              'timestamp': 1730000050000,
              'isSuccess': true,
            };
          case 'closeTranslationPanel':
            panelClosed = true;
            return null;
          default:
            return null;
        }
      });

      final provider = AppStateProvider();
      await provider.initialize();

      expect(provider.permissionStatus.isOverlayGranted, isTrue);
      expect(provider.modelStatus.isLanguageModelReady(TargetLanguage.thai), isTrue);

      await provider.startFloatingBubble();
      expect(provider.isBubbleRunning, isTrue);

      await provider.testSingleFrameCapture();
      expect(provider.lastTranslationResult, isNotNull);
      expect(provider.lastTranslationResult!.originalText, '魔王を倒して世界を救え！');
      expect(
        provider.lastTranslationResult!.translatedText,
        'จงปราบจอมมารและกอบกู้โลก!',
      );
      expect(provider.lastOcrText, '魔王を倒して世界を救え！');

      await provider.closeTranslationPanel();
      expect(panelClosed, isTrue);

      await provider.stopFloatingBubble();
      expect(provider.isBubbleRunning, isFalse);
      provider.dispose();
    });
  });
}

