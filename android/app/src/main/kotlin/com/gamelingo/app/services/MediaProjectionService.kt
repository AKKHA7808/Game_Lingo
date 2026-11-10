package com.gamelingo.app.services

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.gamelingo.app.MainActivity
import com.gamelingo.app.managers.ScreenCaptureManager
import com.gamelingo.app.managers.TranslationPipelineCoordinator
import com.gamelingo.app.models.CaptureResult

/**
 * Foreground Service with `foregroundServiceType="mediaProjection"` that hosts
 * [ScreenCaptureManager] in strict compliance with Android 10–15+ requirements.
 */
class MediaProjectionService : Service() {

    companion object {
        const val ACTION_START_PROJECTION = "com.gamelingo.app.action.START_PROJECTION"
        const val ACTION_STOP_PROJECTION = "com.gamelingo.app.action.STOP_PROJECTION"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "gamelingo_media_projection_channel"
        private const val NOTIFICATION_ID = 1002

        @Volatile
        private var instance: MediaProjectionService? = null

        val isCaptureSessionActive: Boolean
            get() = instance?.screenCaptureManager?.isSessionActive == true

        /**
         * Requests a single frame from the active MediaProjection session.
         */
        fun captureLatestScreenFrame(onResult: (CaptureResult) -> Unit) {
            val activeService = instance
            if (activeService == null || !activeService.screenCaptureManager.isSessionActive) {
                val failResult = CaptureResult(
                    bitmap = null,
                    width = 0,
                    height = 0,
                    isSuccess = false,
                    errorCode = "PROJECTION_SERVICE_NOT_RUNNING",
                    errorMessage = "ยังไม่ได้เปิดใช้งานการจับภาพหน้าจอ (MediaProjection) กรุณาอนุญาตสิทธิ์ก่อนเริ่มแปล"
                )
                NativeEventBridge.emit(
                    eventName = "translationFailed",
                    data = failResult.toEventMetadata(),
                    errorMessage = failResult.errorMessage
                )
                onResult(failResult)
                return
            }
            activeService.screenCaptureManager.captureSingleFrame(onResult)
        }
    }

    lateinit var screenCaptureManager: ScreenCaptureManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        screenCaptureManager = ScreenCaptureManager(applicationContext).apply {
            onSystemRevokedListener = {
                stopProjectionAndSelf("system_revoked_or_stopped")
            }
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_PROJECTION -> {
                stopProjectionAndSelf("explicit_stop")
                return START_NOT_STICKY
            }

            ACTION_START_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    NativeEventBridge.emit(
                        eventName = "translationFailed",
                        data = mapOf("reason" to "invalid_projection_token"),
                        errorMessage = "ไม่พบข้อมูลการอนุญาตจับภาพหน้าจอ กรุณากดอนุญาตใหม่อีกครั้ง"
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Step 1: Must promote to Foreground Service with MEDIA_PROJECTION type BEFORE calling getMediaProjection.
                val notification = buildForegroundNotification()
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } catch (e: Exception) {
                    NativeEventBridge.emit(
                        eventName = "translationFailed",
                        data = mapOf("reason" to "foreground_service_start_failed"),
                        errorMessage = "ไม่สามารถเริ่ม Foreground Service สำหรับจับภาพหน้าจอได้: ${e.localizedMessage}"
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Step 2: Initialize MediaProjection, VirtualDisplay, and ImageReader.
                val started = screenCaptureManager.startSession(resultCode, resultData)
                if (!started) {
                    stopProjectionAndSelf("session_start_failed")
                    return START_NOT_STICKY
                }
                return START_NOT_STICKY
            }

            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        screenCaptureManager.checkAndUpdateDisplayOrientation()
    }

    private fun stopProjectionAndSelf(reason: String) {
        screenCaptureManager.releaseResources(notifyStopped = true, reason = reason)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GameLingo Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "แสดงสถานะระบบจับภาพหน้าจอเกมเพื่อแปลภาษา (ทำงานเฉพาะเมื่อแตะปุ่ม Bubble)"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MediaProjectionService::class.java).apply {
            action = ACTION_STOP_PROJECTION
        }
        val pendingStop = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("GameLingo Screen Capture พร้อมทำงาน")
            .setContentText("จับภาพเฉพาะเมื่อแตะปุ่ม Bubble เท่านั้น ไม่บันทึกภาพลงเครื่อง")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "หยุดจับภาพ",
                pendingStop
            )
            .build()
    }

    override fun onDestroy() {
        screenCaptureManager.releaseResources(notifyStopped = true, reason = "service_destroyed")
        instance = null
        TranslationPipelineCoordinator.releaseResourcesIfIdle()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

