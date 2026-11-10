package com.gamelingo.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.gamelingo.app.managers.TranslationPipelineCoordinator
import com.gamelingo.app.services.FloatingBubbleService
import com.gamelingo.app.services.FloatingPanelService
import com.gamelingo.app.services.MediaProjectionService
import com.gamelingo.app.services.NativeEventBridge
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * MainActivity hosts the Flutter UI and sets up the MethodChannel and EventChannel
 * bridge between Flutter and Android Native components.
 */
class MainActivity : FlutterActivity() {

    companion object {
        const val METHOD_CHANNEL_NAME = "com.gamelingo.app/native"
        const val EVENT_CHANNEL_NAME = "com.gamelingo.app/events"
        private const val REQUEST_CODE_NOTIFICATIONS = 2001
        private const val REQUEST_CODE_MEDIA_PROJECTION = 2002
    }

    private var pendingScreenCaptureResult: MethodChannel.Result? = null
    private var startBubbleAfterCaptureConsent: Boolean = false

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Wire Floating Bubble tap callback to the application-scoped pipeline coordinator.
        NativeEventBridge.onBubbleTapTranslateListener = {
            TranslationPipelineCoordinator.executePipeline(applicationContext)
        }

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            METHOD_CHANNEL_NAME
        ).setMethodCallHandler { call, result ->
            handleMethodCall(call, result)
        }

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            EVENT_CHANNEL_NAME
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                NativeEventBridge.attachSink(events)
                emitPermissionStatusEvent()
                emitCurrentBubbleStatusEvent()
            }

            override fun onCancel(arguments: Any?) {
                NativeEventBridge.detachSink()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        emitPermissionStatusEvent()
        emitCurrentBubbleStatusEvent()
    }

    override fun onDestroy() {
        if (NativeEventBridge.onBubbleTapTranslateListener != null) {
            NativeEventBridge.onBubbleTapTranslateListener = null
        }
        // Only release OCR & Translation managers if background overlay services are NOT running.
        TranslationPipelineCoordinator.releaseResourcesIfIdle()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_NOTIFICATIONS) {
            emitPermissionStatusEvent()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_MEDIA_PROJECTION) {
            val callbackResult = pendingScreenCaptureResult
            pendingScreenCaptureResult = null
            val shouldStartBubble = startBubbleAfterCaptureConsent
            startBubbleAfterCaptureConsent = false

            if (resultCode == Activity.RESULT_OK && data != null) {
                val serviceIntent = Intent(this, MediaProjectionService::class.java).apply {
                    action = MediaProjectionService.ACTION_START_PROJECTION
                    putExtra(MediaProjectionService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(MediaProjectionService.EXTRA_RESULT_DATA, data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)

                if (shouldStartBubble && Settings.canDrawOverlays(this)) {
                    val bubbleIntent = Intent(this, FloatingBubbleService::class.java).apply {
                        action = FloatingBubbleService.ACTION_START
                    }
                    startService(bubbleIntent)
                }

                callbackResult?.success(true)
            } else {
                NativeEventBridge.emit(
                    eventName = "translationFailed",
                    data = mapOf("reason" to "media_projection_user_cancelled"),
                    errorMessage = "ผู้ใช้ยกเลิกการอนุญาตจับภาพหน้าจอ (MediaProjection)"
                )
                emitPermissionStatusEvent()
                callbackResult?.success(false)
            }
        }
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "checkOverlayPermission" -> {
                result.success(TranslationPipelineCoordinator.buildPermissionStatusMap(this))
            }
            "requestOverlayPermission" -> {
                requestNotificationPermissionIfNeeded()
                val granted = Settings.canDrawOverlays(this)
                if (!granted) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
                result.success(Settings.canDrawOverlays(this))
            }
            "startFloatingBubble" -> {
                if (!Settings.canDrawOverlays(this)) {
                    result.error(
                        "OVERLAY_PERMISSION_DENIED",
                        "กรุณาอนุญาตสิทธิ์แสดงผลทับแอปอื่น (Display over other apps) ก่อนเริ่มใช้งาน Floating Bubble",
                        null
                    )
                    return
                }
                requestNotificationPermissionIfNeeded()

                if (MediaProjectionService.isCaptureSessionActive) {
                    val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply {
                        action = FloatingBubbleService.ACTION_START
                    }
                    startService(serviceIntent)
                    result.success(true)
                } else {
                    startBubbleAfterCaptureConsent = true
                    requestFreshMediaProjectionConsent(result)
                }
            }
            "stopFloatingBubble" -> {
                val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply {
                    action = FloatingBubbleService.ACTION_STOP
                }
                stopService(serviceIntent)
                FloatingPanelService.closePanel(this)
                result.success(true)
            }
            "startScreenCapture" -> {
                requestNotificationPermissionIfNeeded()
                startBubbleAfterCaptureConsent = false
                requestFreshMediaProjectionConsent(result)
            }
            "stopScreenCapture" -> {
                val stopIntent = Intent(this, MediaProjectionService::class.java).apply {
                    action = MediaProjectionService.ACTION_STOP_PROJECTION
                }
                stopService(stopIntent)
                emitPermissionStatusEvent()
                result.success(true)
            }
            "setTargetLanguage" -> {
                val lang = call.argument<String>("targetLanguage") ?: "th"
                TranslationPipelineCoordinator.setTargetLanguage(this, lang)
                result.success(null)
            }
            "getTranslationModelStatus" -> {
                TranslationPipelineCoordinator.translationManager.getModelStatusMap { statusMap ->
                    result.success(statusMap)
                }
            }
            "downloadTranslationModel" -> {
                val targetLang = call.argument<String>("targetLanguage")
                    ?: TranslationPipelineCoordinator.getTargetLanguage(this)
                val allowCellular = call.argument<Boolean>("allowCellular") ?: false
                TranslationPipelineCoordinator.translationManager.downloadModel(
                    targetLang,
                    allowCellular
                ) { success, errorMessage ->
                    if (success) {
                        result.success(true)
                    } else {
                        result.error(
                            "MODEL_DOWNLOAD_FAILED",
                            errorMessage ?: "ไม่สามารถดาวน์โหลดโมเดลแปลภาษาได้",
                            null
                        )
                    }
                }
            }
            "translateScreen" -> {
                if (!MediaProjectionService.isCaptureSessionActive) {
                    result.error(
                        "CAPTURE_SESSION_INACTIVE",
                        "ยังไม่ได้เปิดใช้งาน Screen Capture (MediaProjection) กรุณากดอนุญาตก่อน",
                        null
                    )
                    return
                }

                TranslationPipelineCoordinator.executePipeline(applicationContext) { translationResult, errorCode, errorMessage ->
                    if (translationResult != null && translationResult.isSuccess) {
                        result.success(translationResult.toMap())
                    } else {
                        result.error(
                            errorCode ?: "TRANSLATION_FAILED",
                            errorMessage ?: "การแปลข้อความล้มเหลว",
                            null
                        )
                    }
                }
            }
            "closeTranslationPanel" -> {
                FloatingPanelService.closePanel(this)
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    @Suppress("DEPRECATION")
    private fun requestFreshMediaProjectionConsent(result: MethodChannel.Result) {
        if (pendingScreenCaptureResult != null) {
            result.error(
                "CONSENT_ALREADY_PENDING",
                "กำลังรอการอนุญาตจับภาพหน้าจอจากผู้ใช้",
                null
            )
            return
        }

        try {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = projectionManager.createScreenCaptureIntent()
            pendingScreenCaptureResult = result
            startActivityForResult(captureIntent, REQUEST_CODE_MEDIA_PROJECTION)
        } catch (e: Exception) {
            pendingScreenCaptureResult = null
            startBubbleAfterCaptureConsent = false
            result.error(
                "PROJECTION_INTENT_FAILED",
                "ไม่สามารถเปิดหน้าต่างขออนุญาตจับภาพหน้าจอได้: ${e.localizedMessage}",
                null
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIFICATIONS
                )
            }
        }
    }

    private fun emitPermissionStatusEvent() {
        NativeEventBridge.emit(
            eventName = "permissionStatusChanged",
            data = TranslationPipelineCoordinator.buildPermissionStatusMap(this)
        )
    }

    private fun emitCurrentBubbleStatusEvent() {
        NativeEventBridge.emit(
            eventName = "bubbleStatusChanged",
            data = mapOf(
                "isRunning" to FloatingBubbleService.isRunning,
                "bubbleState" to if (FloatingBubbleService.isRunning) "active" else "stopped",
                "isLoading" to FloatingBubbleService.isBubbleLoading
            )
        )
    }
}
