package com.gamelingo.app.models

import android.graphics.Bitmap

/**
 * Holds the in-memory result of a single screen capture operation.
 * Bitmaps are never written to persistent storage by default.
 */
data class CaptureResult(
    val bitmap: Bitmap?,
    val width: Int,
    val height: Int,
    val byteCount: Int = 0,
    val samplePixelHex: String? = null,
    val captureCountInSession: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val isSuccess: Boolean,
    val isFlagSecureSuspected: Boolean = false,
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    fun toEventMetadata(): Map<String, Any?> {
        return mapOf(
            "width" to width,
            "height" to height,
            "byteCount" to byteCount,
            "samplePixelHex" to samplePixelHex,
            "captureCountInSession" to captureCountInSession,
            "timestamp" to timestamp,
            "isSuccess" to isSuccess,
            "isFlagSecureSuspected" to isFlagSecureSuspected,
            "errorCode" to errorCode,
            "errorMessage" to errorMessage
        )
    }
}
