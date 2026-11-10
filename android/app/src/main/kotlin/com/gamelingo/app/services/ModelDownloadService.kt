package com.gamelingo.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gamelingo.app.MainActivity
import com.gamelingo.app.managers.TranslationPipelineCoordinator

/**
 * Foreground Service (`foregroundServiceType="dataSync"`) that keeps ML Kit On-Device
 * Translation model downloads running in the background even when the user minimizes
 * or swipes away the GameLingo app ([MainActivity]).
 *
 * Also displays a live percentage progress bar (`0%` -> `100%`) in the Android system
 * notification shade during download.
 */
class ModelDownloadService : Service() {

    companion object {
        const val ACTION_START_DOWNLOAD = "com.gamelingo.app.action.START_MODEL_DOWNLOAD"
        const val EXTRA_TARGET_LANG = "extra_target_lang"
        const val EXTRA_ALLOW_CELLULAR = "extra_allow_cellular"

        private const val CHANNEL_ID = "gamelingo_model_download_channel"
        private const val PROGRESS_NOTIFICATION_ID = 1003
        private const val RESULT_NOTIFICATION_ID = 1004

        fun startBackgroundDownload(
            context: Context,
            targetLanguage: String,
            allowCellular: Boolean,
            onComplete: ((Boolean, String?) -> Unit)? = null
        ) {
            val appContext = context.applicationContext
            // Register callback before starting service so caller receives completion if still alive.
            if (onComplete != null) {
                TranslationPipelineCoordinator.translationManager.registerCompletionCallback(
                    targetLanguage,
                    onComplete
                )
            }

            val intent = Intent(appContext, ModelDownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_TARGET_LANG, targetLanguage)
                putExtra(EXTRA_ALLOW_CELLULAR, allowCellular)
            }

            try {
                ContextCompat.startForegroundService(appContext, intent)
            } catch (_: Exception) {
                try {
                    appContext.startService(intent)
                } catch (_: Exception) {
                    // Fallback: run directly on coordinator executor if background service start is restricted.
                    TranslationPipelineCoordinator.translationManager.downloadModel(
                        targetLanguage,
                        allowCellular
                    ) { _, _ -> }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        TranslationPipelineCoordinator.translationManager.onProgressUpdateListener =
            { targetLang, progress, state, stageMessage, errorMessage ->
                handleProgressNotificationUpdate(
                    targetLang = targetLang,
                    progress = progress,
                    state = state,
                    stageMessage = stageMessage,
                    errorMessage = errorMessage
                )
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START_DOWNLOAD) {
            if (!TranslationPipelineCoordinator.translationManager.isDownloadingAnyModel) {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val targetLang = intent.getStringExtra(EXTRA_TARGET_LANG) ?: "th"
        val allowCellular = intent.getBooleanExtra(EXTRA_ALLOW_CELLULAR, false)
        val pairTitle = if (targetLang.lowercase() == "en") "Japanese → English" else "Japanese → Thai"

        val initialNotification = buildProgressNotification(
            title = "กำลังดาวน์โหลดโมเดล $pairTitle (2%)",
            stageText = "กำลังเริ่มต้นดาวน์โหลดในพื้นหลัง (ปิดแอปได้)...",
            progress = 2
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    PROGRESS_NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(PROGRESS_NOTIFICATION_ID, initialNotification)
            }
        } catch (_: Exception) {
        }

        TranslationPipelineCoordinator.translationManager.downloadModel(
            targetLangCode = targetLang,
            allowCellular = allowCellular
        ) { _, _ ->
            if (!TranslationPipelineCoordinator.translationManager.isDownloadingAnyModel) {
                stopForegroundSafely()
                stopSelf()
            }
        }

        // Redeliver intent if the OS temporarily reclaims memory while download is still active.
        return START_REDELIVER_INTENT
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the service running when the user swipes the app away from Recents while downloading.
        super.onTaskRemoved(rootIntent)
    }

    private fun handleProgressNotificationUpdate(
        targetLang: String,
        progress: Int,
        state: String,
        stageMessage: String,
        errorMessage: String?
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val pairTitle = if (targetLang.lowercase() == "en") "Japanese → English" else "Japanese → Thai"

        when (state) {
            "downloading" -> {
                val notification = buildProgressNotification(
                    title = "กำลังดาวน์โหลดโมเดล $pairTitle ($progress%)",
                    stageText = "$stageMessage (สามารถสลับหรือปิดแอปได้)",
                    progress = progress
                )
                manager.notify(PROGRESS_NOTIFICATION_ID, notification)
            }
            "downloaded" -> {
                val doneNotification = buildCompletionNotification(
                    title = "ดาวน์โหลดโมเดล $pairTitle สำเร็จ (100%)",
                    body = "พร้อมใช้งานแปลภาษาในเกมแบบ On-Device แล้ว"
                )
                manager.notify(RESULT_NOTIFICATION_ID, doneNotification)
            }
            "failed" -> {
                val failNotification = buildCompletionNotification(
                    title = "ดาวน์โหลดโมเดล $pairTitle ไม่สำเร็จ",
                    body = errorMessage ?: "กรุณาตรวจสอบการเชื่อมต่ออินเทอร์เน็ตแล้วลองใหม่"
                )
                manager.notify(RESULT_NOTIFICATION_ID, failNotification)
            }
        }
    }

    private fun buildProgressNotification(
        title: String,
        stageText: String,
        progress: Int
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(stageText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setContentIntent(pendingOpen)
            .build()
    }

    private fun buildCompletionNotification(
        title: String,
        body: String
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingOpen)
            .build()
    }

    private fun stopForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GameLingo Model Download",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "แสดงความคืบหน้าการดาวน์โหลดโมเดลแปลภาษาในพื้นหลัง"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (TranslationPipelineCoordinator.translationManager.onProgressUpdateListener != null &&
            !TranslationPipelineCoordinator.translationManager.isDownloadingAnyModel
        ) {
            TranslationPipelineCoordinator.translationManager.onProgressUpdateListener = null
        }
        TranslationPipelineCoordinator.releaseResourcesIfIdle()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

