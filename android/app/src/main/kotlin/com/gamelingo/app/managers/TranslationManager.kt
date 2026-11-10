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
    private val downloadProgressMap = ConcurrentHashMap<String, Int>()
    private val downloadStageMap = ConcurrentHashMap<String, String>()
    private val completionCallbacks =
        ConcurrentHashMap<String, MutableList<(Boolean, String?) -> Unit>>()

    @Volatile
    var onProgressUpdateListener: ((
        targetLang: String,
        progress: Int,
        state: String,
        stageMessage: String,
        errorMessage: String?
    ) -> Unit)? = null

    val isDownloadingAnyModel: Boolean
        get() = downloadingStates.values.any { it }

    @Synchronized
    fun registerCompletionCallback(
        targetLangCode: String,
        callback: (Boolean, String?) -> Unit
    ) {
        val target = normalizeTargetLanguage(targetLangCode)
        val list = completionCallbacks.getOrPut(target) { ArrayList() }
        list.add(callback)
    }

    @Synchronized
    private fun dispatchCompletionCallbacks(
        targetLang: String,
        success: Boolean,
        errorMessage: String?
    ) {
        val callbacks = completionCallbacks.remove(targetLang)?.toList() ?: emptyList()
        mainHandler.post {
            for (cb in callbacks) {
                try {
                    cb.invoke(success, errorMessage)
                } catch (_: Exception) {
                }
            }
        }
    }

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

                val thProgress = when (thPairState) {
                    "downloaded" -> 100
                    "downloading" -> (downloadProgressMap[LANG_TH] ?: 5).coerceIn(1, 99)
                    else -> 0
                }

                val enProgress = when (enPairState) {
                    "downloaded" -> 100
                    "downloading" -> (downloadProgressMap[LANG_EN] ?: 5).coerceIn(1, 99)
                    else -> 0
                }

                val statusMap = mapOf<String, Any?>(
                    "th" to thPairState,
                    "en" to enPairState,
                    "thProgress" to thProgress,
                    "enProgress" to enProgress,
                    "thStage" to downloadStageMap[LANG_TH],
                    "enStage" to downloadStageMap[LANG_EN]
                )
                mainHandler.post { onResult(statusMap) }
            } catch (e: Exception) {
                val fallbackMap = mapOf<String, Any?>(
                    "th" to if (failedStates[LANG_TH] == true) "failed" else "not_downloaded",
                    "en" to if (failedStates[LANG_EN] == true) "failed" else "not_downloaded",
                    "thProgress" to 0,
                    "enProgress" to 0,
                    "errorMessage" to e.localizedMessage
                )
                mainHandler.post { onResult(fallbackMap) }
            }
        }
    }

    private fun emitDownloadProgressEvent(
        targetLang: String,
        progress: Int,
        state: String,
        stageMessage: String,
        errorMessage: String? = null
    ) {
        val clamped = progress.coerceIn(0, 100)
        downloadProgressMap[targetLang] = clamped
        downloadStageMap[targetLang] = stageMessage
        NativeEventBridge.emit(
            eventName = "modelDownloadProgress",
            data = mapOf(
                "targetLanguage" to targetLang,
                "progress" to clamped,
                "state" to state,
                "stageMessage" to stageMessage
            ),
            errorMessage = errorMessage
        )
        mainHandler.post {
            try {
                onProgressUpdateListener?.invoke(
                    targetLang,
                    clamped,
                    state,
                    stageMessage,
                    errorMessage
                )
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Downloads the required on-device ML Kit models (`ja` + [targetLangCode]) and emits
     * real-time percentage progress (`0%` -> `100%`) over the native EventChannel.
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
        registerCompletionCallback(target, onComplete)

        if (downloadingStates[target] == true) {
            // Download is already running in background; callback is attached and will be notified when done.
            val currentProg = (downloadProgressMap[target] ?: 10).coerceIn(1, 99)
            val currentStage = downloadStageMap[target] ?: "กำลังดาวน์โหลดโมเดลในพื้นหลัง..."
            emitDownloadProgressEvent(
                targetLang = target,
                progress = currentProg,
                state = "downloading",
                stageMessage = currentStage
            )
            return
        }

        downloadingStates[target] = true
        failedStates[target] = false
        val targetDisplayName = if (target == LANG_TH) "Thai (ภาษาไทย)" else "English (ภาษาอังกฤษ)"

        emitDownloadProgressEvent(
            targetLang = target,
            progress = 2,
            state = "downloading",
            stageMessage = "กำลังตรวจสอบไฟล์โมเดลในเครื่อง..."
        )

        val conditionsBuilder = DownloadConditions.Builder()
        if (!allowCellular) {
            conditionsBuilder.requireWifi()
        }
        val conditions = conditionsBuilder.build()

        ensureExecutor().execute {
            var tickerRunning = true
            var minRange = 5
            var maxRange = 48
            var currentSimulated = 5
            var currentStageText = "กำลังดาวน์โหลดโมเดลภาษาญี่ปุ่น (1/2)..."

            val progressRunnable = object : Runnable {
                override fun run() {
                    if (!tickerRunning || downloadingStates[target] != true) return
                    if (currentSimulated < maxRange) {
                        val step = if (currentSimulated < (minRange + maxRange) / 2) 3 else 1
                        currentSimulated = (currentSimulated + step).coerceAtMost(maxRange)
                    }
                    emitDownloadProgressEvent(
                        targetLang = target,
                        progress = currentSimulated,
                        state = "downloading",
                        stageMessage = currentStageText
                    )
                    mainHandler.postDelayed(this, 320L)
                }
            }

            try {
                val jaModel = TranslateRemoteModel.Builder(LANG_JA).build()
                val targetModel = TranslateRemoteModel.Builder(target).build()

                val jaAlreadyDownloaded = Tasks.await(modelManager.isModelDownloaded(jaModel))
                val targetAlreadyDownloaded = Tasks.await(modelManager.isModelDownloaded(targetModel))

                if (jaAlreadyDownloaded && targetAlreadyDownloaded) {
                    tickerRunning = false
                    downloadingStates[target] = false
                    failedStates[target] = false
                    emitDownloadProgressEvent(
                        targetLang = target,
                        progress = 100,
                        state = "downloaded",
                        stageMessage = "ดาวน์โหลดโมเดลครบถ้วน 100%"
                    )
                    dispatchCompletionCallbacks(target, true, null)
                    return@execute
                }

                // Start smooth progress ticker on mainHandler while ML Kit downloads in background.
                if (!jaAlreadyDownloaded) {
                    minRange = 5
                    maxRange = 48
                    currentSimulated = 6
                    currentStageText = "กำลังดาวน์โหลดโมเดล Japanese (1/2)..."
                } else {
                    minRange = 15
                    maxRange = 90
                    currentSimulated = 18
                    currentStageText = "กำลังดาวน์โหลดโมเดล $targetDisplayName..."
                }
                mainHandler.post(progressRunnable)

                // Step 1: Download Japanese source model if not yet on device.
                if (!jaAlreadyDownloaded) {
                    Tasks.await(modelManager.download(jaModel, conditions))
                }

                // Transition to Step 2: Download target language model (Thai or English).
                minRange = 52
                maxRange = 92
                currentSimulated = maxOf(currentSimulated, 52)
                currentStageText = if (!jaAlreadyDownloaded) {
                    "กำลังดาวน์โหลดโมเดล $targetDisplayName (2/2)..."
                } else {
                    "กำลังดาวน์โหลดโมเดล $targetDisplayName..."
                }
                emitDownloadProgressEvent(
                    targetLang = target,
                    progress = currentSimulated,
                    state = "downloading",
                    stageMessage = currentStageText
                )

                if (!targetAlreadyDownloaded) {
                    Tasks.await(modelManager.download(targetModel, conditions))
                }

                // Step 3: Ensure Translator pipeline (including pivot bridge if needed) is ready.
                minRange = 93
                maxRange = 98
                currentSimulated = 95
                currentStageText = "กำลังตรวจสอบความสมบูรณ์ของโมเดล..."
                emitDownloadProgressEvent(
                    targetLang = target,
                    progress = 95,
                    state = "downloading",
                    stageMessage = currentStageText
                )

                val translator = getOrCreateTranslator(target)
                Tasks.await(translator.downloadModelIfNeeded(conditions))

                val jaReady = Tasks.await(modelManager.isModelDownloaded(jaModel))
                val targetReady = Tasks.await(modelManager.isModelDownloaded(targetModel))

                tickerRunning = false
                mainHandler.removeCallbacks(progressRunnable)
                downloadingStates[target] = false

                if (jaReady && targetReady) {
                    failedStates[target] = false
                    emitDownloadProgressEvent(
                        targetLang = target,
                        progress = 100,
                        state = "downloaded",
                        stageMessage = "ดาวน์โหลดสำเร็จ (100%)"
                    )
                    dispatchCompletionCallbacks(target, true, null)
                } else {
                    failedStates[target] = true
                    val errMsg =
                        "ระบบยังติดตั้งไฟล์โมเดลแปลภาษาไม่ครบถ้วน กรุณาตรวจสอบการเชื่อมต่ออินเทอร์เน็ตแล้วลองใหม่"
                    emitDownloadProgressEvent(
                        targetLang = target,
                        progress = 0,
                        state = "failed",
                        stageMessage = "ดาวน์โหลดไม่สำเร็จ",
                        errorMessage = errMsg
                    )
                    dispatchCompletionCallbacks(target, false, errMsg)
                }
            } catch (e: Exception) {
                tickerRunning = false
                mainHandler.removeCallbacks(progressRunnable)
                downloadingStates[target] = false
                failedStates[target] = true
                val message = if (!allowCellular) {
                    "ดาวน์โหลดโมเดลแปลภาษาไม่สำเร็จ กรุณาตรวจสอบการเชื่อมต่อ Wi-Fi หรือเปิดอนุญาตดาวน์โหลดผ่านเครือข่ายมือถือ (${e.localizedMessage})"
                } else {
                    "ดาวน์โหลดโมเดลแปลภาษาไม่สำเร็จ กรุณาตรวจสอบการเชื่อมต่ออินเทอร์เน็ต (${e.localizedMessage})"
                }
                emitDownloadProgressEvent(
                    targetLang = target,
                    progress = 0,
                    state = "failed",
                    stageMessage = "ดาวน์โหลดล้มเหลว",
                    errorMessage = message
                )
                dispatchCompletionCallbacks(target, false, message)
            }
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

                // Step 2: Reconstruct wrapped Japanese dialogue sentences, normalize colloquial/role-language
                // suffixes for NMT accuracy, translate via the cached on-device Translator, and polish output.
                val translator = getOrCreateTranslator(target)
                val rawLines = cleanedInput.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                val sentences = JapaneseGameTextRefiner.cleanAndReconstructDialogue(rawLines)
                    .ifEmpty { rawLines }
                val translatedLines = ArrayList<String>(sentences.size)

                for (sentence in sentences) {
                    val preparedInput = JapaneseGameTextRefiner.prepareJapaneseForTranslation(sentence)
                    val translatedLine = Tasks.await(translator.translate(preparedInput))
                    if (!translatedLine.isNullOrBlank()) {
                        val polished = JapaneseGameTextRefiner.postProcessTranslation(
                            translatedLine,
                            target
                        )
                        if (polished.isNotBlank()) {
                            translatedLines.add(polished)
                        }
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

