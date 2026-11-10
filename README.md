# GameLingo — Android Floating Game Translator

แอปพลิเคชัน Android สำหรับสแกนข้อความภาษาญี่ปุ่นจากหน้าจอเกมและแปลเป็นภาษาไทย (`Japanese → Thai`) หรือภาษาอังกฤษ (`Japanese → English`) ทันทีผ่านหน้าต่างลอย (**Floating Translation Panel**) โดยไม่ต้องออกจากเกมหรือสลับแอป

---

## 1. โครงสร้างสถาปัตยกรรม (Architecture Overview)

- **Application ID:** `com.gamelingo.app`
- **Frontend UI & State Management:** Flutter + Dart (`Material 3` Dark Theme, `Provider`, `SharedPreferences`)
- **Platform Channels:**
  - `MethodChannel`: `com.gamelingo.app/native`
  - `EventChannel`: `com.gamelingo.app/events`
- **Android Native Services & Managers (Kotlin):**
  - `FloatingBubbleService`: ปุ่มวงกลมลอยเหนือแอปอื่น (`WindowManager` + `TYPE_APPLICATION_OVERLAY`) ลากวางได้ ดูดเข้าขอบซ้าย/ขวาอัตโนมัติ จำตำแหน่งล่าสุด มีสถานะ Active/Inactive/Loading และปุ่มปิด `✕`
  - `FloatingPanelService`: หน้าต่างแสดงผลคำแปลลอยทับบนเกม (`TYPE_APPLICATION_OVERLAY`) ดีไซน์ Dark Theme มุมโค้งมน ลากย้ายตำแหน่งได้ ย่อ/ขยาย (Collapse) ได้ เลื่อนอ่านข้อความยาวได้ มีปุ่ม Copy, ปุ่มแปลใหม่ (Retry), และปุ่มปิด
  - `MediaProjectionService` & `ScreenCaptureManager`: Foreground Service (`foregroundServiceType="mediaProjection"`) รองรับ Android 10–15+ ซ่อนปุ่ม Bubble และ Panel ก่อนเก็บภาพหน้าจอเกม 1 เฟรมเมื่อผู้ใช้แตะ Bubble และแสดงกลับทันทีโดยไม่บันทึกไฟล์ภาพลงเครื่อง
  - `JapaneseOcrManager`: ใช้ **Google ML Kit Text Recognition v2 (Japanese)** (`com.google.mlkit:text-recognition-japanese:16.0.1`, Bundled Model) อ่านอักษร Hiragana, Katakana และ Kanji บน Background Thread
  - `TranslationManager`: ใช้ **Google ML Kit On-Device Translation** (`com.google.mlkit:translate:17.0.2`) แปลภาษาภายในเครื่องแบบออฟไลน์โดยไม่ต้องเรียก Cloud API
  - `TranslationPipelineCoordinator`: ตัวประสานงานระดับ Application/Service ที่ช่วยให้วงจร `Bubble → Capture → OCR → Translation → Floating Panel` ทำงานต่อเนื่องได้แม้ผู้ใช้จะปัดปิดหน้าต่างหลักของแอปไปแล้ว

---

## 2. วิธีติดตั้งไฟล์ APK บนโทรศัพท์ Android (APK Installation Guide)

### ไฟล์ APK ที่สร้างจากการ Build
- **Debug APK:** `build/app/outputs/flutter-apk/app-debug.apk`
- **Release APK:** `build/app/outputs/flutter-apk/app-release.apk`

### วิธีติดตั้งผ่านสาย USB (`adb`)
```bash
adb install -r build/app/outputs/flutter-apk/app-release.apk
```
*(หรือติดตั้งไฟล์ `app-debug.apk` สำหรับการทดสอบและดู Log)*

### วิธีติดตั้งโดยคัดลอกไฟล์ลงเครื่องโทรศัพท์โดยตรง
1. คัดลอกไฟล์ `app-release.apk` (หรือ `app-debug.apk`) ไปยังหน่วยความจำของโทรศัพท์ Android
2. เปิดแอปจัดการไฟล์ (Files / My Files) บนโทรศัพท์ แล้วแตะที่ไฟล์ `.apk`
3. หากระบบถามสิทธิ์ติดตั้งจากแหล่งที่ไม่รู้จัก ให้เปิด **Allow from this source (อนุญาตจากแหล่งที่มานี้)** แล้วกด **Install (ติดตั้ง)**

---

## 3. วิธีเปิดสิทธิ์การใช้งานที่จำเป็น (Permission Setup Guide)

เมื่อเปิดแอป **GameLingo** ครั้งแรก ให้ตั้งค่าสิทธิ์ตามลำดับดังนี้:

1. **สิทธิ์แสดงผลทับแอปอื่น (Overlay Permission — `SYSTEM_ALERT_WINDOW`) [บังคับ]:**
   - ในหน้าหลักของ GameLingo หรือหน้า **Permission Setup** ให้แตะปุ่ม **Grant Overlay Permission**
   - ระบบจะเปิดหน้าตั้งค่า **Display over other apps (แสดงผลทับแอปอื่น)** ของ Android
   - ค้นหาแอป **GameLingo** แล้วเปิดสวิตช์อนุญาต จากนั้นกดย้อนกลับเข้าแอป
2. **สิทธิ์การแจ้งเตือน (Notification Permission — `POST_NOTIFICATIONS`) [สำหรับ Android 13+]:**
   - อนุญาตให้ GameLingo แสดงแถบแจ้งเตือนสถานะของ Foreground Service เพื่อให้คุณสามารถกดหยุดการทำงานของ Bubble หรือ Screen Capture จากแถบแจ้งเตือนได้ตลอดเวลา
3. **สิทธิ์จับภาพหน้าจอ (Screen Capture — `MediaProjection`) [ขอเมื่อเริ่มใช้งาน]:**
   - แตะปุ่ม **Request Screen Capture** หรือ **Start Floating Bubble**
   - ระบบ Android จะแสดงหน้าต่างยืนยันการจับภาพหน้าจอ (**Start recording or casting with GameLingo?**)
   - บน Android 14+ แนะนำให้เลือก **Entire screen (ทั้งหน้าจอ)** แล้วแตะ **Start now (เริ่มเลย)**
   - *หมายเหตุความปลอดภัย:* Android กำหนดให้ต้องขอสิทธิ์ MediaProjection ใหม่ทุกครั้งที่เริ่มเซสชันใหม่ และ GameLingo จะจับภาพเฉพาะจังหวะที่คุณแตะปุ่ม Bubble เท่านั้น ไม่มีการอัดวิดีโอหรือส่งภาพออกนอกเครื่อง

---

## 4. วิธีดาวน์โหลดโมเดลแปลภาษา (Translation Model Download Guide)

ระบบ Japanese OCR ติดตั้งมาพร้อมกับตัวแอปแล้ว (Bundled Model) แต่โมเดลแปลภาษาแบบ On-Device ของ ML Kit ต้องดาวน์โหลดเก็บไว้ในเครื่องก่อนใช้งานครั้งแรก (ขนาดประมาณ 30–50 MB ต่อภาษา):

1. เชื่อมต่ออินเทอร์เน็ตผ่าน **Wi-Fi** (ค่าเริ่มต้นใช้ Wi-Fi เพื่อประหยัดดาต้ามือถือ)
   - *ทางเลือก:* หากต้องการดาวน์โหลดผ่านเน็ตมือถือ (4G/5G) ให้เปิดหน้า **Settings** (ไอคอนฟันเฟืองมุมขวาบน) แล้วเปิดสวิตช์ **อนุญาตให้ดาวน์โหลดผ่านเครือข่ายมือถือ (Cellular Data)**
2. ในหน้าหลัก (**Home Screen**) หรือหน้า **Settings** เลือกภาษาเป้าหมายที่ต้องการแปล (`Japanese → Thai` หรือ `Japanese → English`)
3. ในการ์ด **On-Device Translation Models** แตะปุ่ม **ดาวน์โหลด** ข้างคู่ภาษาที่ต้องการ
4. รอจนสถานะเปลี่ยนเป็น **`พร้อมใช้งาน (Downloaded)`** หลังจากดาวน์โหลดเสร็จแล้วจะสามารถแปลภาษาในเกมแบบ **Offline 100%** โดยไม่ต้องใช้อินเทอร์เน็ตอีก

---

## 5. ขั้นตอนการใช้งานจริงขณะเล่นเกม (12-Step User Workflow)

1. เปิดแอป **GameLingo**
2. ตรวจสอบและอนุญาต **Overlay Permission** และ **Notification Permission**
3. เลือกภาษาเป้าหมาย (`Japanese → Thai` หรือ `Japanese → English`) และตรวจสอบว่าโมเดลแปลภาษาอยู่ในสถานะ **พร้อมใช้งาน**
4. แตะปุ่ม **Start Floating Bubble** และกดอนุญาต **MediaProjection**
5. สลับเข้าสู่เกมภาษาญี่ปุ่นที่ต้องการเล่น (จะเห็นปุ่มวงกลม `訳` ลอยอยู่บริเวณขอบหน้าจอ)
6. เมื่อพบข้อความภาษาญี่ปุ่นในเกมที่ต้องการแปล ให้ **แตะที่ปุ่ม Floating Bubble**
7. ระบบจะซ่อนปุ่มชั่วคราว จับภาพหน้าจอ 1 เฟรม และแสดงปุ่มกลับมาพร้อมไอคอนหมุนประมวลผล
8. ระบบทำ **Japanese OCR** อ่านข้อความ Hiragana, Katakana และ Kanji บนหน้าจอ
9. ระบบแปลข้อความเป็นภาษาไทยหรือภาษาอังกฤษตามที่เลือกไว้
10. แสดงหน้าต่างลอย **Floating Translation Panel** ทับบนเกมทันที (ลากย้ายที่ได้, เลื่อนอ่านข้อความยาวได้, กด **Copy**, กด **แปลใหม่ (Retry)** หรือกดปุ่ม **`—`** เพื่อย่อหน้าต่างได้)
11. แตะปุ่ม **`✕`** หรือปุ่ม **`ปิด`** บนหน้าต่างคำแปลเพื่อซ่อนหน้าต่างและเล่นเกมต่อได้ทันที
12. เมื่อเลิกเล่นเกม สามารถหยุดการทำงานของ GameLingo ได้โดยแตะปุ่ม **`✕`** บนตัว Bubble, กดปุ่ม **ปิด Bubble** บนแถบ Notification, หรือกด **Stop Floating Bubble** ในแอป

---

## 6. ข้อจำกัดที่ทราบ (Known Limitations)

1. **คุณภาพการแปลภาษาญี่ปุ่นเป็นภาษาไทย (Japanese → Thai Pivot Translation):**
   - Google ML Kit On-Device Translation แปลคู่ภาษา `ja → th` โดยใช้ภาษาอังกฤษเป็นภาษากลางภายในเครื่อง (`Japanese → English → Thai`)
   - ประโยคสนทนาในเกมญี่ปุ่นที่ละประธาน/กรรม หรือมีคำศัพท์เฉพาะทางแนวแฟนตาซี/RPG อาจได้สำนวนภาษาไทยที่ไม่เป็นธรรมชาติในบางบริบท หากต้องการความแม่นยำสูงสุดสามารถสลับเป็น **`Japanese → English`** ได้ทันที
2. **เกมที่เปิดใช้งานระบบป้องกันภาพหน้าจอ (`WindowManager.LayoutParams.FLAG_SECURE`):**
   - เกมหรือแอปพลิเคชันบางตัวที่ตั้งค่า `FLAG_SECURE` จะถูกระบบปฏิบัติการ Android บล็อกไม่ให้ `MediaProjection` อ่านภาพหน้าจอ (ได้ภาพสีดำ) ซึ่ง GameLingo จะตรวจจับและแจ้งเตือนให้ผู้ใช้ทราบทันทีโดยไม่พยายามหลบเลี่ยงมาตรการรักษาความปลอดภัยของระบบ
3. **ฟอนต์ตกแต่งพิเศษหรือข้อความแนวตั้ง (Stylized Fonts & Vertical Text):**
   - ฟอนต์ในเกมที่ดัดแปลงรูปทรงมากเกินไป เอฟเฟกต์แสงทับตัวอักษร หรือข้อความภาษาญี่ปุ่นที่เรียงแนวตั้ง (Tategaki) บางรูปแบบอาจทำให้ความแม่นยำของ OCR ลดลง
4. **การจัดการพลังงานของผู้ผลิตโทรศัพท์บางค่าย (OEM Battery Optimization):**
   - โทรศัพท์บางยี่ห้อ (เช่น Xiaomi/MIUI, OPPO/ColorOS, Vivo, Samsung) อาจปิด Background Service อัตโนมัติเมื่อเล่นเกมที่ใช้กราฟิกสูง แนะนำให้ตั้งค่าแบตเตอรี่ของแอป GameLingo เป็น **Unrestricted (ไม่จำกัด)** หากพบว่า Bubble ถูกระบบปิดเอง

---

## 7. Attribution

- **Translated by Google** — ฟีเจอร์การแปลภาษาแบบ On-Device ในแอปพลิเคชันนี้ขับเคลื่อนด้วยเทคโนโลยี **Google ML Kit Translation (Powered by Google Translate)** ตามข้อกำหนดการใช้งานของ Google

---

## 8. สถานะการพัฒนา

ดูรายละเอียดความคืบหน้าครบทั้ง Phase 0 – Phase 8 ได้ที่ [PROJECT_STATUS.md](PROJECT_STATUS.md)
