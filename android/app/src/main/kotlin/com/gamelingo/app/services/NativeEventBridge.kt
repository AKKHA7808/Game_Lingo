package com.gamelingo.app.services

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

/**
 * Thread-safe event bridge that dispatches native Android events to Flutter's EventChannel
 * on the main UI thread.
 */
object NativeEventBridge {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var eventSink: EventChannel.EventSink? = null

    /**
     * Optional callback invoked when the user taps the Floating Bubble to trigger translation.
     */
    @Volatile
    var onBubbleTapTranslateListener: (() -> Unit)? = null

    fun attachSink(sink: EventChannel.EventSink?) {
        eventSink = sink
    }

    fun detachSink() {
        eventSink = null
    }

    fun emit(
        eventName: String,
        data: Map<String, Any?> = emptyMap(),
        errorMessage: String? = null
    ) {
        val payload = HashMap<String, Any?>()
        payload["event"] = eventName
        payload["data"] = data
        if (errorMessage != null) {
            payload["error"] = errorMessage
        }
        payload["timestamp"] = System.currentTimeMillis()

        mainHandler.post {
            try {
                eventSink?.success(payload)
            } catch (_: Exception) {
                // Prevent crash if Flutter engine detaches while an event is in flight.
            }
        }
    }
}

