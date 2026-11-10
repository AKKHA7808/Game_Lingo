package com.gamelingo.app.managers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.gamelingo.app.models.TranslationResult
import com.gamelingo.app.services.FloatingBubbleService
import com.gamelingo.app.services.FloatingPanelService
import com.gamelingo.app.services.MediaProjectionService
import com.gamelingo.app.services.NativeEventBridge
import java.util.concurrent.atomic.AtomicLong

/**
 * Application-scoped coordinator that owns [JapaneseOcrManager] and [TranslationManager]
 * so the full `Bubble -> Capture -> OCR -> Translation -> Floating Panel` pipeline
 * continues working reliably even if the user closes or swipes away [com.gamelingo.app.MainActivity]
 * while playing a full-screen game (Error Case 15).
 */
object TranslationPipelineCoordinator {

    private const val PREFS_NAME = "gamelingo_pipeline_prefs"
    private const val KEY_TARGET_LANGUAGE = "target_language"

    val japaneseOcrManager = JapaneseOcrManager()
    val translationManager = TranslationManager()
    private val pipelineRequestSeq = AtomicLong(0L)

    @Volatile
    private var cachedTargetLanguage: String = "th"

    fun getTargetLanguage(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_TARGET_LANGUAGE, cachedTargetLanguage) ?: "th"
        cachedTargetLanguage = saved
        return saved
    }

    fun setTargetLanguage(context: Context, languageCode: String) {
        val normalized = if (languageCode.lowercase().trim() == "en") "en" else "th"
        cachedTargetLanguage = normalized
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TARGET_LANGUAGE, normalized).apply()
    }

    /**
     * Executes the complete single-frame capture -> Japanese OCR -> On-Device Translation -> Floating Panel flow.
     *
     * @param context Any valid Android [Context] (Service, Activity, or Application).
     * @param onComplete Optional callback for Flutter MethodChannel (`translateScreen`) callers.
     */
    fun executePipeline(
        context: Context,
        onComplete: ((TranslationResult?, String?, String?) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        val requestId = pipelineRequestSeq.incrementAndGet()
        val targetLangSnapshot = getTargetLanguage(appContext)

        if (!MediaProjectionService.isCaptureSessionActive) {
            FloatingBubbleService.setLoadingState(false)
            val errMsg =
                "เซสชันการจับภาพหน้าจอไม่ได้เปิดอยู่ กรุณาเปิดแอป GameLingo เพื่ออนุญาต Screen Capture"
            FloatingPanelService.showError(
                appContext,
                errMsg,
                targetLangSnapshot,
                requestId = requestId
            )
            NativeEventBridge.emit(
                eventName = "translationFailed",
                data = mapOf("reason" to "projection_session_inactive"),
                errorMessage = errMsg
            )
            emitBubbleActiveAfterProcessing()
            onComplete?.invoke(null, "CAPTURE_SESSION_INACTIVE", errMsg)
            return
        }

        FloatingBubbleService.setLoadingState(true)

        // Step 1 & 2: ScreenCaptureManager hides both Bubble and Panel, captures the frame, and restores them.
        MediaProjectionService.captureLatestScreenFrame { captureResult ->
            val capturedBitmap = captureResult.bitmap
            if (requestId != pipelineRequestSeq.get()) {
                capturedBitmap?.recycle()
                return@captureLatestScreenFrame
            }

            if (!captureResult.isSuccess || capturedBitmap == null) {
                capturedBitmap?.recycle()
                FloatingBubbleService.setLoadingState(false)
                val errMsg = captureResult.errorMessage ?: "ไม่สามารถจับภาพหน้าจอเกมได้"
                FloatingPanelService.showError(
                    appContext,
                    errMsg,
                    targetLangSnapshot,
                    requestId = requestId
                )
                emitBubbleActiveAfterProcessing()
                onComplete?.invoke(null, captureResult.errorCode ?: "CAPTURE_FAILED", errMsg)
                return@captureLatestScreenFrame
            }

            // Show Loading state on the Floating Panel while OCR + Translation execute on background threads.
            FloatingPanelService.showLoading(
                appContext,
                targetLangSnapshot,
                "กำลังตรวจจับข้อความภาษาญี่ปุ่น (OCR)...",
                requestId = requestId
            )

            // Step 3: Japanese OCR (always recycles capturedBitmap when finished).
            japaneseOcrManager.recognizeJapaneseFromBitmap(capturedBitmap) { ocrResult ->
                if (requestId != pipelineRequestSeq.get()) {
                    return@recognizeJapaneseFromBitmap
                }

                if (!ocrResult.isSuccess) {
                    FloatingBubbleService.setLoadingState(false)
                    val errMsg = ocrResult.errorMessage ?: "ไม่พบข้อความภาษาญี่ปุ่นบนหน้าจอเกม"
                    FloatingPanelService.showError(
                        appContext,
                        errMsg,
                        targetLangSnapshot,
                        requestId = requestId
                    )
                    emitBubbleActiveAfterProcessing()
                    onComplete?.invoke(null, ocrResult.errorCode ?: "OCR_FAILED", errMsg)
                    return@recognizeJapaneseFromBitmap
                }

                FloatingPanelService.showLoading(
                    appContext,
                    targetLangSnapshot,
                    "กำลังแปลข้อความแบบ On-Device...",
                    requestId = requestId
                )

                // Step 4 & 5: On-Device Translation -> Show Result in Floating Panel
                translationManager.translateJapaneseText(
                    ocrResult.extractedText,
                    targetLangSnapshot
                ) { translationResult ->
                    if (requestId != pipelineRequestSeq.get()) {
                        return@translateJapaneseText
                    }

                    FloatingBubbleService.setLoadingState(false)
                    if (translationResult.isSuccess) {
                        FloatingPanelService.showResult(
                            appContext,
                            translationResult.originalText,
                            translationResult.translatedText,
                            targetLangSnapshot,
                            requestId = requestId
                        )
                        emitBubbleActiveAfterProcessing()
                        onComplete?.invoke(translationResult, null, null)
                    } else {
                        val errMsg = translationResult.errorMessage ?: "การแปลข้อความล้มเหลว"
                        FloatingPanelService.showError(
                            appContext,
                            errMsg,
                            targetLangSnapshot,
                            originalText = ocrResult.extractedText,
                            requestId = requestId
                        )
                        emitBubbleActiveAfterProcessing()
                        onComplete?.invoke(
                            null,
                            translationResult.errorCode ?: "TRANSLATION_FAILED",
                            errMsg
                        )
                    }
                }
            }
        }
    }

    private fun emitBubbleActiveAfterProcessing() {
        NativeEventBridge.emit(
            eventName = "bubbleStatusChanged",
            data = mapOf(
                "isRunning" to FloatingBubbleService.isRunning,
                "bubbleState" to if (FloatingBubbleService.isRunning) "active" else "stopped",
                "isLoading" to false
            )
        )
    }

    /**
     * Builds the current permission status map using any Android [Context].
     */
    fun buildPermissionStatusMap(context: Context): Map<String, String> {
        val appContext = context.applicationContext
        val overlayGranted = Settings.canDrawOverlays(appContext)
        val captureActive = MediaProjectionService.isCaptureSessionActive
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return mapOf(
            "overlayPermission" to if (overlayGranted) "granted" else "not_granted",
            "screenCapturePermission" to if (captureActive) "granted" else "action_required",
            "notificationPermission" to if (notificationGranted) "granted" else "not_granted"
        )
    }

    /**
     * Releases ML Kit OCR and Translation native resources when all GameLingo services
     * and background model downloads are stopped.
     */
    fun releaseResourcesIfIdle() {
        if (!FloatingBubbleService.isRunning &&
            !MediaProjectionService.isCaptureSessionActive &&
            !translationManager.isDownloadingAnyModel
        ) {
            japaneseOcrManager.close()
            translationManager.close()
        }
    }

    /**
     * Unconditionally closes ML Kit OCR and Translation clients.
     */
    fun forceReleaseAll() {
        japaneseOcrManager.close()
        translationManager.close()
    }
}

