package com.gamelingo.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gamelingo.app.managers.TranslationPipelineCoordinator
import com.gamelingo.app.services.FloatingBubbleService
import com.gamelingo.app.services.FloatingPanelService
import com.gamelingo.app.services.MediaProjectionService
import com.gamelingo.app.services.NativeEventBridge

/**
 * BroadcastReceiver that stops [FloatingBubbleService], [FloatingPanelService], and
 * [MediaProjectionService] when triggered from the persistent notification action.
 */
class StopServiceReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP_BUBBLE_SERVICE = "com.gamelingo.app.ACTION_STOP_BUBBLE_SERVICE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_STOP_BUBBLE_SERVICE) {
            val stopBubbleIntent = Intent(context, FloatingBubbleService::class.java)
            context.stopService(stopBubbleIntent)
            FloatingPanelService.closePanel(context)

            val stopCaptureIntent = Intent(context, MediaProjectionService::class.java).apply {
                action = MediaProjectionService.ACTION_STOP_PROJECTION
            }
            context.stopService(stopCaptureIntent)

            NativeEventBridge.emit(
                eventName = "bubbleStatusChanged",
                data = mapOf(
                    "isRunning" to false,
                    "bubbleState" to "stopped",
                    "reason" to "notification_stop_action"
                )
            )
            TranslationPipelineCoordinator.releaseResourcesIfIdle()
        }
    }
}

