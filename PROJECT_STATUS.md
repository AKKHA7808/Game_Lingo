# GameLingo — Project Status

**Project Name:** GameLingo  
**Application ID:** `com.gamelingo.app`  
**Platform:** Android (Flutter + Kotlin Native)  
**Target Users:** ผู้เล่นเกมภาษาญี่ปุ่นที่ต้องการแปลข้อความบนหน้าจอเกมเป็นภาษาไทยหรืออังกฤษผ่านหน้าต่างลอย (Floating Translation Panel) โดยไม่ต้องออกจากเกม

---

## Environment Inspection (Phase 0)

| Component | Version / Details |
|---|---|
| **Flutter SDK** | `3.44.1` (Channel stable) at `D:\Flutter_1\flutter` |
| **Dart SDK** | `3.12.1` |
| **Android SDK** | `36.1.0` (Platform `android-36.1`, Build-Tools `36.1.0`) |
| **Java / JDK** | OpenJDK `21.0.10` (Bundled with Android Studio) |
| **Android Gradle Plugin** | `9.0.1` |
| **Kotlin Gradle Plugin** | `2.3.20` |

---

## Development Phase Tracker

| Phase | Name | Status | Summary |
|---|---|---|---|
| **Phase 0** | Project Inspection | ✅ Completed | ตรวจสอบ Flutter SDK, Android SDK, Gradle, และสร้างโครงสร้างโปรเจกต์เริ่มต้น |
| **Phase 1** | Flutter Foundation | ✅ Completed | สร้างโครงสร้าง Flutter UI (Modern Dark Theme Material 3), Home/Settings/Permission Screens, Language Selector (`SharedPreferences`), Models, State Provider, และ `MethodChannel` / `EventChannel` (`com.gamelingo.app/native`, `com.gamelingo.app/events`) |
| **Phase 2** | Floating Bubble | ✅ Completed | พัฒนา `FloatingBubbleService` ด้วย `WindowManager` (`TYPE_APPLICATION_OVERLAY`), ลากวาง, ดูดเข้าขอบซ้าย/ขวา (`ValueAnimator`), จดจำตำแหน่ง (`SharedPreferences`), สถานะ Active/Inactive/Loading, Debounce ป้องกันการแตะซ้ำ, ปุ่มปิด Bubble และ `StopServiceReceiver` |
| **Phase 3** | Screen Capture | ✅ Completed | พัฒนา `MediaProjectionService` (`foregroundServiceType="mediaProjection"`), `ScreenCaptureManager`, และ `CaptureResult` รองรับ Android 14+ (ลงทะเบียน Callback ก่อนสร้าง `VirtualDisplay`, Reuse `VirtualDisplay`/`ImageReader` ภายใน Session เดียวโดยไม่ใช้ Token ซ้ำ, รองรับการหมุนจอ `resize`, ซ่อน Bubble ชั่วคราวก่อนเก็บภาพ, ตรวจจับหน้าจอดำจาก `FLAG_SECURE`, และคืนทรัพยากรครบถ้วน) |
| **Phase 4** | Japanese OCR | ✅ Completed | ติดตั้ง Bundled ML Kit Text Recognition v2 (`com.google.mlkit:text-recognition-japanese:16.0.1`) และพัฒนา `JapaneseOcrManager` ด้วย `JapaneseTextRecognizerOptions` ประมวลผลบน Background Thread, อ่าน Hiragana/Katakana/Kanji, เรียงลำดับข้อความหลายบรรทัด, กรองข้อความว่าง/ซ้ำ และแสดงข้อความญี่ปุ่นที่ตรวจพบใน Flutter |
| **Phase 5** | Translation | ✅ Completed | ติดตั้ง ML Kit On-Device Translation (`com.google.mlkit:translate:17.0.2`), พัฒนา `TranslationManager` และ `TranslationResult`, รองรับ `ja → th` และ `ja → en`, ตรวจสอบสถานะโมเดลจริงผ่าน `RemoteModelManager`, ดาวน์โหลดโมเดลโดยใช้ Wi-Fi เป็นค่าเริ่มต้น, จัดการ `Translator.close()`, เพิ่ม Attribution "Translated by Google" และบันทึกข้อจำกัดคุณภาพการแปล `ja → th` ใน `README.md` |
| **Phase 6** | Floating Translation Panel | ✅ Completed | พัฒนา `FloatingPanelService` ด้วย `WindowManager` (`TYPE_APPLICATION_OVERLAY`) แสดงหน้าต่างลอยทับหน้าจอเกม (มุมโค้งมน พื้นหลังโปร่งแสง แสดงข้อความต้นฉบับภาษาญี่ปุ่น คำแปลไทย/อังกฤษ เลื่อนอ่านได้ ลากเปลี่ยนตำแหน่งได้ ย่อ/ขยาย Collapse ได้ มีสถานะ Loading/Error ปุ่ม Copy ปุ่มแปลใหม่ Retry และปุ่มปิด Close ป้องกันการแสดงผลซ้ำจาก Event เก่าด้วย Request Sequence ID) พร้อมเชื่อมต่อวงจร Bubble → ซ่อน Bubble/Panel → จับภาพ → OCR → แปลภาษา → แสดงผลใน Panel |
| **Phase 7** | Integration & Testing | ✅ Completed | พัฒนา `TranslationPipelineCoordinator` เพื่อให้วงจรทำงานต่อเนื่องแม้ผู้ใช้ปัดปิดหน้าต่างหลักของแอป (Error Case 15), ตรวจสอบและจัดการ Error Cases ครบทั้ง 15 กรณี, ตรวจสอบการคืนทรัพยากร (`Bitmap`, `ImageReader`, `VirtualDisplay`, `MediaProjection`, `TextRecognizer`, `Translator`) และเพิ่ม Integration Workflow Tests |
| **Phase 8** | Final Build | ✅ Completed | รัน `flutter pub get`, `flutter analyze`, `flutter test`, สร้างไฟล์ APK (`assembleDebug` และ `assembleRelease`), และจัดทำเอกสารคู่มือใน `README.md` ครบถ้วน |

---

## Deliverables Summary (Phase 0 – Phase 8)

1. **Application Architecture & Models:**
   - [lib/models/translation_result.dart](lib/models/translation_result.dart) (includes `JapaneseOcrTextProcessor`)
   - [lib/models/permission_status.dart](lib/models/permission_status.dart)
   - [lib/models/translation_model_status.dart](lib/models/translation_model_status.dart)
   - [CaptureResult.kt](android/app/src/main/kotlin/com/gamelingo/app/models/CaptureResult.kt)
   - [TranslationResult.kt](android/app/src/main/kotlin/com/gamelingo/app/models/TranslationResult.kt)
2. **Platform Integration Layer & Native Services/Managers:**
   - [lib/services/gamelingo_channel.dart](lib/services/gamelingo_channel.dart) (`MethodChannel: com.gamelingo.app/native`)
   - [lib/services/gamelingo_event_service.dart](lib/services/gamelingo_event_service.dart) (`EventChannel: com.gamelingo.app/events`)
   - [MainActivity.kt](android/app/src/main/kotlin/com/gamelingo/app/MainActivity.kt)
   - [TranslationPipelineCoordinator.kt](android/app/src/main/kotlin/com/gamelingo/app/managers/TranslationPipelineCoordinator.kt)
   - [NativeEventBridge.kt](android/app/src/main/kotlin/com/gamelingo/app/services/NativeEventBridge.kt)
   - [FloatingBubbleService.kt](android/app/src/main/kotlin/com/gamelingo/app/services/FloatingBubbleService.kt)
   - [FloatingPanelService.kt](android/app/src/main/kotlin/com/gamelingo/app/services/FloatingPanelService.kt)
   - [MediaProjectionService.kt](android/app/src/main/kotlin/com/gamelingo/app/services/MediaProjectionService.kt)
   - [ScreenCaptureManager.kt](android/app/src/main/kotlin/com/gamelingo/app/managers/ScreenCaptureManager.kt)
   - [JapaneseOcrManager.kt](android/app/src/main/kotlin/com/gamelingo/app/managers/JapaneseOcrManager.kt)
   - [TranslationManager.kt](android/app/src/main/kotlin/com/gamelingo/app/managers/TranslationManager.kt)
   - [StopServiceReceiver.kt](android/app/src/main/kotlin/com/gamelingo/app/receivers/StopServiceReceiver.kt)
3. **State Management & Persistence:**
   - [lib/providers/app_state_provider.dart](lib/providers/app_state_provider.dart)
4. **Flutter Screens & Reusable Widgets:**
   - [lib/main.dart](lib/main.dart), [lib/app.dart](lib/app.dart)
   - [lib/screens/home_screen.dart](lib/screens/home_screen.dart)
   - [lib/screens/settings_screen.dart](lib/screens/settings_screen.dart)
   - [lib/screens/permission_screen.dart](lib/screens/permission_screen.dart)
   - [lib/widgets/app_header.dart](lib/widgets/app_header.dart)
   - [lib/widgets/permission_status_card.dart](lib/widgets/permission_status_card.dart)
   - [lib/widgets/language_selector.dart](lib/widgets/language_selector.dart)
   - [lib/widgets/model_status_card.dart](lib/widgets/model_status_card.dart)
5. **Automated Tests, Static Analysis & Android Builds:**
   - [translation_result_test.dart](test/translation_result_test.dart) — 11/11 unit & integration tests passed
   - [widget_test.dart](test/widget_test.dart) — 3/3 widget tests passed (total 14/14 tests passed)
   - `flutter analyze` — `No issues found!`
   - `android\gradlew.bat -p android assembleDebug assembleRelease` — สร้างไฟล์ Debug APK (`build/app/outputs/flutter-apk/app-debug.apk`) และ Release APK (`build/app/outputs/flutter-apk/app-release.apk`)
   - *หมายเหตุการทดสอบบนอุปกรณ์จริง:* ยังไม่ได้ทดสอบรันจริงบนโทรศัพท์ Android หรือ Emulator เนื่องจากสภาพแวดล้อมปัจจุบันไม่มีอุปกรณ์ Android เชื่อมต่ออยู่
