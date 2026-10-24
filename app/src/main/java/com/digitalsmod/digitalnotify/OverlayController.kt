package com.digitalsmod.digitalnotify

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.util.ArrayDeque
import kotlin.math.abs

internal data class BannerData(
    val packageName: String,
    val appName: String,
    val title: String,
    val body: String,
    val postTime: Long,
    val contentIntent: PendingIntent? = null,
    val customIcon: Drawable? = null
)

internal class OverlayController(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
    private val queue = ArrayDeque<BannerData>()

    private var activeView: GlassBannerView? = null
    private var activeData: BannerData? = null
    private var activeTitleView: TextView? = null
    private var activeBodyView: TextView? = null
    private var timeout: Runnable? = null
    private var disposed = false

    fun show(data: BannerData) {
        mainHandler.post {
            if (disposed || !Settings.canDrawOverlays(context)) return@post

            val current = activeData
            if (current != null &&
                current.packageName == data.packageName &&
                current.title == data.title &&
                current.body == data.body &&
                abs(data.postTime - current.postTime) < 2500L
            ) {
                return@post
            }

            // If same app posts a new message while its banner is currently open, update in-place smoothly
            if (current != null && activeView != null && current.packageName == data.packageName) {
                activeData = data
                activeTitleView?.text = data.title
                activeBodyView?.text = data.body
                activeView?.playEntranceSweep()
                scheduleAutoDismiss()
                return@post
            }

            if (queue.size >= 10) queue.removeFirst()
            queue.addLast(data)
            if (activeView == null) showNext()
        }
    }

    private fun showNext() {
        if (disposed || activeView != null || queue.isEmpty()) return
        if (!Settings.canDrawOverlays(context)) {
            queue.clear()
            return
        }

        val data = queue.removeFirst()
        val themeIndex = prefs.getInt(MainActivity.KEY_THEME_INDEX, 0)
        val isBottom = prefs.getBoolean(MainActivity.KEY_POSITION_BOTTOM, false)
        val shouldVibrate = prefs.getBoolean(MainActivity.KEY_VIBRATE_ENABLED, true)

        val view = buildBanner(data, themeIndex, isBottom)
        val metrics = context.resources.displayMetrics
        val maxWidth = dp(440)
        val width = (metrics.widthPixels - dp(24)).coerceAtLeast(dp(240)).coerceAtMost(maxWidth)

        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (isBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
            x = 0
            y = if (isBottom) dp(28) else (statusBarHeight() + dp(8))
            windowAnimations = 0
            dimAmount = 0f
        }

        try {
            activeView = view
            activeData = data
            windowManager.addView(view, params)

            if (shouldVibrate) {
                triggerSubtleHaptic()
            }

            val startOffset = if (isBottom) dp(72).toFloat() else -dp(72).toFloat()
            view.alpha = 0f
            view.scaleX = 0.94f
            view.scaleY = 0.94f
            view.translationY = startOffset
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(250L)
                .setInterpolator(OvershootInterpolator(0.8f))
                .start()

            SpringAnimation(view, DynamicAnimation.TRANSLATION_Y, 0f).apply {
                spring = SpringForce(0f)
                    .setDampingRatio(0.76f)
                    .setStiffness(620f)
                setStartValue(startOffset)
                start()
            }

            view.playEntranceSweep()
            scheduleAutoDismiss()
        } catch (_: Exception) {
            activeView = null
            activeData = null
            activeTitleView = null
            activeBodyView = null
            mainHandler.post { showNext() }
        }
    }

    private fun scheduleAutoDismiss() {
        timeout?.let { mainHandler.removeCallbacks(it) }
        val durationMs = prefs.getLong(MainActivity.KEY_BANNER_DURATION_MS, 5200L).coerceIn(2000L, 15000L)
        val end = Runnable { dismissCurrent(openApp = false) }
        timeout = end
        mainHandler.postDelayed(end, durationMs)
    }

    private fun pauseAutoDismiss() {
        timeout?.let { mainHandler.removeCallbacks(it) }
        timeout = null
    }

    private fun buildBanner(data: BannerData, themeIndex: Int, isBottom: Boolean): GlassBannerView {
        val root = GlassBannerView(context, themeIndex).apply {
            setPadding(dp(14), dp(13), dp(12), dp(13))
            elevation = dp(18).toFloat()
            isClickable = true
            contentDescription = "Thông báo từ ${data.appName}: ${data.title}. ${data.body}"
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val appIcon = ImageView(context).apply {
            val iconDrawable = data.customIcon ?: try {
                context.packageManager.getApplicationIcon(data.packageName)
            } catch (_: Exception) {
                makeFallbackIcon()
            }
            setImageDrawable(iconDrawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(Color.argb(75, 42, 54, 78), dp(13))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        row.addView(appIcon, LinearLayout.LayoutParams(dp(44), dp(44)))

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(6), 0)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = data.appName.uppercase()
            textSize = 10f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.rgb(185, 206, 248))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))

        header.addView(TextView(context).apply {
            text = "VỪA XONG"
            textSize = 9f
            letterSpacing = 0.05f
            setTextColor(Color.rgb(150, 165, 190))
        })
        textColumn.addView(header, LinearLayout.LayoutParams(-1, -2))

        val titleTv = TextView(context).apply {
            text = data.title
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        activeTitleView = titleTv
        textColumn.addView(titleTv, LinearLayout.LayoutParams(-1, -2))

        val bodyTv = TextView(context).apply {
            text = data.body
            textSize = 12.5f
            setTextColor(Color.rgb(216, 224, 238))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        activeBodyView = bodyTv
        textColumn.addView(bodyTv, LinearLayout.LayoutParams(-1, -2))

        row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))

        val close = TextView(context).apply {
            text = "×"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(218, 226, 242))
            background = rounded(Color.argb(42, 255, 255, 255), dp(16))
            contentDescription = "Đóng thông báo"
            setOnClickListener { dismissCurrent(openApp = false) }
        }
        row.addView(close, LinearLayout.LayoutParams(dp(30), dp(30)).apply { leftMargin = dp(6) })
        root.addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))

        attachTouchGestures(root, isBottom)
        close.bringToFront()
        return root
    }

    private fun attachTouchGestures(view: GlassBannerView, isBottom: Boolean) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
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
                        v.translationX = dx * 0.85f
                        val allowedDy = if (isBottom) dy.coerceAtLeast(-dp(18).toFloat()) else dy.coerceAtMost(dp(18).toFloat())
                        v.translationY = allowedDy
                        val distance = (abs(dx) + abs(allowedDy)).coerceAtMost(dp(180).toFloat())
                        v.alpha = (1f - (distance / dp(220).toFloat())).coerceIn(0.25f, 1f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    val dismissVertical = if (isBottom) dy > dp(36) else dy < -dp(36)
                    val dismissHorizontal = abs(dx) > dp(75)

                    if (isDragging) {
                        if (dismissVertical || dismissHorizontal) {
                            dismissCurrent(openApp = false)
                        } else {
                            v.animate()
                                .translationX(0f)
                                .translationY(0f)
                                .alpha(1f)
                                .setDuration(180L)
                                .start()
                            scheduleAutoDismiss()
                        }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        v.performClick()
                        dismissCurrent(openApp = true)
                    } else {
                        scheduleAutoDismiss()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dismissCurrent(openApp: Boolean) {
        mainHandler.post {
            timeout?.let { mainHandler.removeCallbacks(it) }
            timeout = null
            val view = activeView
            val data = activeData
            if (view == null) {
                if (openApp && data != null) openNotificationTarget(data)
                showNext()
                return@post
            }
            activeView = null
            activeData = null
            activeTitleView = null
            activeBodyView = null

            val isBottom = prefs.getBoolean(MainActivity.KEY_POSITION_BOTTOM, false)
            val exitY = if (isBottom) dp(42).toFloat() else -dp(42).toFloat()

            val finish = Runnable {
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {
                }
                if (openApp && data != null) openNotificationTarget(data)
                showNext()
            }
            view.animate()
                .alpha(0f)
                .scaleX(0.95f)
                .scaleY(0.95f)
                .translationY(exitY)
                .setDuration(170L)
                .withEndAction(finish)
                .start()
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
                    pending.send(context, 0, null, null, null, null, options.toBundle())
                } else {
                    pending.send()
                }
                return
            } catch (_: Exception) {
                // Fall back to launching the package's main activity below
            }
        }

        try {
            val intent = context.packageManager.getLaunchIntentForPackage(data.packageName) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun triggerSubtleHaptic() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(VibrationEffect.createOneShot(18L, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {
        }
    }

    fun dispose() {
        mainHandler.post {
            disposed = true
            timeout?.let { mainHandler.removeCallbacks(it) }
            timeout = null
            queue.clear()
            activeView?.let { view ->
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {
                }
            }
            activeView = null
            activeData = null
            activeTitleView = null
            activeBodyView = null
        }
    }

    private fun makeFallbackIcon() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.rgb(68, 87, 125))
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
