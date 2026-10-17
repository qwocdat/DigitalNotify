package com.digitalsmod.digitalnotify

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.View

/**
 * A procedural glass surface. Android composites its translucency over the app behind it;
 * this shader draws lighting/rim detail but does not sample pixels from other app windows.
 */
class GlassBannerView(context: Context) : android.widget.FrameLayout(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(105, 220, 233, 255)
    }
    private val topGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = RectF()
    private val corner = dp(27f)
    private var shader: Shader? = null
    private var animationStart = SystemClock.uptimeMillis()
    private var animateUntil = animationStart + 1000L

    init {
        setWillNotDraw(false)
        clipToOutline = true
        if (Build.VERSION.SDK_INT >= 33) {
            shader = runCatching { GlassShaderApi33.create(AGSL) }.getOrNull()
            fillPaint.shader = shader
        }
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    fun playEntranceSweep() {
        animationStart = SystemClock.uptimeMillis()
        animateUntil = animationStart + 1000L
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shape.set(0f, 0f, w.toFloat(), h.toFloat())
        if (Build.VERSION.SDK_INT < 33 && w > 0 && h > 0) {
            fillPaint.shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(Color.argb(232, 16, 21, 33), Color.argb(218, 32, 42, 61), Color.argb(228, 13, 17, 27)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            GlassShaderApi33.setResolution(shader, w.coerceAtLeast(1).toFloat(), h.coerceAtLeast(1).toFloat())
        }
        topGlowPaint.shader = LinearGradient(
            0f, 0f, 0f, dp(40f),
            intArrayOf(Color.argb(35, 255, 255, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (Build.VERSION.SDK_INT >= 33) {
            val now = SystemClock.uptimeMillis()
            GlassShaderApi33.setAnimation(shader, ((now - animationStart).coerceAtLeast(0L) / 1000f), ((now - animationStart) / 1000f).coerceIn(0f, 1f))
            if (now < animateUntil) postInvalidateOnAnimation()
        }
        canvas.drawRoundRect(shape, corner, corner, fillPaint)
        canvas.drawRoundRect(shape, corner, corner, edgePaint)
        canvas.save()
        canvas.clipRect(shape)
        canvas.drawRoundRect(shape, corner, corner, topGlowPaint)
        canvas.restore()
        super.onDraw(canvas)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    companion object {
        private const val AGSL = """
            uniform float2 resolution;
            uniform float time;
            uniform float progress;

            half4 main(float2 p) {
                float2 uv = p / max(resolution, float2(1.0, 1.0));
                float edge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
                float rim = exp(-max(edge, 0.0) * 36.0);
                float sweep = 0.5 + 0.5 * sin(uv.x * 5.0 - uv.y * 1.8 + time * 2.1);
                float shine = exp(-pow((uv.x - fract(progress * 1.35)) * 13.0, 2.0)) * (1.0 - progress * 0.7);
                float3 base = mix(float3(0.018, 0.025, 0.043), float3(0.105, 0.14, 0.205), uv.y);
                float3 tint = float3(0.22, 0.38, 0.67) * sweep * 0.15;
                float3 edgeLight = float3(0.65, 0.78, 1.0) * rim * 0.25;
                float3 highlight = float3(0.8, 0.9, 1.0) * shine * 0.11;
                return half4(base + tint + edgeLight + highlight, 0.88);
            }
        """
    }
}
