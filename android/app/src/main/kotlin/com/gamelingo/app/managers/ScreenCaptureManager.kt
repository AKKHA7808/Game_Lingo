package com.gamelingo.app.managers

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import com.gamelingo.app.models.CaptureResult
import com.gamelingo.app.services.FloatingBubbleService
import com.gamelingo.app.services.FloatingPanelService
import com.gamelingo.app.services.NativeEventBridge

/**
 * Manages the Android [MediaProjection], [VirtualDisplay], and [ImageReader] lifecycle.
 *
 * Strictly complies with Android 14+ (API 34+) requirements:
 * - Registers [MediaProjection.Callback] BEFORE creating the [VirtualDisplay].
 * - Creates a single [VirtualDisplay] per consent token and reuses it across multiple
 *   Floating Bubble taps within the same session.
 * - Uses [VirtualDisplay.resize] on orientation/resolution changes rather than
 *   re-invoking `createVirtualDisplay` on a used token.
 * - Never processes frames continuously; only extracts a frame when explicitly requested.
 */
class ScreenCaptureManager(private val appContext: Context) {

    companion object {
        private const val VIRTUAL_DISPLAY_NAME = "GameLingoScreenCapture"
        private const val OVERLAY_HIDE_DELAY_MS = 110L
        private const val FRAME_RETRY_DELAY_MS = 80L
        private const val MAX_FRAME_RETRIES = 4
    }

    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val projectionManager =
        appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    @Volatile
    var isSessionActive: Boolean = false
        private set

    @Volatile
    private var isCapturingFrame: Boolean = false

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var currentWidth: Int = 0
    private var currentHeight: Int = 0
    private var currentDensityDpi: Int = DisplayMetrics.DENSITY_HIGH
    private var sessionCaptureCounter: Int = 0

    var onSystemRevokedListener: (() -> Unit)? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            releaseResources(notifyStopped = true, reason = "system_revoked_or_stopped")
            onSystemRevokedListener?.invoke()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            super.onCapturedContentResize(width, height)
            if (width > 0 && height > 0 && (width != currentWidth || height != currentHeight)) {
                resizeActiveVirtualDisplay(width, height)
            }
        }
    }

    /**
     * Initializes the [MediaProjection] session using a fresh consent result from [MainActivity].
     * Must be called from a running `mediaProjection` Foreground Service.
     */
    @Synchronized
    fun startSession(resultCode: Int, resultData: Intent): Boolean {
        releaseResources(notifyStopped = false)

        return try {
            ensureBackgroundThread()
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                NativeEventBridge.emit(
                    eventName = "translationFailed",
                    data = mapOf("reason" to "media_projection_null"),
                    errorMessage = "ไม่สามารถเริ่มต้นเซสชันการจับภาพหน้าจอได้ (MediaProjection is null)"
                )
                return false
            }

            // Android 14+ requirement: register callback BEFORE createVirtualDisplay.
            projection.registerCallback(projectionCallback, mainHandler)
            mediaProjection = projection

            val (width, height, densityDpi) = getDisplayMetrics()
            currentWidth = width
            currentHeight = height
            currentDensityDpi = densityDpi

            val reader = ImageReader.newInstance(
                currentWidth,
                currentHeight,
                PixelFormat.RGBA_8888,
                2
            )
            imageReader = reader

            virtualDisplay = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                currentWidth,
                currentHeight,
                currentDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                backgroundHandler
            )

            isSessionActive = true
            sessionCaptureCounter = 0
            NativeEventBridge.emit(
                eventName = "permissionStatusChanged",
                data = TranslationPipelineCoordinator.buildPermissionStatusMap(appContext)
            )
            true
        } catch (e: SecurityException) {
            releaseResources(notifyStopped = false)
            NativeEventBridge.emit(
                eventName = "translationFailed",
                data = mapOf("reason" to "media_projection_security_exception"),
                errorMessage = "สิทธิ์การจับภาพหน้าจอหมดอายุหรือถูกปฏิเสธ กรุณาขออนุญาตใหม่: ${e.localizedMessage}"
            )
            false
        } catch (e: Exception) {
            releaseResources(notifyStopped = false)
            NativeEventBridge.emit(
                eventName = "translationFailed",
                data = mapOf("reason" to "media_projection_init_failed"),
                errorMessage = "เกิดข้อผิดพลาดในการสร้าง VirtualDisplay: ${e.localizedMessage}"
            )
            false
        }
    }

    /**
     * Adjusts the existing [ImageReader] and [VirtualDisplay] when device orientation
     * (Portrait <-> Landscape) changes without re-creating the session from the token.
     */
    @Synchronized
    fun checkAndUpdateDisplayOrientation() {
        if (!isSessionActive || virtualDisplay == null) return
        val (width, height, densityDpi) = getDisplayMetrics()
        if (width != currentWidth || height != currentHeight || densityDpi != currentDensityDpi) {
            currentDensityDpi = densityDpi
            resizeActiveVirtualDisplay(width, height)
        }
    }

    @Synchronized
    private fun resizeActiveVirtualDisplay(newWidth: Int, newHeight: Int) {
        val vd = virtualDisplay ?: return
        try {
            currentWidth = newWidth
            currentHeight = newHeight

            val oldReader = imageReader
            val newReader = ImageReader.newInstance(
                currentWidth,
                currentHeight,
                PixelFormat.RGBA_8888,
                2
            )
            imageReader = newReader
            vd.resize(currentWidth, currentHeight, currentDensityDpi)
            vd.surface = newReader.surface
            oldReader?.close()
        } catch (_: Exception) {
        }
    }

    /**
     * Captures a single screenshot frame on demand when the user taps the Floating Bubble.
     * Temporarily hides GameLingo overlays before acquiring the frame, then restores them.
     */
    fun captureSingleFrame(onResult: (CaptureResult) -> Unit) {
        if (!isSessionActive || mediaProjection == null || imageReader == null) {
            val errorResult = CaptureResult(
                bitmap = null,
                width = 0,
                height = 0,
                isSuccess = false,
                errorCode = "CAPTURE_SESSION_INACTIVE",
                errorMessage = "ยังไม่ได้เปิดใช้งานสิทธิ์จับภาพหน้าจอ (MediaProjection) กรุณากดอนุญาตในแอป GameLingo"
            )
            NativeEventBridge.emit(
                eventName = "translationFailed",
                data = errorResult.toEventMetadata(),
                errorMessage = errorResult.errorMessage
            )
            onResult(errorResult)
            return
        }

        if (isCapturingFrame) {
            return
        }
        isCapturingFrame = true

        checkAndUpdateDisplayOrientation()

        NativeEventBridge.emit(
            eventName = "captureStarted",
            data = mapOf(
                "width" to currentWidth,
                "height" to currentHeight
            )
        )

        // Step 1: Temporarily hide both Floating Bubble and Floating Translation Panel
        // so GameLingo's own overlays do not appear in the captured game screenshot.
        FloatingBubbleService.setOverlayVisibility(false)
        FloatingPanelService.setOverlayVisibility(false)

        val handler = backgroundHandler ?: mainHandler
        handler.postDelayed({
            acquireFrameWithRetry(attempt = 0) { result ->
                // Step 2: Immediately restore Floating Bubble and Panel visibility after acquiring the frame.
                FloatingBubbleService.setOverlayVisibility(true)
                FloatingPanelService.setOverlayVisibility(true)
                isCapturingFrame = false

                if (result.isSuccess) {
                    NativeEventBridge.emit(
                        eventName = "captureCompleted",
                        data = result.toEventMetadata()
                    )
                } else {
                    NativeEventBridge.emit(
                        eventName = "translationFailed",
                        data = result.toEventMetadata(),
                        errorMessage = result.errorMessage
                    )
                }
                mainHandler.post {
                    onResult(result)
                }
            }
        }, OVERLAY_HIDE_DELAY_MS)
    }

    private fun acquireFrameWithRetry(
        attempt: Int,
        onComplete: (CaptureResult) -> Unit
    ) {
        val reader = imageReader
        if (reader == null || !isSessionActive) {
            onComplete(
                CaptureResult(
                    bitmap = null,
                    width = 0,
                    height = 0,
                    isSuccess = false,
                    errorCode = "READER_CLOSED",
                    errorMessage = "ระบบจับภาพหน้าจอถูกปิดระหว่างประมวลผล"
                )
            )
            return
        }

        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) {
                if (attempt < MAX_FRAME_RETRIES) {
                    val handler = backgroundHandler ?: mainHandler
                    handler.postDelayed({
                        acquireFrameWithRetry(attempt + 1, onComplete)
                    }, FRAME_RETRY_DELAY_MS)
                } else {
                    onComplete(
                        CaptureResult(
                            bitmap = null,
                            width = currentWidth,
                            height = currentHeight,
                            isSuccess = false,
                            errorCode = "NO_FRAME_AVAILABLE",
                            errorMessage = "ไม่สามารถดึงภาพหน้าจอล่าสุดได้ (เกมอาจป้องกันการจับภาพหน้าจอด้วย FLAG_SECURE หรือหน้าจอไม่มีการเปลี่ยนแปลง)"
                        )
                    )
                }
                return
            }

            val bitmap = convertImageToBitmap(image)
            image.close()
            image = null

            if (bitmap == null) {
                onComplete(
                    CaptureResult(
                        bitmap = null,
                        width = currentWidth,
                        height = currentHeight,
                        isSuccess = false,
                        errorCode = "BITMAP_CONVERSION_FAILED",
                        errorMessage = "ไม่สามารถแปลงข้อมูลภาพหน้าจอเพื่อประมวลผลได้"
                    )
                )
                return
            }

            // Detect completely black frames caused by FLAG_SECURE protected game windows.
            if (isBitmapCompletelyBlack(bitmap)) {
                bitmap.recycle()
                onComplete(
                    CaptureResult(
                        bitmap = null,
                        width = currentWidth,
                        height = currentHeight,
                        isSuccess = false,
                        isFlagSecureSuspected = true,
                        errorCode = "FLAG_SECURE_DETECTED",
                        errorMessage = "เกมหรือแอปพลิเคชันนี้มีการป้องกันการจับภาพหน้าจอ (FLAG_SECURE) จึงไม่สามารถอ่านข้อความจากหน้าจอได้"
                    )
                )
                return
            }

            sessionCaptureCounter += 1
            val centerPixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            val hexSample = String.format("#%08X", centerPixel)

            onComplete(
                CaptureResult(
                    bitmap = bitmap,
                    width = bitmap.width,
                    height = bitmap.height,
                    byteCount = bitmap.byteCount,
                    samplePixelHex = hexSample,
                    captureCountInSession = sessionCaptureCounter,
                    isSuccess = true
                )
            )
        } catch (e: Exception) {
            try {
                image?.close()
            } catch (_: Exception) {
            }
            onComplete(
                CaptureResult(
                    bitmap = null,
                    width = currentWidth,
                    height = currentHeight,
                    isSuccess = false,
                    errorCode = "CAPTURE_EXCEPTION",
                    errorMessage = "เกิดข้อผิดพลาดขณะจับภาพหน้าจอ: ${e.localizedMessage}"
                )
            )
        }
    }

    private fun convertImageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        if (planes.isEmpty()) return null
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val width = image.width
        val height = image.height
        val rowPadding = rowStride - pixelStride * width

        val paddedWidth = width + rowPadding / pixelStride
        val rawBitmap = Bitmap.createBitmap(
            paddedWidth,
            height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        rawBitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            rawBitmap
        } else {
            val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, width, height)
            rawBitmap.recycle()
            cropped
        }
    }

    /**
     * Samples a grid of pixels across the captured bitmap to check if the frame is
     * entirely black/blank (which occurs when an app sets WindowManager.LayoutParams.FLAG_SECURE).
     */
    private fun isBitmapCompletelyBlack(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return true

        val stepX = maxOf(1, width / 10)
        val stepY = maxOf(1, height / 10)
        var nonBlackFound = false

        var y = stepY / 2
        while (y < height && !nonBlackFound) {
            var x = stepX / 2
            while (x < width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r > 4 || g > 4 || b > 4) {
                    nonBlackFound = true
                    break
                }
                x += stepX
            }
            y += stepY
        }
        return !nonBlackFound
    }

    private fun ensureBackgroundThread() {
        if (backgroundThread == null || backgroundThread?.isAlive != true) {
            val thread = HandlerThread("GameLingoCaptureThread")
            thread.start()
            backgroundThread = thread
            backgroundHandler = Handler(thread.looper)
        }
    }

    private fun getDisplayMetrics(): Triple<Int, Int, Int> {
        val displayMetrics = appContext.resources.displayMetrics
        val densityDpi = displayMetrics.densityDpi

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            val width = maxOf(320, bounds.width())
            val height = maxOf(480, bounds.height())
            Triple(width, height, densityDpi)
        } else {
            Triple(
                maxOf(320, displayMetrics.widthPixels),
                maxOf(480, displayMetrics.heightPixels),
                densityDpi
            )
        }
    }

    /**
     * Stops the active [MediaProjection] session and frees all native display/image resources.
     */
    @Synchronized
    fun releaseResources(notifyStopped: Boolean = true, reason: String = "manual_stop") {
        val wasActive = isSessionActive
        isSessionActive = false
        isCapturingFrame = false

        // Always ensure overlays are visible if capture is aborted mid-flight.
        FloatingBubbleService.setOverlayVisibility(true)
        FloatingPanelService.setOverlayVisibility(true)

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null

        try {
            backgroundThread?.quitSafely()
        } catch (_: Exception) {
        }
        backgroundThread = null
        backgroundHandler = null

        if (notifyStopped && wasActive) {
            NativeEventBridge.emit(
                eventName = "captureStopped",
                data = mapOf("reason" to reason)
            )
            NativeEventBridge.emit(
                eventName = "permissionStatusChanged",
                data = TranslationPipelineCoordinator.buildPermissionStatusMap(appContext)
            )
        }
    }
}
