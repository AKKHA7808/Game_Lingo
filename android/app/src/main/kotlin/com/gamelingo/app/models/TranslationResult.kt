package com.gamelingo.app.models

/**
 * Native model representing the result of an on-device Japanese translation cycle.
 */
data class TranslationResult(
    val originalText: String,
    val translatedText: String,
    val sourceLanguage: String = "ja",
    val targetLanguage: String,
    val processingTimeMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val isSuccess: Boolean,
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "originalText" to originalText,
            "translatedText" to translatedText,
            "sourceLanguage" to sourceLanguage,
            "targetLanguage" to targetLanguage,
            "processingTimeMs" to processingTimeMs,
            "timestamp" to timestamp,
            "isSuccess" to isSuccess,
            "errorCode" to errorCode,
            "errorMessage" to errorMessage
        )
    }
}

