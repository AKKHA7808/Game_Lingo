package com.gamelingo.app.services

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
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
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.gamelingo.app.managers.TranslationPipelineCoordinator
import kotlin.math.abs
import kotlin.math.min

/**
 * Android Native Overlay Service that displays the Floating Translation Panel
 * above full-screen games using [WindowManager] and
 * [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY].
 *
 * Features:
 * - Translucent dark theme with rounded corners (`18dp`) that does not overly obstruct the game.
 * - Draggable header bar with position persistence.
 * - Expand / Minimize (Collapse) toggle so players can keep a compact single-line bar on screen.
 * - Scrollable content container for long multi-line Japanese dialogues and Thai/English translations.
 * - Loading state, Error state, Copy to Clipboard button, Translate Again (Retry) button, and Close button.
 */
class FloatingPanelService : Service() {

    companion object {
        const val ACTION_SHOW_LOADING = "com.gamelingo.app.action.PANEL_SHOW_LOADING"
        const val ACTION_SHOW_RESULT = "com.gamelingo.app.action.PANEL_SHOW_RESULT"
        const val ACTION_SHOW_ERROR = "com.gamelingo.app.action.PANEL_SHOW_ERROR"
        const val ACTION_CLOSE_PANEL = "com.gamelingo.app.action.PANEL_CLOSE"

        const val EXTRA_ORIGINAL_TEXT = "extra_original_text"
        const val EXTRA_TRANSLATED_TEXT = "extra_translated_text"
        const val EXTRA_TARGET_LANG = "extra_target_lang"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
        const val EXTRA_LOADING_MESSAGE = "extra_loading_message"
        const val EXTRA_REQUEST_ID = "extra_request_id"

        private const val PREFS_NAME = "gamelingo_panel_prefs"
        private const val KEY_PANEL_X = "panel_x"
        private const val KEY_PANEL_Y = "panel_y"

        @Volatile
        var isPanelShowing: Boolean = false
            private set

        @Volatile
        private var instance: FloatingPanelService? = null

        /**
         * Temporarily hides or restores the Floating Translation Panel during screen capture
         * so the panel itself is never captured in the game screenshot.
         */
        fun setOverlayVisibility(visible: Boolean) {
            instance?.setPanelVisibleInternal(visible)
        }

        fun showLoading(
            context: Context,
            targetLang: String,
            message: String = "กำลังตรวจจับและแปลข้อความภาษาญี่ปุ่น...",
            requestId: Long = 0L
        ) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingPanelService::class.java).apply {
                action = ACTION_SHOW_LOADING
                putExtra(EXTRA_TARGET_LANG, targetLang)
                putExtra(EXTRA_LOADING_MESSAGE, message)
                putExtra(EXTRA_REQUEST_ID, requestId)
            }
            context.startService(intent)
        }

        fun showResult(
            context: Context,
            originalText: String,
            translatedText: String,
            targetLang: String,
            requestId: Long = 0L
        ) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingPanelService::class.java).apply {
                action = ACTION_SHOW_RESULT
                putExtra(EXTRA_ORIGINAL_TEXT, originalText)
                putExtra(EXTRA_TRANSLATED_TEXT, translatedText)
                putExtra(EXTRA_TARGET_LANG, targetLang)
                putExtra(EXTRA_REQUEST_ID, requestId)
            }
            context.startService(intent)
        }

        fun showError(
            context: Context,
            errorMessage: String,
            targetLang: String,
            originalText: String = "",
            requestId: Long = 0L
        ) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingPanelService::class.java).apply {
                action = ACTION_SHOW_ERROR
                putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
                putExtra(EXTRA_TARGET_LANG, targetLang)
                putExtra(EXTRA_ORIGINAL_TEXT, originalText)
                putExtra(EXTRA_REQUEST_ID, requestId)
            }
            context.startService(intent)
        }

        fun closePanel(context: Context) {
            val intent = Intent(context, FloatingPanelService::class.java).apply {
                action = ACTION_CLOSE_PANEL
            }
            context.stopService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootCardLayout: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var langBadgeView: TextView? = null
    private var minimizeBtnView: TextView? = null
    private var collapsedSummaryView: TextView? = null
    private var expandableBodyContainer: LinearLayout? = null

    private var loadingContainer: LinearLayout? = null
    private var loadingTextView: TextView? = null

    private var errorContainer: LinearLayout? = null
    private var errorTextView: TextView? = null

    private var resultScrollView: ScrollView? = null
    private var originalTextView: TextView? = null
    private var translatedTextView: TextView? = null
    private var copyButtonView: TextView? = null
    private var retryButtonView: TextView? = null

    private var currentOriginalText: String = ""
    private var currentTranslatedText: String = ""
    private var currentTargetLang: String = "th"
    private var isMinimized: Boolean = false
    private var latestAcceptedRequestId: Long = 0L
    private var lastRenderedStateSignature: String = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE_PANEL) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Prevent stale or duplicate event rendering:
        val incomingRequestId = intent?.getLongExtra(EXTRA_REQUEST_ID, 0L) ?: 0L
        if (incomingRequestId > 0L) {
            if (incomingRequestId < latestAcceptedRequestId) {
                // Ignore stale event from an older superseded request.
                return START_NOT_STICKY
            }
            latestAcceptedRequestId = incomingRequestId
        }

        ensurePanelViewAttached()

        val targetLang = intent?.getStringExtra(EXTRA_TARGET_LANG) ?: currentTargetLang
        currentTargetLang = targetLang
        updateLanguageBadge(targetLang)

        when (intent?.action) {
            ACTION_SHOW_LOADING -> {
                val msg = intent.getStringExtra(EXTRA_LOADING_MESSAGE)
                    ?: "กำลังตรวจจับและแปลข้อความภาษาญี่ปุ่น..."
                val sig = "LOADING|$incomingRequestId|$targetLang|$msg"
                if (sig != lastRenderedStateSignature) {
                    lastRenderedStateSignature = sig
                    renderLoadingState(msg)
                }
            }
            ACTION_SHOW_RESULT -> {
                val orig = intent.getStringExtra(EXTRA_ORIGINAL_TEXT) ?: ""
                val trans = intent.getStringExtra(EXTRA_TRANSLATED_TEXT) ?: ""
                val sig = "RESULT|$incomingRequestId|$targetLang|$orig|$trans"
                if (sig != lastRenderedStateSignature) {
                    lastRenderedStateSignature = sig
                    renderResultState(orig, trans)
                }
            }
            ACTION_SHOW_ERROR -> {
                val err = intent.getStringExtra(EXTRA_ERROR_MESSAGE)
                    ?: "เกิดข้อผิดพลาดในการแปลข้อความ"
                val orig = intent.getStringExtra(EXTRA_ORIGINAL_TEXT) ?: ""
                val sig = "ERROR|$incomingRequestId|$targetLang|$err|$orig"
                if (sig != lastRenderedStateSignature) {
                    lastRenderedStateSignature = sig
                    renderErrorState(err, orig)
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun ensurePanelViewAttached() {
        if (rootCardLayout != null) {
            rootCardLayout?.visibility = View.VISIBLE
            isPanelShowing = true
            return
        }

        val (screenW, screenH) = getScreenDimensions()
        val panelWidthPx = calculateOptimalPanelWidth(screenW)
        val defaultX = ((screenW - panelWidthPx) / 2).coerceAtLeast(dpToPx(8f))
        val defaultY = (screenH * 0.16f).toInt().coerceAtLeast(dpToPx(24f))

        val savedX = prefs.getInt(KEY_PANEL_X, defaultX).coerceIn(0, maxOf(0, screenW - panelWidthPx))
        val savedY = prefs.getInt(KEY_PANEL_Y, defaultY).coerceIn(0, maxOf(0, screenH - dpToPx(120f)))

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            panelWidthPx,
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

        val view = buildPanelHierarchy(panelWidthPx)
        rootCardLayout = view

        try {
            windowManager.addView(view, params)
            isPanelShowing = true
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun buildPanelHierarchy(panelWidthPx: Int): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                panelWidthPx,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(18f).toFloat()
                // Translucent dark navy surface (#EB0F172A ~ 92% opacity)
                setColor(Color.parseColor("#EB0F172A"))
                setStroke(dpToPx(1.5f), Color.parseColor("#6366F1"))
            }
            elevation = dpToPx(12f).toFloat()
            setPadding(dpToPx(14f), dpToPx(10f), dpToPx(14f), dpToPx(12f))
        }

        // 1. Header Bar (Draggable + Title + Language Badge + Minimize/Expand + Close)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val logoDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(10f), dpToPx(10f)).apply {
                rightMargin = dpToPx(8f)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#38BDF8"))
            }
        }

        val titleText = TextView(this).apply {
            text = "GameLingo"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val langBadge = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dpToPx(8f)
            }
            text = "JA → TH"
            setTextColor(Color.parseColor("#C7D2FE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(7f), dpToPx(2f), dpToPx(7f), dpToPx(2f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(99f).toFloat()
                setColor(Color.parseColor("#336366F1"))
                setStroke(dpToPx(1f), Color.parseColor("#66818CF8"))
            }
        }
        langBadgeView = langBadge

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        val minimizeBtn = createHeaderIconButton("—") {
            toggleMinimizeState()
        }
        minimizeBtnView = minimizeBtn

        val closeBtn = createHeaderIconButton("✕", isDanger = true) {
            stopSelf()
        }
        closeBtn.layoutParams = (closeBtn.layoutParams as LinearLayout.LayoutParams).apply {
            leftMargin = dpToPx(6f)
        }

        headerRow.addView(logoDot)
        headerRow.addView(titleText)
        headerRow.addView(langBadge)
        headerRow.addView(spacer)
        headerRow.addView(minimizeBtn)
        headerRow.addView(closeBtn)

        attachDragListener(headerRow)
        card.addView(headerRow)

        // 2. Collapsed single-line preview (shown only when minimized)
        val collapsedSummary = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(6f)
            }
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            visibility = View.GONE
            setOnClickListener {
                if (isMinimized) toggleMinimizeState()
            }
        }
        collapsedSummaryView = collapsedSummary
        card.addView(collapsedSummary)

        // 3. Expandable Body Container
        val bodyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8f)
            }
        }
        expandableBodyContainer = bodyContainer

        // 3a. Loading View
        val loadingBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(10f), dpToPx(12f), dpToPx(10f), dpToPx(12f))
            visibility = View.GONE
        }
        val spinner = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(24f), dpToPx(24f)).apply {
                rightMargin = dpToPx(10f)
            }
            isIndeterminate = true
        }
        val loadingLabel = TextView(this).apply {
            text = "กำลังตรวจจับและแปลข้อความภาษาญี่ปุ่น..."
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        loadingTextView = loadingLabel
        loadingBox.addView(spinner)
        loadingBox.addView(loadingLabel)
        loadingContainer = loadingBox
        bodyContainer.addView(loadingBox)

        // 3b. Error View
        val errorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(10f), dpToPx(10f), dpToPx(10f), dpToPx(10f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(12f).toFloat()
                setColor(Color.parseColor("#26EF4444"))
                setStroke(dpToPx(1f), Color.parseColor("#80F87171"))
            }
            visibility = View.GONE
        }
        val errorLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#FCA5A5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(0f, 1.25f)
        }
        errorTextView = errorLabel
        errorBox.addView(errorLabel)
        errorContainer = errorBox
        bodyContainer.addView(errorBox)

        // 3c. Scrollable Result Content (Original JP + Translated TH/EN)
        val scrollArea = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // Cap maximum scroll height to 200dp so the panel never covers too much of the game screen.
                val maxHeightSpec = MeasureSpec.makeMeasureSpec(dpToPx(200f), MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, maxHeightSpec)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isVerticalScrollBarEnabled = true
        }
        resultScrollView = scrollArea

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val jpHeaderLabel = TextView(this).apply {
            text = "Japanese (OCR)"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val origText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(3f)
                bottomMargin = dpToPx(8f)
            }
            setPadding(dpToPx(10f), dpToPx(8f), dpToPx(10f), dpToPx(8f))
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setLineSpacing(0f, 1.3f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10f).toFloat()
                setColor(Color.parseColor("#CC1E293B"))
            }
        }
        originalTextView = origText

        val transHeaderLabel = TextView(this).apply {
            text = "คำแปล (Translation)"
            setTextColor(Color.parseColor("#A5B4FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val transText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(3f)
            }
            setPadding(dpToPx(10f), dpToPx(9f), dpToPx(10f), dpToPx(9f))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.35f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10f).toFloat()
                setColor(Color.parseColor("#B31E1B4B"))
                setStroke(dpToPx(1f), Color.parseColor("#666366F1"))
            }
        }
        translatedTextView = transText

        val attributionLabel = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(4f)
            }
            gravity = Gravity.END
            text = "Translated by Google"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, Typeface.ITALIC)
        }

        scrollContent.addView(jpHeaderLabel)
        scrollContent.addView(origText)
        scrollContent.addView(transHeaderLabel)
        scrollContent.addView(transText)
        scrollContent.addView(attributionLabel)
        scrollArea.addView(scrollContent)
        bodyContainer.addView(scrollArea)

        // 4. Bottom Action Bar (Copy, Translate Again, Close)
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(10f)
            }
        }

        val copyBtn = createActionButton("Copy", bgColor = "#334155") {
            copyCurrentTextToClipboard()
        }
        copyButtonView = copyBtn

        val retryBtn = createActionButton("แปลใหม่ (Retry)", bgColor = "#4F46E5") {
            triggerRetranslateFromPanel()
        }
        retryBtn.layoutParams = (retryBtn.layoutParams as LinearLayout.LayoutParams).apply {
            leftMargin = dpToPx(8f)
        }
        retryButtonView = retryBtn

        val bottomCloseBtn = createActionButton("ปิด", bgColor = "#1E293B") {
            stopSelf()
        }
        bottomCloseBtn.layoutParams = (bottomCloseBtn.layoutParams as LinearLayout.LayoutParams).apply {
            leftMargin = dpToPx(8f)
        }

        actionRow.addView(copyBtn)
        actionRow.addView(retryBtn)
        actionRow.addView(bottomCloseBtn)
        bodyContainer.addView(actionRow)

        card.addView(bodyContainer)
        return card
    }

    private fun createHeaderIconButton(
        symbol: String,
        isDanger: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(26f), dpToPx(26f))
            text = symbol
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    if (isDanger) Color.parseColor("#CCEF4444")
                    else Color.parseColor("#CC334155")
                )
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createActionButton(
        label: String,
        bgColor: String,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(8f), dpToPx(8f), dpToPx(8f), dpToPx(8f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10f).toFloat()
                setColor(Color.parseColor(bgColor))
                setStroke(dpToPx(1f), Color.parseColor("#475569"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun attachDragListener(dragHandle: View) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        dragHandle.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            val card = rootCardLayout ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
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
                        val (screenW, screenH) = getScreenDimensions()
                        val cardW = card.width.takeIf { it > 0 } ?: params.width
                        val cardH = card.height.takeIf { it > 0 } ?: dpToPx(160f)

                        params.x = (initialX + dx).coerceIn(0, maxOf(0, screenW - cardW))
                        params.y = (initialY + dy).coerceIn(0, maxOf(0, screenH - cardH))
                        updatePanelLayoutSafely()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        prefs.edit()
                            .putInt(KEY_PANEL_X, params.x)
                            .putInt(KEY_PANEL_Y, params.y)
                            .apply()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun toggleMinimizeState() {
        isMinimized = !isMinimized
        if (isMinimized) {
            expandableBodyContainer?.visibility = View.GONE
            collapsedSummaryView?.visibility = View.VISIBLE
            val preview = when {
                currentTranslatedText.isNotBlank() -> currentTranslatedText.replace("\n", " ")
                currentOriginalText.isNotBlank() -> currentOriginalText.replace("\n", " ")
                else -> "แตะเพื่อขยายหน้าต่างคำแปล GameLingo"
            }
            collapsedSummaryView?.text = preview
            minimizeBtnView?.text = "▢"
        } else {
            collapsedSummaryView?.visibility = View.GONE
            expandableBodyContainer?.visibility = View.VISIBLE
            minimizeBtnView?.text = "—"
        }
        updatePanelLayoutSafely()
    }

    private fun renderLoadingState(message: String) {
        if (isMinimized) {
            toggleMinimizeState()
        }
        loadingTextView?.text = message
        loadingContainer?.visibility = View.VISIBLE
        errorContainer?.visibility = View.GONE
        resultScrollView?.visibility = View.GONE
        copyButtonView?.isEnabled = false
        copyButtonView?.alpha = 0.5f
        retryButtonView?.isEnabled = false
        retryButtonView?.alpha = 0.5f
        updatePanelLayoutSafely()
    }

    private fun renderResultState(originalText: String, translatedText: String) {
        currentOriginalText = originalText
        currentTranslatedText = translatedText

        loadingContainer?.visibility = View.GONE
        errorContainer?.visibility = View.GONE
        resultScrollView?.visibility = View.VISIBLE

        originalTextView?.text = originalText
        translatedTextView?.text = translatedText
        collapsedSummaryView?.text = translatedText.replace("\n", " ")

        copyButtonView?.isEnabled = true
        copyButtonView?.alpha = 1.0f
        retryButtonView?.isEnabled = true
        retryButtonView?.alpha = 1.0f
        updatePanelLayoutSafely()
    }

    private fun renderErrorState(errorMessage: String, originalText: String) {
        currentOriginalText = originalText
        currentTranslatedText = ""

        loadingContainer?.visibility = View.GONE
        errorContainer?.visibility = View.VISIBLE
        errorTextView?.text = errorMessage

        if (originalText.isNotBlank()) {
            resultScrollView?.visibility = View.VISIBLE
            originalTextView?.text = originalText
            translatedTextView?.text = "—"
            copyButtonView?.isEnabled = true
            copyButtonView?.alpha = 1.0f
        } else {
            resultScrollView?.visibility = View.GONE
            copyButtonView?.isEnabled = false
            copyButtonView?.alpha = 0.5f
        }

        retryButtonView?.isEnabled = true
        retryButtonView?.alpha = 1.0f
        updatePanelLayoutSafely()
    }

    private fun copyCurrentTextToClipboard() {
        val textToCopy = when {
            currentTranslatedText.isNotBlank() && currentOriginalText.isNotBlank() ->
                "${currentTranslatedText}\n\n[JP]: ${currentOriginalText}"
            currentTranslatedText.isNotBlank() -> currentTranslatedText
            currentOriginalText.isNotBlank() -> currentOriginalText
            else -> return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("GameLingo Translation", textToCopy))

        copyButtonView?.text = "Copied ✓"
        mainHandler.postDelayed({
            copyButtonView?.text = "Copy"
        }, 1400L)
    }

    private fun triggerRetranslateFromPanel() {
        val listener = NativeEventBridge.onBubbleTapTranslateListener
        if (listener != null) {
            FloatingBubbleService.setLoadingState(true)
            listener.invoke()
        } else {
            TranslationPipelineCoordinator.executePipeline(applicationContext)
        }
    }

    private fun updateLanguageBadge(targetLang: String) {
        val code = targetLang.uppercase().trim()
        langBadgeView?.text = if (code == "EN") "JA → EN" else "JA → TH"
    }

    private fun setPanelVisibleInternal(visible: Boolean) {
        mainHandler.post {
            rootCardLayout?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mainHandler.post {
            val params = layoutParams ?: return@post
            val (screenW, screenH) = getScreenDimensions()
            val newWidth = calculateOptimalPanelWidth(screenW)
            params.width = newWidth
            params.x = params.x.coerceIn(0, maxOf(0, screenW - newWidth))
            params.y = params.y.coerceIn(0, maxOf(0, screenH - dpToPx(120f)))
            updatePanelLayoutSafely()
        }
    }

    private fun calculateOptimalPanelWidth(screenWidthPx: Int): Int {
        return min((screenWidthPx * 0.86f).toInt(), dpToPx(380f))
    }

    private fun updatePanelLayoutSafely() {
        val view = rootCardLayout ?: return
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

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        rootCardLayout?.let { view ->
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            } catch (_: Exception) {
            }
        }
        rootCardLayout = null
        isPanelShowing = false
        instance = null
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
