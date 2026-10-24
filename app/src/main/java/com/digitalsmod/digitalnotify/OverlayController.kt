package com.digitalsmod.digitalnotify

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.ArrayDeque
import kotlin.math.abs

internal data class BannerData(
    val packageName: String,
    val appName: String,
    val title: String,
    val body: String,
    val postTime: Long,
    val contentIntent: PendingIntent? = null,
    val largeIcon: Drawable? = null,
    val appBadgeIcon: Drawable? = null,
    val timeLabel: String = "now"
)

internal object OverlayManager {
    @Volatile
    private var controller: OverlayController? = null

    fun get(context: Context): OverlayController {
        return controller ?: synchronized(this) {
            controller ?: OverlayController(context.applicationContext).also {
                controller = it
            }
        }
    }
}

internal class OverlayController(private val appContext: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val uiContext: Context = createOverlayWindowContext(appContext)
    private val windowManager = uiContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = appContext.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
    private val queue = ArrayDeque<BannerData>()

    private var activeView: GlassBannerView? = null
    private var activeData: BannerData? = null
    private var activeAnimator: ValueAnimator? = null
    private var isTransitioningOut = false
    private var timeout: Runnable? = null

    private val expandInterpolator = PathInterpolator(0.20f, 0.92f, 0.28f, 1.0f)
    private val collapseInterpolator = PathInterpolator(0.32f, 0.0f, 0.67f, 0.0f)

    private fun createOverlayWindowContext(base: Context): Context {
        return try {
            val dm = base.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            val dispCtx = if (display != null) base.createDisplayContext(display) else base
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dispCtx.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else {
                dispCtx
            }
        } catch (_: Exception) {
            base
        }
    }

    fun show(data: BannerData) {
        mainHandler.post {
            if (!Settings.canDrawOverlays(appContext)) return@post

            val current = activeData
            if (current != null &&
                current.packageName == data.packageName &&
                current.title == data.title &&
                current.body == data.body &&
                abs(data.postTime - current.postTime) < 1200L
            ) {
                return@post
            }

            if (queue.size >= 10) queue.removeFirst()

            if (activeView != null) {
                queue.addFirst(data)
                if (!isTransitioningOut) {
                    dismissCurrent(openApp = false, fastReplace = true)
                }
                return@post
            }

            queue.addLast(data)
            if (!isTransitioningOut) {
                showNext()
            }
        }
    }

    private fun showNext() {
        if (activeView != null || isTransitioningOut || queue.isEmpty()) return
        if (!Settings.canDrawOverlays(appContext)) {
            queue.clear()
            return
        }

        val data = queue.removeFirst()
        val themeIndex = prefs.getInt(AppPrefs.KEY_THEME_INDEX, 0)
        val shouldVibrate = prefs.getBoolean(AppPrefs.KEY_VIBRATE_ENABLED, true)

        val metrics = uiContext.resources.displayMetrics
        val bannerWidth = (metrics.widthPixels - dp(12)).coerceAtLeast(dp(260))
        val (view, contentRow) = buildBanner(data, themeIndex)

        // IMPORTANT: No FLAG_BLUR_BEHIND so the surrounding screen NEVER gets blurred!
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        val params = WindowManager.LayoutParams(
            bannerWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = statusBarHeight() + dp(5)
            windowAnimations = 0
            dimAmount = 0f
        }

        val added = tryAddOverlayView(view, params)
        if (!added) {
            activeView = null
            activeData = null
            isTransitioningOut = false
            mainHandler.post { showNext() }
            return
        }

        activeView = view
        activeData = data

        if (shouldVibrate) {
            triggerSubtleHaptic()
        }

        val pivotX = bannerWidth * 0.118f
        val startScaleX = 0.11f
        val startScaleY = 0.095f
        val startTransY = -dp(16).toFloat()

        view.pivotX = pivotX
        view.pivotY = 0f
        view.scaleX = startScaleX
        view.scaleY = startScaleY
        view.translationY = startTransY
        view.alpha = 0.82f
        contentRow.alpha = 0.15f

        activeAnimator?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 430L
            interpolator = expandInterpolator
            addUpdateListener { va ->
                val f = va.animatedFraction
                val p = va.animatedValue as Float
                view.scaleX = startScaleX + (1f - startScaleX) * p
                view.scaleY = startScaleY + (1f - startScaleY) * p
                view.translationY = startTransY * (1f - p)
                view.alpha = (0.82f + f * 0.9f).coerceIn(0f, 1f)
                contentRow.alpha = ((f - 0.08f) / 0.52f).coerceIn(0f, 1f)
            }
        }
        activeAnimator = anim
        anim.start()

        view.playEntranceSweep()
        scheduleAutoDismiss()
    }

    private fun tryAddOverlayView(view: View, params: WindowManager.LayoutParams): Boolean {
        return try {
            windowManager.addView(view, params)
            true
        } catch (_: Exception) {
            try {
                val fallbackWm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                fallbackWm.addView(view, params)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun scheduleAutoDismiss() {
        timeout?.let { mainHandler.removeCallbacks(it) }
        val durationMs = prefs.getLong(AppPrefs.KEY_BANNER_DURATION_MS, 5500L).coerceIn(2000L, 15000L)
        val end = Runnable { dismissCurrent(openApp = false, fastReplace = false) }
        timeout = end
        mainHandler.postDelayed(end, durationMs)
    }

    private fun pauseAutoDismiss() {
        timeout?.let { mainHandler.removeCallbacks(it) }
        timeout = null
    }

    private fun buildBanner(data: BannerData, themeIndex: Int): Pair<GlassBannerView, LinearLayout> {
        val isDarkTheme = themeIndex == 1
        val titleColor = if (isDarkTheme) Color.WHITE else Color.rgb(18, 20, 24)
        val bodyColor = if (isDarkTheme) Color.rgb(222, 228, 240) else Color.rgb(26, 28, 34)
        val timeColor = if (isDarkTheme) Color.rgb(170, 182, 205) else Color.argb(160, 42, 40, 44)

        val root = GlassBannerView(uiContext, themeIndex).apply {
            setPadding(dp(15), dp(14), dp(18), dp(14))
            elevation = dp(14).toFloat()
            isClickable = true
            contentDescription = "${data.title}: ${data.body}"
        }

        val row = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val avatarContainer = FrameLayout(uiContext)
        val avatarSizePx = dp(46)
        val badgeSizePx = dp(20)

        val appDrawable = data.appBadgeIcon ?: try {
            uiContext.packageManager.getApplicationIcon(data.packageName)
        } catch (_: Exception) {
            null
        }

        val mainAvatarDrawable = if (data.largeIcon != null) {
            DemoIcons.makeCircularAvatar(uiContext.resources, data.largeIcon, avatarSizePx, data.title)
        } else if (appDrawable != null) {
            DemoIcons.makeCircularAvatar(uiContext.resources, appDrawable, avatarSizePx, data.title)
        } else {
            DemoIcons.makeCircularAvatar(uiContext.resources, null, avatarSizePx, data.title)
        }

        val avatarView = ImageView(uiContext).apply {
            setImageDrawable(mainAvatarDrawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        avatarContainer.addView(
            avatarView,
            FrameLayout.LayoutParams(avatarSizePx, avatarSizePx, Gravity.TOP or Gravity.START)
        )

        if (appDrawable != null) {
            val badgeView = ImageView(uiContext).apply {
                setImageDrawable(appDrawable)
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(5).toFloat())
                    }
                }
                clipToOutline = true
                elevation = dp(2).toFloat()
            }
            avatarContainer.addView(
                badgeView,
                FrameLayout.LayoutParams(badgeSizePx, badgeSizePx, Gravity.BOTTOM or Gravity.END)
            )
        }

        row.addView(avatarContainer, LinearLayout.LayoutParams(dp(50), dp(48)))

        val textColumn = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }

        val topRow = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleTv = TextView(uiContext).apply {
            text = data.title
            textSize = 15.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(titleColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        topRow.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))

        val nowTv = TextView(uiContext).apply {
            text = data.timeLabel
            textSize = 13f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(timeColor)
            setPadding(dp(8), 0, 0, 0)
        }
        topRow.addView(nowTv, LinearLayout.LayoutParams(-2, -2))

        textColumn.addView(topRow, LinearLayout.LayoutParams(-1, -2))

        val bodyTv = TextView(uiContext).apply {
            text = data.body
            textSize = 14.5f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(bodyColor)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), dp(12), 0)
        }
        textColumn.addView(bodyTv, LinearLayout.LayoutParams(-1, -2))

        row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))

        attachTouchGestures(root)
        return root to row
    }

    private fun attachTouchGestures(view: GlassBannerView) {
        val touchSlop = ViewConfiguration.get(uiContext).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var isDragging = false

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    isDragging = false
                    pauseAutoDismiss()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        v.translationX = dx * 0.6f
                        v.translationY = dy.coerceAtMost(dp(18).toFloat())
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    val dismissSwipe = dy < -dp(26) || abs(dx) > dp(75)

                    if (isDragging) {
                        if (dismissSwipe) {
                            dismissCurrent(openApp = false, fastReplace = false)
                        } else {
                            v.animate()
                                .translationX(0f)
                                .translationY(0f)
                                .setDuration(160L)
                                .start()
                            scheduleAutoDismiss()
                        }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        v.performClick()
                        dismissCurrent(openApp = true, fastReplace = false)
                    } else {
                        scheduleAutoDismiss()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dismissCurrent(openApp: Boolean, fastReplace: Boolean) {
        mainHandler.post {
            timeout?.let { mainHandler.removeCallbacks(it) }
            timeout = null
            val view = activeView
            val data = activeData
            if (view == null) {
                isTransitioningOut = false
                if (openApp && data != null) openNotificationTarget(data)
                showNext()
                return@post
            }

            isTransitioningOut = true
            activeAnimator?.cancel()
            view.animate().cancel()

            val w = if (view.width > 0) view.width else (uiContext.resources.displayMetrics.widthPixels - dp(12))
            view.pivotX = w * 0.118f
            view.pivotY = 0f

            val startScaleX = view.scaleX
            val startScaleY = view.scaleY
            val startTransX = view.translationX
            val startTransY = view.translationY
            val endScaleX = 0.11f
            val endScaleY = 0.095f
            val endTransY = -dp(16).toFloat()

            val collapseDuration = if (fastReplace) 185L else 225L

            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = collapseDuration
                interpolator = collapseInterpolator
                addUpdateListener { va ->
                    val f = va.animatedFraction
                    val p = va.animatedValue as Float
                    view.scaleX = startScaleX + (endScaleX - startScaleX) * p
                    view.scaleY = startScaleY + (endScaleY - startScaleY) * p
                    view.translationX = startTransX * (1f - p)
                    view.translationY = startTransY + (endTransY - startTransY) * p
                    view.alpha = if (f < 0.72f) 1f else ((1f - f) / 0.28f).coerceIn(0f, 1f)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        safeRemoveOverlayView(view)
                        activeView = null
                        activeData = null
                        activeAnimator = null
                        isTransitioningOut = false
                        if (openApp && data != null) {
                            openNotificationTarget(data)
                        }
                        if (fastReplace) {
                            mainHandler.postDelayed({ showNext() }, 40L)
                        } else {
                            showNext()
                        }
                    }
                })
            }
            activeAnimator = anim
            anim.start()
        }
    }

    private fun safeRemoveOverlayView(view: View) {
        try {
            windowManager.removeViewImmediate(view)
            return
        } catch (_: Exception) {
        }
        try {
            val fallbackWm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            fallbackWm.removeViewImmediate(view)
        } catch (_: Exception) {
        }
    }

    private fun openNotificationTarget(data: BannerData) {
        val pending = data.contentIntent
        if (pending != null) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val options = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    pending.send(appContext, 0, null, null, null, null, options.toBundle())
                } else {
                    pending.send()
                }
                return
            } catch (_: Exception) {
            }
        }

        try {
            val intent = appContext.packageManager.getLaunchIntentForPackage(data.packageName) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun triggerSubtleHaptic() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(VibrationEffect.createOneShot(15L, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {
        }
    }

    private fun statusBarHeight(): Int {
        val id = uiContext.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) uiContext.resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(value: Int): Int = (value * uiContext.resources.displayMetrics.density).toInt()
}
