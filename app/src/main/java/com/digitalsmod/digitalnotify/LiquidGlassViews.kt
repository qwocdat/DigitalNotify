package com.digitalsmod.digitalnotify

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Interactive iOS 27 Liquid Glass Button:
 * - On press or hold-to-drag, morphs into a glowing translucent liquid droplet surface
 * - Specular refraction highlight follows the user's touch coordinate in real time
 */
internal class LiquidGlassButton(
    context: Context,
    textValue: String,
    private val isPrimary: Boolean = false,
    private val onClick: () -> Unit
) : FrameLayout(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val liquidGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val labelView: TextView

    private var touchX = 0f
    private var touchY = 0f
    private var pressProgress = 0f
    private var pressAnimator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        val padH = dp(16).toInt()
        val padV = dp(13).toInt()
        setPadding(padH, padV, padH, padV)

        labelView = TextView(context).apply {
            text = textValue
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        addView(labelView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    fun setText(value: String) {
        labelView.text = value
    }

    private fun animatePress(to: Float) {
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressProgress, to).apply {
            duration = if (to > pressProgress) 140L else 260L
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener { va ->
                pressProgress = (va.animatedValue as Float).coerceIn(0f, 1.15f)
                val s = 1f + 0.035f * pressProgress
                scaleX = s
                scaleY = 1f - 0.02f * pressProgress
                invalidate()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchX = event.x
                touchY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                animatePress(1f)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = event.x.coerceIn(0f, width.toFloat())
                touchY = event.y.coerceIn(0f, height.toFloat())
                // Slight liquid follow displacement while holding & dragging
                translationX = ((event.x - width * 0.5f) / width.coerceAtLeast(1)) * dp(8f)
                translationY = ((event.y - height * 0.5f) / height.coerceAtLeast(1)) * dp(5f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                animate().translationX(0f).translationY(0f).setDuration(200L).start()
                animatePress(0f)
                performClick()
                onClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                animate().translationX(0f).translationY(0f).setDuration(200L).start()
                animatePress(0f)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val inset = dp(1f)
        rect.set(inset, inset, w - inset, h - inset)
        val radius = h * 0.5f

        val topAlpha = (if (isPrimary) 175 else 95) + (55 * pressProgress).toInt().coerceIn(0, 75)
        val botAlpha = (if (isPrimary) 135 else 65) + (50 * pressProgress).toInt().coerceIn(0, 75)

        val c1 = if (isPrimary) Color.argb(topAlpha, 58, 132, 255) else Color.argb(topAlpha, 255, 255, 255)
        val c2 = if (isPrimary) Color.argb(botAlpha, 32, 82, 210) else Color.argb(botAlpha, 180, 205, 255)

        bgPaint.shader = LinearGradient(0f, 0f, w, h, c1, c2, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        // When pressed or dragged, render the liquid glass droplet refraction following the finger
        if (pressProgress > 0.01f) {
            val glowRadius = (w * 0.55f).coerceAtLeast(dp(40f))
            val glowAlpha = (145 * pressProgress.coerceIn(0f, 1f)).toInt()
            liquidGlowPaint.shader = RadialGradient(
                touchX,
                touchY,
                glowRadius,
                intArrayOf(
                    Color.argb(glowAlpha, 255, 255, 255),
                    Color.argb((glowAlpha * 0.45f).toInt(), 160, 220, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, liquidGlowPaint)
        }

        val rimAlpha = (130 + 95 * pressProgress.coerceIn(0f, 1f)).toInt()
        borderPaint.shader = LinearGradient(
            0f, 0f, w, h,
            Color.argb(rimAlpha, 255, 255, 255),
            Color.argb((rimAlpha * 0.45f).toInt(), 165, 210, 255),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        super.onDraw(canvas)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}

/**
 * Interactive iOS 27 Liquid Glass Switch:
 * Supports both tap-to-toggle AND hold-to-drag thumb with liquid glass stretch!
 */
internal class LiquidGlassSwitch(
    context: Context,
    initialChecked: Boolean,
    private val onChanged: (Boolean) -> Unit
) : android.view.View(context) {

    var isChecked: Boolean = initialChecked
        private set

    private var thumbPosition = if (initialChecked) 1f else 0f
    private var liquidStretch = 0f
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()
    private val thumbRect = RectF()
    private var posAnimator: ValueAnimator? = null
    private var startX = 0f
    private var dragging = false

    fun setCheckedProgrammatically(checked: Boolean, notify: Boolean = false) {
        if (isChecked == checked) return
        isChecked = checked
        animateThumbTo(if (checked) 1f else 0f)
        if (notify) onChanged(checked)
    }

    private fun animateThumbTo(target: Float) {
        posAnimator?.cancel()
        posAnimator = ValueAnimator.ofFloat(thumbPosition, target).apply {
            duration = 220L
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener { va ->
                thumbPosition = (va.animatedValue as Float).coerceIn(0f, 1f)
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(54f).toInt(), dp(31f).toInt())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                dragging = false
                liquidStretch = 1f
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - startX) > dp(4f)) {
                    dragging = true
                }
                if (dragging) {
                    val pad = dp(3f)
                    val usable = (width - height).coerceAtLeast(1).toFloat()
                    thumbPosition = ((event.x - height * 0.5f + pad) / usable).coerceIn(0f, 1f)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                liquidStretch = 0f
                val newState = if (dragging) thumbPosition >= 0.5f else !isChecked
                isChecked = newState
                animateThumbTo(if (newState) 1f else 0f)
                onChanged(newState)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h * 0.5f
        trackRect.set(dp(1f), dp(1f), w - dp(1f), h - dp(1f))

        // Blend track between translucent glass gray (off) and iOS 27 liquid cyan-blue (on)
        val red = (40 + (52 - 40) * thumbPosition).toInt()
        val green = (48 + (199 - 48) * thumbPosition).toInt()
        val blue = (66 + (89 - 66) * thumbPosition).toInt()
        val alpha = (135 + 85 * thumbPosition).toInt()
        trackPaint.color = Color.argb(alpha, red, green, blue)
        canvas.drawRoundRect(trackRect, r, r, trackPaint)

        trackBorderPaint.color = Color.argb(115, 255, 255, 255)
        canvas.drawRoundRect(trackRect, r, r, trackBorderPaint)

        // Thumb droplet (stretches horizontally into a liquid glass capsule while held!)
        val pad = dp(3f)
        val baseThumbD = h - pad * 2f
        val extraW = dp(5f) * liquidStretch
        val thumbW = baseThumbD + extraW
        val maxLeft = w - pad - thumbW
        val left = pad + (maxLeft - pad) * thumbPosition
        thumbRect.set(left, pad, left + thumbW, h - pad)

        thumbPaint.shader = LinearGradient(
            thumbRect.left, thumbRect.top, thumbRect.right, thumbRect.bottom,
            if (liquidStretch > 0.1f) Color.argb(230, 235, 246, 255) else Color.WHITE,
            if (liquidStretch > 0.1f) Color.argb(195, 190, 228, 255) else Color.rgb(232, 238, 248),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(thumbRect, baseThumbD * 0.5f, baseThumbD * 0.5f, thumbPaint)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
