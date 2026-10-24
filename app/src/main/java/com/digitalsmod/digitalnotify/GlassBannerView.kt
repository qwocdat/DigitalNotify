package com.digitalsmod.digitalnotify

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/**
 * Upgraded procedural Liquid Glass surface with proper ViewOutlineProvider clipping,
 * multi-theme AGSL shader support on Android 13+, and fallback multi-stop glass gradients.
 */
class GlassBannerView(
    context: Context,
    private val themeIndex: Int = 0
) : FrameLayout(context) {

    private val strokePx = dp(1.15f)
    private val corner = dp(26f)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = when (themeIndex) {
            1 -> Color.argb(125, 226, 198, 255) // Aurora Violet rim
            2 -> Color.argb(115, 255, 224, 178) // Obsidian Gold rim
            else -> Color.argb(120, 210, 232, 255) // Frost Blue rim
        }
    }
    private val topGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = RectF()
    private val edgeShape = RectF()
    private var shader: Shader? = null
    private var animationStart = SystemClock.uptimeMillis()
    private var animateUntil = animationStart + 1150L

    init {
        setWillNotDraw(false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width > 0 && view.height > 0) {
                    outline.setRoundRect(0, 0, view.width, view.height, corner)
                }
            }
        }
        clipToOutline = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shader = runCatching { GlassShaderApi33.create(AGSL) }.getOrNull()
            if (shader != null) {
                GlassShaderApi33.setTheme(shader, themeIndex)
                fillPaint.shader = shader
            }
        }
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    fun playEntranceSweep() {
        animationStart = SystemClock.uptimeMillis()
        animateUntil = animationStart + 1150L
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shape.set(0f, 0f, w.toFloat(), h.toFloat())
        val inset = strokePx * 0.5f
        edgeShape.set(inset, inset, (w - inset).coerceAtLeast(inset), (h - inset).coerceAtLeast(inset))
        invalidateOutline()

        if (w > 0 && h > 0 && (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || shader == null)) {
            val colors = when (themeIndex) {
                1 -> intArrayOf(
                    Color.argb(236, 22, 15, 38),
                    Color.argb(224, 48, 30, 76),
                    Color.argb(234, 17, 13, 30)
                )
                2 -> intArrayOf(
                    Color.argb(240, 14, 14, 18),
                    Color.argb(228, 34, 31, 28),
                    Color.argb(238, 10, 10, 13)
                )
                else -> intArrayOf(
                    Color.argb(235, 14, 20, 34),
                    Color.argb(222, 29, 43, 68),
                    Color.argb(232, 12, 17, 28)
                )
            }
            fillPaint.shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                colors,
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
            GlassShaderApi33.setResolution(
                shader,
                w.coerceAtLeast(1).toFloat(),
                h.coerceAtLeast(1).toFloat()
            )
        }

        topGlowPaint.shader = LinearGradient(
            0f, 0f, 0f, dp(42f),
            intArrayOf(Color.argb(46, 255, 255, 255), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
            val now = SystemClock.uptimeMillis()
            val elapsedSec = (now - animationStart).coerceAtLeast(0L) / 1000f
            val progress = ((now - animationStart) / 1150f).coerceIn(0f, 1f)
            GlassShaderApi33.setAnimation(shader, elapsedSec, progress)
            if (now < animateUntil) {
                postInvalidateOnAnimation()
            }
        }
        canvas.drawRoundRect(shape, corner, corner, fillPaint)
        canvas.save()
        canvas.clipRect(shape)
        canvas.drawRoundRect(shape, corner, corner, topGlowPaint)
        canvas.restore()
        canvas.drawRoundRect(edgeShape, corner - strokePx * 0.5f, corner - strokePx * 0.5f, edgePaint)
        super.onDraw(canvas)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val AGSL = """
            uniform float2 resolution;
            uniform float time;
            uniform float progress;
            uniform float themeMode;

            half4 main(float2 p) {
                float2 uv = p / max(resolution, float2(1.0, 1.0));
                float edge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
                float rim = exp(-max(edge, 0.0) * 34.0);
                float sweep = 0.5 + 0.5 * sin(uv.x * 5.2 - uv.y * 1.9 + time * 2.2);
                float shine = exp(-pow((uv.x - fract(progress * 1.32)) * 12.0, 2.0)) * (1.0 - progress * 0.68);

                float3 base0 = mix(float3(0.020, 0.030, 0.055), float3(0.100, 0.145, 0.220), uv.y);
                float3 tint0 = float3(0.24, 0.42, 0.74) * sweep * 0.16;
                float3 rim0  = float3(0.66, 0.80, 1.00) * rim * 0.27;

                float3 base1 = mix(float3(0.035, 0.020, 0.065), float3(0.155, 0.095, 0.245), uv.y);
                float3 tint1 = float3(0.55, 0.28, 0.78) * sweep * 0.17;
                float3 rim1  = float3(0.85, 0.68, 1.00) * rim * 0.28;

                float3 base2 = mix(float3(0.018, 0.018, 0.022), float3(0.095, 0.088, 0.080), uv.y);
                float3 tint2 = float3(0.52, 0.42, 0.25) * sweep * 0.13;
                float3 rim2  = float3(1.00, 0.86, 0.62) * rim * 0.24;

                float3 base = base0;
                float3 tint = tint0;
                float3 edgeLight = rim0;

                if (themeMode > 1.5) {
                    base = base2;
                    tint = tint2;
                    edgeLight = rim2;
                } else if (themeMode > 0.5) {
                    base = base1;
                    tint = tint1;
                    edgeLight = rim1;
                }

                float3 highlight = float3(0.86, 0.93, 1.0) * shine * 0.13;
                return half4(base + tint + edgeLight + highlight, 0.90);
            }
        """
    }
}
