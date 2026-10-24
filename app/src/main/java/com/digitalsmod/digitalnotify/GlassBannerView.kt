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
 * Real optical Liquid Glass surface matching 90047.mp4:
 * - Strictly premultiplied alpha in AGSL so hardware blur (`FLAG_BLUR_BEHIND`) shines through naturally
 * - Rounded-rectangle SDF (`sdRoundBox`) optical bevel, internal reflection line, and bottom lens caustic
 */
class GlassBannerView(
    context: Context,
    private val themeIndex: Int = 0
) : FrameLayout(context) {

    private val strokePx = dp(1.05f)
    val cornerRadiusPx = dp(26f)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
    }
    private val innerBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
    }
    private val topGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bottomLensPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = RectF()
    private val edgeShape = RectF()
    private val innerBevelShape = RectF()
    private var shader: Shader? = null
    private var animationStart = SystemClock.uptimeMillis()
    private var animateUntil = animationStart + 750L

    init {
        setWillNotDraw(false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width > 0 && view.height > 0) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
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
        animateUntil = animationStart + 750L
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shape.set(0f, 0f, w.toFloat(), h.toFloat())
        val inset = strokePx * 0.5f
        edgeShape.set(inset, inset, (w - inset).coerceAtLeast(inset), (h - inset).coerceAtLeast(inset))
        val bevelInset = dp(2.4f)
        innerBevelShape.set(
            bevelInset,
            bevelInset,
            (w - bevelInset).coerceAtLeast(bevelInset),
            (h - bevelInset).coerceAtLeast(bevelInset)
        )
        invalidateOutline()

        if (w > 0 && h > 0 && (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || shader == null)) {
            val colors = when (themeIndex) {
                1 -> intArrayOf(
                    Color.argb(145, 22, 28, 42),
                    Color.argb(125, 34, 44, 66),
                    Color.argb(150, 18, 24, 36)
                )
                else -> intArrayOf(
                    // Translucent frosty glass veil (~44% alpha) so real blurred background passes through
                    Color.argb(118, 250, 248, 252),
                    Color.argb(104, 232, 235, 242),
                    Color.argb(126, 252, 252, 255)
                )
            }
            fillPaint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                colors,
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
            GlassShaderApi33.setResolution(
                shader,
                w.coerceAtLeast(1).toFloat(),
                h.coerceAtLeast(1).toFloat(),
                cornerRadiusPx
            )
        }

        // Specular diagonal rim gradient (brightest on top-left and bottom-right like real curved glass)
        edgePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                Color.argb(215, 255, 255, 255),
                Color.argb(110, 230, 238, 255),
                Color.argb(200, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        // Subtle inner refractive bevel
        innerBevelPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.argb(75, 255, 255, 255),
                Color.argb(24, 15, 20, 30),
                Color.argb(95, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        topGlowPaint.shader = LinearGradient(
            0f, 0f, 0f, dp(24f),
            intArrayOf(Color.argb(68, 255, 255, 255), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )

        // Bottom curved caustic lens band (replicates the bright bottom refraction band in 90047.mp4)
        bottomLensPaint.shader = LinearGradient(
            0f, (h - dp(18f)).coerceAtLeast(0f), 0f, h.toFloat(),
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(82, 255, 255, 255),
                Color.argb(38, 30, 36, 48)
            ),
            floatArrayOf(0f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
            val now = SystemClock.uptimeMillis()
            val elapsedSec = (now - animationStart).coerceAtLeast(0L) / 1000f
            val progress = ((now - animationStart) / 750f).coerceIn(0f, 1f)
            GlassShaderApi33.setAnimation(shader, elapsedSec, progress)
            if (now < animateUntil) {
                postInvalidateOnAnimation()
            }
        }
        canvas.drawRoundRect(shape, cornerRadiusPx, cornerRadiusPx, fillPaint)
        canvas.save()
        canvas.clipRect(shape)
        canvas.drawRoundRect(shape, cornerRadiusPx, cornerRadiusPx, topGlowPaint)
        canvas.drawRoundRect(shape, cornerRadiusPx, cornerRadiusPx, bottomLensPaint)
        canvas.restore()
        canvas.drawRoundRect(
            innerBevelShape,
            (cornerRadiusPx - dp(2.4f)).coerceAtLeast(0f),
            (cornerRadiusPx - dp(2.4f)).coerceAtLeast(0f),
            innerBevelPaint
        )
        canvas.drawRoundRect(
            edgeShape,
            cornerRadiusPx - strokePx * 0.5f,
            cornerRadiusPx - strokePx * 0.5f,
            edgePaint
        )
        super.onDraw(canvas)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val AGSL = """
            uniform float2 resolution;
            uniform float cornerRadius;
            uniform float time;
            uniform float progress;
            uniform float themeMode;

            float sdRoundBox(float2 p, float2 b, float r) {
                float2 q = abs(p) - b + r;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
            }

            half4 main(float2 p) {
                float2 halfRes = max(resolution * 0.5, float2(1.0, 1.0));
                float r = clamp(cornerRadius, 1.0, min(halfRes.x, halfRes.y));
                // Signed distance inward from the rounded pill border (0 at border, positive inside)
                float distInward = max(-sdRoundBox(p - halfRes, halfRes, r), 0.0);
                float2 uv = p / max(resolution, float2(1.0, 1.0));

                // 3D curved glass optical bevel profile:
                // 1) Crisp outer specular rim (0..2.2px)
                float outerRim = exp(-distInward * 0.55);
                // 2) Subtle internal-reflection dark trough (2.5..6.5px) giving physical glass depth
                float trough = exp(-pow((distInward - 4.2) / 2.2, 2.0));
                // 3) Inner convex lens caustic band (6..22px), strongest along bottom & corners
                float bottomWeight = smoothstep(0.35, 1.0, uv.y) * 0.75 + 0.25;
                float causticBand = exp(-pow((distInward - 11.5) / 6.5, 2.0)) * bottomWeight;

                // Subtle entrance light refraction sweep
                float shine = exp(-pow((uv.x - fract(progress * 1.18)) * 8.5, 2.0)) * (1.0 - progress * 0.82);

                if (themeMode > 0.5) {
                    float3 darkVeil = mix(float3(0.09, 0.12, 0.18), float3(0.14, 0.19, 0.28), uv.y);
                    float3 darkSpec = float3(0.82, 0.90, 1.0) * (outerRim * 0.42 + causticBand * 0.18 + shine * 0.10);
                    float3 rgb = clamp(darkVeil + darkSpec - float3(0.04) * trough, 0.0, 1.0);
                    float alpha = clamp(0.56 + outerRim * 0.22 + causticBand * 0.08, 0.0, 0.88);
                    // CRITICAL: AGSL requires premultiplied alpha!
                    return half4(rgb * alpha, alpha);
                }

                // Exact iOS 26 Silvery-Frost Liquid Glass from 90047.mp4:
                // Highly translucent interior (alpha ~ 0.44) so real blurred widgets behind pass through,
                // with 3D optical bevel refraction around the rounded pill perimeter.
                float3 glassVeil = mix(float3(0.97, 0.96, 0.98), float3(0.91, 0.93, 0.96), uv.y);
                float3 specLight = float3(1.0, 1.0, 1.0) * (outerRim * 0.36 + causticBand * 0.24 + shine * 0.10);
                float3 troughShadow = float3(0.18, 0.20, 0.26) * (trough * 0.12);
                float3 rgb = clamp(glassVeil + specLight - troughShadow, 0.0, 1.0);

                float baseAlpha = 0.43;
                float bevelAlpha = outerRim * 0.32 + causticBand * 0.14 + trough * 0.05;
                float alpha = clamp(baseAlpha + bevelAlpha, 0.0, 0.84);

                // Strictly premultiplied alpha output so Skia composites true glass translucency!
                return half4(rgb * alpha, alpha);
            }
        """
    }
}
