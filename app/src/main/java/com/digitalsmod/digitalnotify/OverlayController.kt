package com.digitalsmod.digitalnotify

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
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

internal data class BannerData(
    val packageName: String,
    val appName: String,
    val title: String,
    val body: String,
    val postTime: Long
)

internal class OverlayController(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val queue = ArrayDeque<BannerData>()
    private var activeView: GlassBannerView? = null
    private var activeData: BannerData? = null
    private var timeout: Runnable? = null
    private var disposed = false

    fun show(data: BannerData) {
        mainHandler.post {
            if (disposed || !android.provider.Settings.canDrawOverlays(context)) return@post
            // Avoid showing duplicate callbacks for the same app/title/body in a short burst.
            val current = activeData
            if (current != null && current.packageName == data.packageName && current.title == data.title && current.body == data.body) {
                return@post
            }
            if (queue.size >= 8) queue.removeFirst()
            queue.addLast(data)
            if (activeView == null) showNext()
        }
    }

    private fun showNext() {
        if (disposed || activeView != null || queue.isEmpty()) return
        if (!android.provider.Settings.canDrawOverlays(context)) {
            queue.clear()
            return
        }
        val data = queue.removeFirst()
        val view = buildBanner(data)
        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels - dp(28)).coerceAtLeast(dp(240))
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = statusBarHeight() + dp(8)
            windowAnimations = 0
            dimAmount = 0f
        }
        try {
            activeView = view
            activeData = data
            windowManager.addView(view, params)
            view.alpha = 0f
            view.scaleX = 0.965f
            view.scaleY = 0.965f
            view.translationY = -dp(70).toFloat()
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240L)
                .setInterpolator(OvershootInterpolator(0.7f)).start()
            SpringAnimation(view, DynamicAnimation.TRANSLATION_Y, 0f).apply {
                spring = SpringForce(0f).setDampingRatio(0.78f).setStiffness(650f)
                setStartValue(-dp(70).toFloat())
                start()
            }
            view.playEntranceSweep()
            val end = Runnable { dismissCurrent(openApp = false) }
            timeout = end
            mainHandler.postDelayed(end, 5200L)
        } catch (_: Exception) {
            activeView = null
            activeData = null
            mainHandler.post { showNext() }
        }
    }

    private fun buildBanner(data: BannerData): GlassBannerView {
        val root = GlassBannerView(context).apply {
            setPadding(dp(13), dp(13), dp(12), dp(13))
            elevation = dp(16).toFloat()
            isClickable = true
            contentDescription = "Thông báo từ ${data.appName}: ${data.title}. ${data.body}"
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val appIcon = ImageView(context).apply {
            try {
                setImageDrawable(context.packageManager.getApplicationIcon(data.packageName))
            } catch (_: Exception) {
                setImageDrawable(makeFallbackIcon(data.appName))
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(Color.rgb(42, 49, 65), dp(12))
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        row.addView(appIcon, LinearLayout.LayoutParams(dp(42), dp(42)))

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, dp(5), 0)
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = data.appName.uppercase()
            textSize = 10f
            letterSpacing = 0.07f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.rgb(206, 215, 231))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(context).apply {
            text = "NOW"
            textSize = 9f
            letterSpacing = 0.06f
            setTextColor(Color.rgb(154, 166, 188))
        })
        textColumn.addView(header, LinearLayout.LayoutParams(-1, -2))
        textColumn.addView(TextView(context).apply {
            text = data.title
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        textColumn.addView(TextView(context).apply {
            text = data.body
            textSize = 12f
            setTextColor(Color.rgb(210, 216, 228))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))

        val close = TextView(context).apply {
            text = "×"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(200, 208, 224))
            background = rounded(Color.argb(38, 255, 255, 255), dp(17))
            contentDescription = "Đóng thông báo"
            setOnClickListener { dismissCurrent(openApp = false) }
        }
        row.addView(close, LinearLayout.LayoutParams(dp(30), dp(30)).apply { leftMargin = dp(5) })
        root.addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.setOnClickListener { dismissCurrent(openApp = true) }
        close.bringToFront()
        return root
    }

    private fun dismissCurrent(openApp: Boolean) {
        mainHandler.post {
            timeout?.let { mainHandler.removeCallbacks(it) }
            timeout = null
            val view = activeView
            val data = activeData
            if (view == null) {
                if (openApp && data != null) launchApp(data.packageName)
                showNext()
                return@post
            }
            activeView = null
            activeData = null
            val finish = Runnable {
                try { windowManager.removeView(view) } catch (_: Exception) { }
                if (openApp && data != null) launchApp(data.packageName)
                showNext()
            }
            view.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).translationY(-dp(34).toFloat())
                .setDuration(170L).withEndAction(finish).start()
        }
    }

    private fun launchApp(packageName: String) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) { }
    }

    fun dispose() {
        mainHandler.post {
            disposed = true
            timeout?.let { mainHandler.removeCallbacks(it) }
            timeout = null
            queue.clear()
            activeView?.let { view -> try { windowManager.removeView(view) } catch (_: Exception) { } }
            activeView = null
            activeData = null
        }
    }

    private fun makeFallbackIcon(label: String) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
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
