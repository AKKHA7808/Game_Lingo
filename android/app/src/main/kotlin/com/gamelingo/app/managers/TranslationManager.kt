package com.gamelingo.app.managers

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.gamelingo.app.models.TranslationResult
import com.gamelingo.app.services.NativeEventBridge
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages Google ML Kit On-Device Translation for Japanese (`ja`) to Thai (`th`)
 * and English (`en`).
 *
 * Key guarantees:
 * - Strictly separated from [JapaneseOcrManager] so the translation engine can be swapped in the future.
 * - Reuses [Translator] instances per language pair rather than recreating them on every call.
 * - Never reports a model as `downloaded` until the ML Kit API confirms completion.
 * - Supports Wi-Fi-only download by default and optional cellular download when enabled by the user.
 * - Runs completely on-device once models are downloaded; never calls Cloud Translation APIs.
 */
class TranslationManager {

    companion object {
        const val LANG_JA = TranslateLanguage.JAPANESE
        const val LANG_TH = TranslateLanguage.THAI
        const val LANG_EN = TranslateLanguage.ENGLISH
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val modelManager: RemoteModelManager = RemoteModelManager.getInstance()

    private val translators = ConcurrentHashMap<String, Translator>()
    private val downloadingStates = ConcurrentHashMap<String, Boolean>()
    private val failedStates = ConcurrentHashMap<String, Boolean>()

    @Synchronized
    private fun ensureExecutor(): ExecutorService {
        if (backgroundExecutor.isShutdown || backgroundExecutor.isTerminated) {
            backgroundExecutor = Executors.newSingleThreadExecutor()
        }
        return backgroundExecutor
    }

    /**
     * Returns a cached [Translator] instance for `ja` -> [targetLangCode], creating it only once.
     */
    private fun getOrCreateTranslator(targetLangCode: String): Translator {
        val normalizedTarget = normalizeTargetLanguage(targetLangCode)
        return translators.getOrPut(normalizedTarget) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(LANG_JA)
                .setTargetLanguage(normalizedTarget)
                .build()
            Translation.getClient(options)
        }
    }

    private fun normalizeTargetLanguage(code: String): String {
        return when (code.lowercase().trim()) {
            "en", TranslateLanguage.ENGLISH -> LANG_EN
            "th", TranslateLanguage.THAI -> LANG_TH
            else -> LANG_TH
        }
    }

    /**
     * Queries ML Kit's [RemoteModelManager] to check the real on-device download status
     * for both `Japanese -> Thai` and `Japanese -> English` models.
     */
    fun getModelStatusMap(onResult: (Map<String, Any?>) -> Unit) {
        ensureExecutor().execute {
            try {
                val jaModel = TranslateRemoteModel.Builder(LANG_JA).build()
                val thModel = TranslateRemoteModel.Builder(LANG_TH).build()
                val enModel = TranslateRemoteModel.Builder(LANG_EN).build()

                val isJaDownloaded = Tasks.await(modelManager.isModelDownloaded(jaModel))
                val isThDownloaded = Tasks.await(modelManager.isModelDownloaded(thModel))
                val isEnDownloaded = Tasks.await(modelManager.isModelDownloaded(enModel))

                val thPairState = when {
                    downloadingStates[LANG_TH] == true -> "downloading"
                    isJaDownloaded && isThDownloaded -> "downloaded"
                    failedStates[LANG_TH] == true -> "failed"
                    else -> "not_downloaded"
                }

                val enPairState = when {
                    downloadingStates[LANG_EN] == true -> "downloading"
                    isJaDownloaded && isEnDownloaded -> "downloaded"
                    failedStates[LANG_EN] == true -> "failed"
                    else -> "not_downloaded"
                }

                val statusMap = mapOf<String, Any?>(
                    "th" to thPairState,
                    "en" to enPairState
                )
                mainHandler.post { onResult(statusMap) }
            } catch (e: Exception) {
                val fallbackMap = mapOf<String, Any?>(
                    "th" to if (failedStates[LANG_TH] == true) "failed" else "not_downloaded",
                    "en" to if (failedStates[LANG_EN] == true) "failed" else "not_downloaded",
                    "errorMessage" to e.localizedMessage
                )
                mainHandler.post { onResult(fallbackMap) }
            }
        }
    }

    /**
     * Downloads the required on-device ML Kit models (`ja` + [targetLangCode]).
     * Requires Wi-Fi by default unless [allowCellular] is true.
     * Never reports success unless `downloadModelIfNeeded` completes AND `RemoteModelManager`
     * verifies the models exist on disk.
     */
    fun downloadModel(
        targetLangCode: String,
        allowCellular: Boolean,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val target = normalizeTargetLanguage(targetLangCode)
        if (downloadingStates[target] == true) {
            onComplete(false, "กำลังดาวน์โหลดโมเดลภาษานี้อยู่แล้ว กรุณารอสักครู่")
            return
        }

        downloadingStates[target] = true
        failedStates[target] = false

        val conditionsBuilder = DownloadConditions.Builder()
        if (!allowCellular) {
            conditionsBuilder.requireWifi()
        }
        val conditions = conditionsBuilder.build()

        val translator = getOrCreateTranslator(target)
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                // Verify with RemoteModelManager that both source (ja) and target models are truly present.
                ensureExecutor().execute {
                    try {
                        val jaModel = TranslateRemoteModel.Builder(LANG_JA).build()
                        val targetModel = TranslateRemoteModel.Builder(target).build()
                        val jaReady = Tasks.await(modelManager.isModelDownloaded(jaModel))
                        val targetReady = Tasks.await(modelManager.isModelDownloaded(targetModel))

                        downloadingStates[target] = false
                        if (jaReady && targetReady) {
                            failedStates[target] = false
                            mainHandler.post { onComplete(true, null) }
                        } else {
                            failedStates[target] = true
                            mainHandler.post {
                                onComplete(
                                    false,
                                    "ระบบยังติดตั้งไฟล์โมเดลแปลภาษาไม่ครบถ้วน กรุณาตรวจสอบการเชื่อมต่ออินเทอร์เน็ตแล้วลองใหม่"
                                )
                            }
                        }
                    } catch (e: Exception) {
                        downloadingStates[target] = false
                        failedStates[target] = true
                        mainHandler.post {
                            onComplete(
                                false,
                                "เกิดข้อผิดพลาดในการตรวจสอบไฟล์โมเดลหลังดาวน์โหลด: ${e.localizedMessage}"
                            )
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                downloadingStates[target] = false
                failedStates[target] = true
                val message = if (!allowCellular) {
                    "ดาวน์โหลดโมเดลแปลภาษาไม่สำเร็จ กรุณาตรวจสอบการเชื่อมต่อ Wi-Fi หรือเปิดอนุญาตดาวน์โหลดผ่านเครือข่ายมือถือ (${e.localizedMessage})"
                } else {
                    "ดาวน์โหลดโมเดลแปลภาษาไม่สำเร็จ กรุณาตรวจสอบการเชื่อมต่ออินเทอร์เน็ต (${e.localizedMessage})"
                }
                mainHandler.post { onComplete(false, message) }
            }
    }

    /**
     * Translates Japanese [japaneseText] into [targetLangCode] (`th` or `en`) strictly on-device.
     * Verifies that the required translation model is downloaded first.
     */
    fun translateJapaneseText(
        japaneseText: String,
        targetLangCode: String,
        onComplete: (TranslationResult) -> Unit
    ) {
        val cleanedInput = japaneseText.trim()
        val target = normalizeTargetLanguage(targetLangCode)

        if (cleanedInput.isEmpty()) {
            val emptyResult = TranslationResult(
                originalText = "",
                translatedText = "",
                targetLanguage = target,
                isSuccess = false,
                errorCode = "EMPTY_OCR_TEXT",
                errorMessage = "ไม่มีข้อความภาษาญี่ปุ่นสำหรับแปล"
            )
            NativeEventBridge.emit(
                eventName = "translationFailed",
                data = emptyResult.toMap(),
                errorMessage = emptyResult.errorMessage
            )
            mainHandler.post { onComplete(emptyResult) }
            return
        }

        NativeEventBridge.emit(
            eventName = "translationStarted",
            data = mapOf(
                "sourceLanguage" to LANG_JA,
                "targetLanguage" to target,
                "originalText" to cleanedInput
            )
        )

        val startTime = SystemClock.elapsedRealtime()
        ensureExecutor().execute {
            try {
                // Step 1: Verify on-device models are downloaded before attempting translation.
                val jaModel = TranslateRemoteModel.Builder(LANG_JA).build()
                val targetModel = TranslateRemoteModel.Builder(target).build()
                val jaDownloaded = Tasks.await(modelManager.isModelDownloaded(jaModel))
                val targetDownloaded = Tasks.await(modelManager.isModelDownloaded(targetModel))

                if (!jaDownloaded || !targetDownloaded) {
                    val langLabel = if (target == LANG_TH) "Japanese → Thai" else "Japanese → English"
                    val missingModelResult = TranslationResult(
                        originalText = cleanedInput,
                        translatedText = "",
                        targetLanguage = target,
                        processingTimeMs = SystemClock.elapsedRealtime() - startTime,
                        isSuccess = false,
                        errorCode = "MODEL_NOT_DOWNLOADED",
                        errorMessage = "ยังไม่ได้ดาวน์โหลดโมเดลแปลภาษา ($langLabel) กรุณาดาวน์โหลดโมเดลในแอป GameLingo ก่อนเริ่มแปล"
                    )
                    NativeEventBridge.emit(
                        eventName = "translationFailed",
                        data = missingModelResult.toMap(),
                        errorMessage = missingModelResult.errorMessage
                    )
                    mainHandler.post { onComplete(missingModelResult) }
                    return@execute
                }

                // Step 2: Translate line-by-line or full block using the cached on-device Translator.
                val translator = getOrCreateTranslator(target)
                val lines = cleanedInput.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                val translatedLines = ArrayList<String>(lines.size)

                for (line in lines) {
                    val translatedLine = Tasks.await(translator.translate(line))
                    if (!translatedLine.isNullOrBlank()) {
                        translatedLines.add(translatedLine.trim())
                    }
                }

                val combinedTranslation = translatedLines.joinToString(separator = "\n").trim()
                val elapsed = SystemClock.elapsedRealtime() - startTime

                if (combinedTranslation.isEmpty()) {
                    val emptyOutResult = TranslationResult(
                        originalText = cleanedInput,
                        translatedText = "",
                        targetLanguage = target,
                        processingTimeMs = elapsed,
                        isSuccess = false,
                        errorCode = "TRANSLATION_EMPTY_OUTPUT",
                        errorMessage = "ระบบแปลภาษาไม่สามารถแปลข้อความนี้ได้"
                    )
                    NativeEventBridge.emit(
                        eventName = "translationFailed",
                        data = emptyOutResult.toMap(),
                        errorMessage = emptyOutResult.errorMessage
                    )
                    mainHandler.post { onComplete(emptyOutResult) }
                    return@execute
                }

                val successResult = TranslationResult(
                    originalText = cleanedInput,
                    translatedText = combinedTranslation,
                    sourceLanguage = LANG_JA,
                    targetLanguage = target,
                    processingTimeMs = elapsed,
                    isSuccess = true
                )
                NativeEventBridge.emit(
                    eventName = "translationCompleted",
                    data = successResult.toMap()
                )
                mainHandler.post { onComplete(successResult) }
            } catch (e: Exception) {
                val elapsed = SystemClock.elapsedRealtime() - startTime
                val failResult = TranslationResult(
                    originalText = cleanedInput,
                    translatedText = "",
                    targetLanguage = target,
                    processingTimeMs = elapsed,
                    isSuccess = false,
                    errorCode = "TRANSLATION_API_ERROR",
                    errorMessage = "เกิดข้อผิดพลาดในการแปลภาษาแบบ On-Device: ${e.localizedMessage}"
                )
                NativeEventBridge.emit(
                    eventName = "translationFailed",
                    data = failResult.toMap(),
                    errorMessage = failResult.errorMessage
                )
                mainHandler.post { onComplete(failResult) }
            }
        }
    }

    /**
     * Closes all cached [Translator] instances and shuts down the background executor.
     */
    @Synchronized
    fun close() {
        for ((_, translator) in translators) {
            try {
                translator.close()
            } catch (_: Exception) {
            }
        }
        translators.clear()
        downloadingStates.clear()

        try {
            backgroundExecutor.shutdownNow()
        } catch (_: Exception) {
        }
    }
}

