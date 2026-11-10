package com.gamelingo.app.services

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.gamelingo.app.MainActivity
import com.gamelingo.app.managers.TranslationPipelineCoordinator
import com.gamelingo.app.receivers.StopServiceReceiver
import kotlin.math.abs

/**
 * Android Native Overlay Service that renders a Messenger Chat-Head style
 * Floating Bubble above full-screen games using [WindowManager] and
 * [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY].
 */
class FloatingBubbleService : Service() {

    companion object {
        const val ACTION_START = "com.gamelingo.app.action.START_BUBBLE"
        const val ACTION_STOP = "com.gamelingo.app.action.STOP_BUBBLE"
        const val ACTION_SET_LOADING = "com.gamelingo.app.action.SET_BUBBLE_LOADING"
        const val ACTION_SET_VISIBILITY = "com.gamelingo.app.action.SET_BUBBLE_VISIBILITY"

        const val EXTRA_IS_LOADING = "extra_is_loading"
        const val EXTRA_IS_VISIBLE = "extra_is_visible"

        private const val PREFS_NAME = "gamelingo_bubble_prefs"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"

        private const val NOTIFICATION_CHANNEL_ID = "gamelingo_bubble_channel"
        private const val NOTIFICATION_ID = 1001

        private const val DEBOUNCE_MS = 800L
        private const val INACTIVE_IDLE_DELAY_MS = 3500L

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isBubbleLoading: Boolean = false
            private set

        @Volatile
        private var instance: FloatingBubbleService? = null

        /**
         * Temporarily hides or restores the bubble view (used by Phase 3 ScreenCapture
         * so GameLingo's own overlay does not appear in captured game screenshots).
         */
        fun setOverlayVisibility(visible: Boolean) {
            instance?.setBubbleVisibleInternal(visible)
        }

        /**
         * Updates the loading spinner state on the floating bubble.
         */
        fun setLoadingState(loading: Boolean) {
            instance?.setLoadingInternal(loading)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootOverlayContainer: FrameLayout? = null
    private var bubbleCircleView: FrameLayout? = null
    private var bubbleLabelView: TextView? = null
    private var loadingProgressBar: ProgressBar? = null
    private var closeBadgeView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var snapAnimator: ValueAnimator? = null
    private var lastTapTimestamp: Long = 0L
    private var isActiveVisualState: Boolean = true

    private val enterInactiveStateRunnable = Runnable {
        if (!isBubbleLoading) {
            applyVisualActivityState(active = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBubbleSelf("explicit_stop_command")
                return START_NOT_STICKY
            }
            ACTION_SET_LOADING -> {
                val loading = intent.getBooleanExtra(EXTRA_IS_LOADING, false)
                setLoadingInternal(loading)
                return START_STICKY
            }
            ACTION_SET_VISIBILITY -> {
                val visible = intent.getBooleanExtra(EXTRA_IS_VISIBLE, true)
                setBubbleVisibleInternal(visible)
                return START_STICKY
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            NativeEventBridge.emit(
                eventName = "translationFailed",
                data = mapOf("reason" to "overlay_permission_missing"),
                errorMessage = "ไม่ได้รับอนุญาตสิทธิ์แสดงผลทับแอปอื่น (Overlay Permission)"
            )
            stopSelf()
            return START_NOT_STICKY
        }

        if (rootOverlayContainer == null) {
            addBubbleToWindow()
        }

        showStatusNotification()
        isRunning = true
        emitBubbleStatus(state = if (isActiveVisualState) "active" else "inactive")
        scheduleInactiveTransition()

        return START_STICKY
    }

    private fun addBubbleToWindow() {
        val screenSize = getScreenDimensions()
        val bubbleWidthPx = dpToPx(68f)
        val defaultX = screenSize.first - bubbleWidthPx
        val defaultY = (screenSize.second * 0.30f).toInt()

        val savedX = prefs.getInt(KEY_BUBBLE_X, defaultX).coerceIn(0, maxOf(0, screenSize.first - bubbleWidthPx))
        val savedY = prefs.getInt(KEY_BUBBLE_Y, defaultY).coerceIn(0, maxOf(0, screenSize.second - bubbleWidthPx))

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        layoutParams = params

        val container = buildBubbleViewHierarchy()
        rootOverlayContainer = container

        try {
            windowManager.addView(container, params)
        } catch (e: Exception) {
            NativeEventBridge.emit(
                eventName = "translationFailed",
                data = mapOf("reason" to "window_add_failed"),
                errorMessage = "ไม่สามารถสร้างหน้าต่าง Floating Bubble ได้: ${e.localizedMessage}"
            )
            stopSelf()
        }
    }

    private fun buildBubbleViewHierarchy(): FrameLayout {
        val outerSizePx = dpToPx(66f)
        val mainCircleSizePx = dpToPx(56f)
        val closeBadgeSizePx = dpToPx(22f)

        val rootContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(outerSizePx, outerSizePx)
            clipChildren = false
            clipToPadding = false
        }

        val circleContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(mainCircleSizePx, mainCircleSizePx).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = dpToPx(2f)
                bottomMargin = dpToPx(2f)
            }
            background = createBubbleDrawable(active = true)
            elevation = dpToPx(8f).toFloat()
        }
        bubbleCircleView = circleContainer

        val label = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            text = "訳"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        bubbleLabelView = label
        circleContainer.addView(label)

        val progress = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(32f), dpToPx(32f), Gravity.CENTER)
            isIndeterminate = true
            visibility = View.GONE
        }
        loadingProgressBar = progress
        circleContainer.addView(progress)

        val closeBadge = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(closeBadgeSizePx, closeBadgeSizePx).apply {
                gravity = Gravity.TOP or Gravity.END
            }
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EF4444"))
                setStroke(dpToPx(1.5f), Color.parseColor("#1E293B"))
            }
            elevation = dpToPx(10f).toFloat()
            setOnClickListener {
                stopBubbleSelf("bubble_close_button")
            }
        }
        closeBadgeView = closeBadge

        rootContainer.addView(circleContainer)
        rootContainer.addView(closeBadge)

        attachTouchListener(rootContainer)
        return rootContainer
    }

    private fun createBubbleDrawable(active: Boolean): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (active) {
                intArrayOf(
                    Color.parseColor("#6366F1"),
                    Color.parseColor("#4338CA"),
                    Color.parseColor("#1E1B4B")
                )
            } else {
                intArrayOf(
                    Color.parseColor("#475569"),
                    Color.parseColor("#1E293B")
                )
            }
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(
                dpToPx(2f),
                if (active) Color.parseColor("#38BDF8") else Color.parseColor("#64748B")
            )
        }
    }

    private fun attachTouchListener(targetView: View) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        targetView.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    applyVisualActivityState(active = true)
                    mainHandler.removeCallbacks(enterInactiveStateRunnable)

                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                    }

                    if (isDragging) {
                        val (screenWidth, screenHeight) = getScreenDimensions()
                        val viewWidth = targetView.width.takeIf { it > 0 } ?: dpToPx(66f)
                        val viewHeight = targetView.height.takeIf { it > 0 } ?: dpToPx(66f)

                        params.x = (initialX + dx).coerceIn(0, maxOf(0, screenWidth - viewWidth))
                        params.y = (initialY + dy).coerceIn(0, maxOf(0, screenHeight - viewHeight))
                        updateViewLayoutSafely()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        snapToNearestEdge()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        handleBubbleTap()
                    }
                    scheduleInactiveTransition()
                    true
                }

                else -> false
            }
        }
    }

    private fun snapToNearestEdge() {
        val params = layoutParams ?: return
        val container = rootOverlayContainer ?: return
        val (screenWidth, screenHeight) = getScreenDimensions()
        val viewWidth = container.width.takeIf { it > 0 } ?: dpToPx(66f)
        val viewHeight = container.height.takeIf { it > 0 } ?: dpToPx(66f)

        val maxX = maxOf(0, screenWidth - viewWidth)
        val maxY = maxOf(0, screenHeight - viewHeight)
        val clampedY = params.y.coerceIn(0, maxY)
        params.y = clampedY

        val startX = params.x
        val targetX = if (startX + viewWidth / 2 < screenWidth / 2) 0 else maxX

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                params.x = animator.animatedValue as Int
                updateViewLayoutSafely()
            }
            start()
        }

        savePosition(targetX, clampedY)
        emitBubbleStatus(state = if (isActiveVisualState) "active" else "inactive", x = targetX, y = clampedY)
    }

    private fun handleBubbleTap() {
        val now = SystemClock.elapsedRealtime()
        if (isBubbleLoading || (now - lastTapTimestamp) < DEBOUNCE_MS) {
            return
        }
        lastTapTimestamp = now

        setLoadingInternal(true)
        val params = layoutParams
        NativeEventBridge.emit(
            eventName = "bubbleStatusChanged",
            data = mapOf(
                "isRunning" to true,
                "bubbleState" to "tapped",
                "isLoading" to true,
                "x" to (params?.x ?: 0),
                "y" to (params?.y ?: 0)
            )
        )

        val customTapListener = NativeEventBridge.onBubbleTapTranslateListener
        if (customTapListener != null) {
            customTapListener.invoke()
        } else {
            // Execute the full Capture -> OCR -> Translation -> Floating Panel pipeline directly
            // from Service context so it continues working even if MainActivity was closed/destroyed.
            TranslationPipelineCoordinator.executePipeline(applicationContext)
        }
    }

    private fun setLoadingInternal(loading: Boolean) {
        mainHandler.post {
            isBubbleLoading = loading
            if (loading) {
                applyVisualActivityState(active = true)
                bubbleLabelView?.visibility = View.GONE
                loadingProgressBar?.visibility = View.VISIBLE
            } else {
                loadingProgressBar?.visibility = View.GONE
                bubbleLabelView?.visibility = View.VISIBLE
                scheduleInactiveTransition()
            }
        }
    }

    private fun setBubbleVisibleInternal(visible: Boolean) {
        mainHandler.post {
            rootOverlayContainer?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun applyVisualActivityState(active: Boolean) {
        isActiveVisualState = active
        mainHandler.post {
            bubbleCircleView?.background = createBubbleDrawable(active)
            rootOverlayContainer?.alpha = if (active) 1.0f else 0.65f
            closeBadgeView?.visibility = if (active) View.VISIBLE else View.GONE
        }
    }

    private fun scheduleInactiveTransition() {
        mainHandler.removeCallbacks(enterInactiveStateRunnable)
        mainHandler.postDelayed(enterInactiveStateRunnable, INACTIVE_IDLE_DELAY_MS)
    }

    private fun savePosition(x: Int, y: Int) {
        prefs.edit()
            .putInt(KEY_BUBBLE_X, x)
            .putInt(KEY_BUBBLE_Y, y)
            .apply()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Re-snap bubble within valid screen bounds when switching between Portrait and Landscape games.
        mainHandler.post {
            snapToNearestEdge()
        }
    }

    private fun updateViewLayoutSafely() {
        val view = rootOverlayContainer ?: return
        val params = layoutParams ?: return
        if (view.isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
        }
    }

    private fun getScreenDimensions(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars()
            )
            val width = metrics.bounds.width() - insets.left - insets.right
            val height = metrics.bounds.height() - insets.top - insets.bottom
            Pair(maxOf(width, 320), maxOf(height, 480))
        } else {
            val displayMetrics = resources.displayMetrics
            Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "GameLingo Floating Bubble",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "แสดงสถานะการทำงานของปุ่มลอยแปลภาษา GameLingo"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun showStatusNotification() {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopReceiverIntent = Intent(this, StopServiceReceiver::class.java).apply {
            action = StopServiceReceiver.ACTION_STOP_BUBBLE_SERVICE
        }
        val pendingStop = PendingIntent.getBroadcast(
            this,
            1,
            stopReceiverIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_sort_alphabetically)
            .setContentTitle("GameLingo Bubble กำลังทำงาน")
            .setContentText("แตะปุ่มลอยบนหน้าจอเกมเพื่อแปลภาษาญี่ปุ่น")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingOpenApp)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "ปิด Bubble",
                pendingStop
            )
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun cancelStatusNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    private fun emitBubbleStatus(
        state: String,
        x: Int = layoutParams?.x ?: 0,
        y: Int = layoutParams?.y ?: 0
    ) {
        NativeEventBridge.emit(
            eventName = "bubbleStatusChanged",
            data = mapOf(
                "isRunning" to isRunning,
                "bubbleState" to state,
                "isLoading" to isBubbleLoading,
                "x" to x,
                "y" to y
            )
        )
    }

    private fun stopBubbleSelf(reason: String) {
        isRunning = false
        isBubbleLoading = false
        FloatingPanelService.closePanel(this)
        if (reason == "bubble_close_button" || reason == "notification_stop_action") {
            val stopCaptureIntent = Intent(this, MediaProjectionService::class.java).apply {
                action = MediaProjectionService.ACTION_STOP_PROJECTION
            }
            stopService(stopCaptureIntent)
        }
        NativeEventBridge.emit(
            eventName = "bubbleStatusChanged",
            data = mapOf(
                "isRunning" to false,
                "bubbleState" to "stopped",
                "isLoading" to false,
                "reason" to reason
            )
        )
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        snapAnimator?.cancel()
        cancelStatusNotification()

        rootOverlayContainer?.let { view ->
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            } catch (_: Exception) {
            }
        }
        rootOverlayContainer = null
        bubbleCircleView = null
        bubbleLabelView = null
        loadingProgressBar = null
        closeBadgeView = null

        if (isRunning) {
            isRunning = false
            NativeEventBridge.emit(
                eventName = "bubbleStatusChanged",
                data = mapOf(
                    "isRunning" to false,
                    "bubbleState" to "stopped",
                    "isLoading" to false
                )
            )
        }
        instance = null
        TranslationPipelineCoordinator.releaseResourcesIfIdle()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).toInt()
    }
}

