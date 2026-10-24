package com.digitalsmod.digitalnotify

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

internal object DemoIcons {

    fun makeCircularAvatar(resources: Resources, source: Drawable?, sizePx: Int, fallbackTitle: String): Drawable {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        if (source != null) {
            val srcBmp = drawableToBitmap(source, sizePx, sizePx)
            canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(srcBmp, 0f, 0f, paint)
            return BitmapDrawable(resources, bmp)
        }

        paint.color = Color.WHITE
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 52, 112)
            textSize = sizePx * 0.42f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val initial = fallbackTitle.trim().take(1).uppercase().ifEmpty { "N" }
        val fm = textPaint.fontMetrics
        val cy = sizePx / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(initial, sizePx / 2f, cy, textPaint)
        return BitmapDrawable(resources, bmp)
    }

    fun createTechVerseAvatar(resources: Resources, sizePx: Int): Drawable {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(18, 22, 30)
            textSize = sizePx * 0.165f
            typeface = Typeface.create("sans-serif-medium", Typeface.ITALIC)
        }
        canvas.drawText("TechVerse", sizePx * 0.11f, sizePx * 0.54f, textPaint)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 78, 168)
            strokeWidth = sizePx * 0.025f
        }
        canvas.drawLine(sizePx * 0.11f, sizePx * 0.58f, sizePx * 0.68f, sizePx * 0.58f, linePaint)

        val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                sizePx * 0.68f, sizePx * 0.36f, sizePx * 0.88f, sizePx * 0.64f,
                Color.rgb(56, 148, 255), Color.rgb(18, 52, 138), Shader.TileMode.CLAMP
            )
            strokeWidth = sizePx * 0.055f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(sizePx * 0.70f, sizePx * 0.38f, sizePx * 0.86f, sizePx * 0.62f, xPaint)
        canvas.drawLine(sizePx * 0.86f, sizePx * 0.38f, sizePx * 0.70f, sizePx * 0.62f, xPaint)

        return BitmapDrawable(resources, bmp)
    }

    fun createTikTokBadge(resources: Resources, sizePx: Int): Drawable {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rect = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        val r = sizePx * 0.26f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(12, 12, 14) }
        canvas.drawRoundRect(rect, r, r, bgPaint)

        val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.12f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        notePaint.color = Color.rgb(37, 244, 238)
        drawTikTokNote(canvas, sizePx, -sizePx * 0.03f, -sizePx * 0.02f, notePaint)

        notePaint.color = Color.rgb(254, 44, 85)
        drawTikTokNote(canvas, sizePx, sizePx * 0.03f, sizePx * 0.02f, notePaint)

        notePaint.color = Color.WHITE
        drawTikTokNote(canvas, sizePx, 0f, 0f, notePaint)

        return BitmapDrawable(resources, bmp)
    }

    private fun drawTikTokNote(canvas: Canvas, s: Int, dx: Float, dy: Float, paint: Paint) {
        val path = Path().apply {
            moveTo(s * 0.56f + dx, s * 0.24f + dy)
            lineTo(s * 0.56f + dx, s * 0.62f + dy)
            quadTo(s * 0.56f + dx, s * 0.76f + dy, s * 0.41f + dx, s * 0.76f + dy)
            quadTo(s * 0.28f + dx, s * 0.76f + dy, s * 0.28f + dx, s * 0.63f + dy)
            quadTo(s * 0.28f + dx, s * 0.50f + dy, s * 0.42f + dx, s * 0.51f + dy)
        }
        canvas.drawPath(path, paint)
        val hook = Path().apply {
            moveTo(s * 0.56f + dx, s * 0.26f + dy)
            quadTo(s * 0.62f + dx, s * 0.39f + dy, s * 0.74f + dx, s * 0.40f + dy)
        }
        canvas.drawPath(hook, paint)
    }

    fun drawableToBitmap(drawable: Drawable, width: Int, height: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return Bitmap.createScaledBitmap(drawable.bitmap, width, height, true)
        }
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val oldBounds = Rect(drawable.bounds)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        drawable.bounds = oldBounds
        return bmp
    }
}
