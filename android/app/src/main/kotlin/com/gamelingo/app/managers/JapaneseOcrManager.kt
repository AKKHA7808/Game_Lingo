package com.gamelingo.app.managers

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.gamelingo.app.services.NativeEventBridge
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Result returned from a single on-device Japanese OCR pass.
 */
data class OcrRecognitionResult(
    val extractedText: String,
    val lineCount: Int,
    val blockCount: Int,
    val japaneseCharCount: Int,
    val processingTimeMs: Long,
    val isSuccess: Boolean,
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    fun toEventMetadata(): Map<String, Any?> {
        return mapOf(
            "originalText" to extractedText,
            "lineCount" to lineCount,
            "blockCount" to blockCount,
            "japaneseCharCount" to japaneseCharCount,
            "processingTimeMs" to processingTimeMs,
            "isSuccess" to isSuccess,
            "errorCode" to errorCode,
            "errorMessage" to errorMessage
        )
    }
}

/**
 * Manages Google ML Kit Text Recognition v2 for Japanese script
 * ([JapaneseTextRecognizerOptions] bundled model).
 *
 * Responsibilities:
 * - Preprocesses/scales high-resolution game screenshots on a background thread when needed.
 * - Recognizes Hiragana, Katakana, and Kanji from game dialogue boxes, menus, and HUDs.
 * - Orders detected text lines naturally (top-to-bottom, left-to-right), strips blank lines
 *   and duplicates, and recycles in-memory Bitmaps immediately after recognition.
 */
class JapaneseOcrManager {

    companion object {
        private const val MAX_OCR_IMAGE_DIMENSION = 2200
        private const val VERTICAL_BAND_THRESHOLD_PX = 28

        /**
         * Matches Hiragana (\u3040-\u309F), Katakana (\u30A0-\u30FF), CJK Unified Ideographs / Kanji
         * (\u4E00-\u9FFF, \u3400-\u4DBF), and Half-width Katakana (\uFF66-\uFF9F).
         */
        private val JAPANESE_SCRIPT_REGEX =
            Regex("[\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF\\u3400-\\u4DBF\\uFF66-\\uFF9F]")

        fun containsJapaneseScript(text: String): Boolean {
            return JAPANESE_SCRIPT_REGEX.containsMatchIn(text)
        }

        fun countJapaneseCharacters(text: String): Int {
            return JAPANESE_SCRIPT_REGEX.findAll(text).count()
        }
    }

    private data class PositionedTextLine(
        val text: String,
        val boundingBox: Rect?
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var recognizer: TextRecognizer? = null

    @Synchronized
    private fun getOrCreateRecognizer(): TextRecognizer {
        var current = recognizer
        if (current == null) {
            val options = JapaneseTextRecognizerOptions.Builder().build()
            current = TextRecognition.getClient(options)
            recognizer = current
        }
        return current
    }

    @Synchronized
    private fun ensureExecutor(): ExecutorService {
        if (backgroundExecutor.isShutdown || backgroundExecutor.isTerminated) {
            backgroundExecutor = Executors.newSingleThreadExecutor()
        }
        return backgroundExecutor
    }

    /**
     * Recognizes Japanese text from [sourceBitmap] on a background thread and invokes [onComplete]
     * on the main thread. Always recycles [sourceBitmap] when finished.
     */
    fun recognizeJapaneseFromBitmap(
        sourceBitmap: Bitmap,
        onComplete: (OcrRecognitionResult) -> Unit
    ) {
        if (sourceBitmap.isRecycled || sourceBitmap.width <= 0 || sourceBitmap.height <= 0) {
            val invalidResult = OcrRecognitionResult(
                extractedText = "",
                lineCount = 0,
                blockCount = 0,
                japaneseCharCount = 0,
                processingTimeMs = 0L,
                isSuccess = false,
                errorCode = "OCR_INVALID_BITMAP",
                errorMessage = "ภาพหน้าจอไม่ถูกต้องหรือถูกคืนหน่วยความจำไปแล้ว"
            )
            NativeEventBridge.emit(
                eventName = "translationFailed",
                data = invalidResult.toEventMetadata(),
                errorMessage = invalidResult.errorMessage
            )
            mainHandler.post { onComplete(invalidResult) }
            return
        }

        NativeEventBridge.emit(
            eventName = "ocrStarted",
            data = mapOf(
                "width" to sourceBitmap.width,
                "height" to sourceBitmap.height
            )
        )

        val startTime = SystemClock.elapsedRealtime()
        ensureExecutor().execute {
            var workingBitmap: Bitmap = sourceBitmap
            var scaledBitmapCreated = false

            try {
                // Resize ultra-high-resolution screenshots if necessary to optimize OCR latency and RAM.
                val maxDim = max(sourceBitmap.width, sourceBitmap.height)
                if (maxDim > MAX_OCR_IMAGE_DIMENSION) {
                    val scale = MAX_OCR_IMAGE_DIMENSION.toFloat() / maxDim.toFloat()
                    val targetW = max(1, (sourceBitmap.width * scale).roundToInt())
                    val targetH = max(1, (sourceBitmap.height * scale).roundToInt())
                    workingBitmap = Bitmap.createScaledBitmap(sourceBitmap, targetW, targetH, true)
                    scaledBitmapCreated = workingBitmap !== sourceBitmap
                }

                val inputImage = InputImage.fromBitmap(workingBitmap, 0)
                val client = getOrCreateRecognizer()

                client.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        ensureExecutor().execute {
                            try {
                                val elapsed = SystemClock.elapsedRealtime() - startTime
                                val result = parseVisionText(visionText, elapsed)
                                recycleBitmapsSafely(sourceBitmap, workingBitmap, scaledBitmapCreated)

                                if (result.isSuccess) {
                                    NativeEventBridge.emit(
                                        eventName = "ocrCompleted",
                                        data = result.toEventMetadata()
                                    )
                                } else {
                                    NativeEventBridge.emit(
                                        eventName = "translationFailed",
                                        data = result.toEventMetadata(),
                                        errorMessage = result.errorMessage
                                    )
                                }
                                mainHandler.post { onComplete(result) }
                            } catch (e: Exception) {
                                recycleBitmapsSafely(sourceBitmap, workingBitmap, scaledBitmapCreated)
                                val errResult = OcrRecognitionResult(
                                    extractedText = "",
                                    lineCount = 0,
                                    blockCount = 0,
                                    japaneseCharCount = 0,
                                    processingTimeMs = SystemClock.elapsedRealtime() - startTime,
                                    isSuccess = false,
                                    errorCode = "OCR_PARSE_ERROR",
                                    errorMessage = "เกิดข้อผิดพลาดในการจัดเรียงข้อความภาษาญี่ปุ่น: ${e.localizedMessage}"
                                )
                                NativeEventBridge.emit(
                                    eventName = "translationFailed",
                                    data = errResult.toEventMetadata(),
                                    errorMessage = errResult.errorMessage
                                )
                                mainHandler.post { onComplete(errResult) }
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        recycleBitmapsSafely(sourceBitmap, workingBitmap, scaledBitmapCreated)
                        val elapsed = SystemClock.elapsedRealtime() - startTime
                        val failResult = OcrRecognitionResult(
                            extractedText = "",
                            lineCount = 0,
                            blockCount = 0,
                            japaneseCharCount = 0,
                            processingTimeMs = elapsed,
                            isSuccess = false,
                            errorCode = "OCR_PROCESS_FAILED",
                            errorMessage = "การตรวจจับข้อความภาษาญี่ปุ่น (OCR) ล้มเหลว: ${e.localizedMessage}"
                        )
                        NativeEventBridge.emit(
                            eventName = "translationFailed",
                            data = failResult.toEventMetadata(),
                            errorMessage = failResult.errorMessage
                        )
                        mainHandler.post { onComplete(failResult) }
                    }
            } catch (e: Exception) {
                recycleBitmapsSafely(sourceBitmap, workingBitmap, scaledBitmapCreated)
                val elapsed = SystemClock.elapsedRealtime() - startTime
                val failResult = OcrRecognitionResult(
                    extractedText = "",
                    lineCount = 0,
                    blockCount = 0,
                    japaneseCharCount = 0,
                    processingTimeMs = elapsed,
                    isSuccess = false,
                    errorCode = "OCR_EXECUTION_ERROR",
                    errorMessage = "ไม่สามารถประมวลผลภาพสำหรับ Japanese OCR ได้: ${e.localizedMessage}"
                )
                NativeEventBridge.emit(
                    eventName = "translationFailed",
                    data = failResult.toEventMetadata(),
                    errorMessage = failResult.errorMessage
                )
                mainHandler.post { onComplete(failResult) }
            }
        }
    }

    /**
     * Extracts, sorts, filters, and deduplicates Japanese lines from ML Kit's [Text] output.
     */
    private fun parseVisionText(visionText: Text, elapsedMs: Long): OcrRecognitionResult {
        val rawBlocks = visionText.textBlocks
        if (rawBlocks.isEmpty()) {
            return OcrRecognitionResult(
                extractedText = "",
                lineCount = 0,
                blockCount = 0,
                japaneseCharCount = 0,
                processingTimeMs = elapsedMs,
                isSuccess = false,
                errorCode = "OCR_NO_TEXT_FOUND",
                errorMessage = "ไม่พบข้อความภาษาญี่ปุ่นบนหน้าจอเกม"
            )
        }

        val positionedLines = ArrayList<PositionedTextLine>()
        var japaneseBlockCount = 0

        for (block in rawBlocks) {
            var blockHasJapanese = false
            for (line in block.lines) {
                val cleaned = normalizeSingleLine(line.text)
                if (cleaned.isNotEmpty() && containsJapaneseScript(cleaned)) {
                    positionedLines.add(PositionedTextLine(cleaned, line.boundingBox))
                    blockHasJapanese = true
                }
            }
            if (blockHasJapanese) {
                japaneseBlockCount += 1
            }
        }

        // Sort lines in reading order: top-to-bottom (grouped into horizontal bands), then left-to-right.
        positionedLines.sortWith { a, b ->
            val boxA = a.boundingBox
            val boxB = b.boundingBox
            if (boxA != null && boxB != null) {
                val topDiff = boxA.top - boxB.top
                if (kotlin.math.abs(topDiff) <= VERTICAL_BAND_THRESHOLD_PX) {
                    boxA.left.compareTo(boxB.left)
                } else {
                    topDiff
                }
            } else {
                0
            }
        }

        // Deduplicate empty and repeated lines while preserving natural multi-line order.
        val uniqueOrderedLines = LinkedHashSet<String>()
        for (item in positionedLines) {
            if (item.text.isNotBlank()) {
                uniqueOrderedLines.add(item.text)
            }
        }

        // Filter HUD chrome noise (e.g. "オート", "スキップ", "ログ") when real dialogue exists
        // and reconstruct wrapped dialogue sentences so SOV grammar stays intact.
        val refinedLines = JapaneseGameTextRefiner.cleanAndReconstructDialogue(
            uniqueOrderedLines.toList()
        )
        val combinedText = refinedLines.joinToString(separator = "\n").trim()
        val jpCharCount = countJapaneseCharacters(combinedText)

        if (combinedText.isEmpty() || jpCharCount == 0) {
            return OcrRecognitionResult(
                extractedText = "",
                lineCount = 0,
                blockCount = rawBlocks.size,
                japaneseCharCount = 0,
                processingTimeMs = elapsedMs,
                isSuccess = false,
                errorCode = "OCR_NO_JAPANESE_TEXT",
                errorMessage = "ไม่พบข้อความภาษาญี่ปุ่น (Hiragana / Katakana / Kanji) บนหน้าจอเกม"
            )
        }

        return OcrRecognitionResult(
            extractedText = combinedText,
            lineCount = refinedLines.size,
            blockCount = japaneseBlockCount,
            japaneseCharCount = jpCharCount,
            processingTimeMs = elapsedMs,
            isSuccess = true
        )
    }

    private fun normalizeSingleLine(rawLine: String): String {
        return JapaneseGameTextRefiner.normalizeOcrLine(rawLine)
    }

    private fun recycleBitmapsSafely(
        sourceBitmap: Bitmap,
        workingBitmap: Bitmap,
        scaledBitmapCreated: Boolean
    ) {
        try {
            if (scaledBitmapCreated && !workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
        } catch (_: Exception) {
        }
        try {
            if (!sourceBitmap.isRecycled) {
                sourceBitmap.recycle()
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun close() {
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        recognizer = null

        try {
            backgroundExecutor.shutdownNow()
        } catch (_: Exception) {
        }
    }
}

