package com.amr3d.preview.pro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * شريط تقدّم بتوهج وموجة لمعان متحركة فوق الجزء المعبّى — نسخة أندرويد من نفس
 * تصميم شريط التحميل في الـ HTML preview بتاع المستخدم، بديل الـ ProgressBar
 * العادي. بتتغذى بـ progress/statusText من نفس الـ ValueAnimator الموجود في
 * SplashActivity من غير ما تتغيّر منطق التوقيت.
 */
class GlowProgressBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    var statusText: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var accentColor: Int = 0xFFFF8A1E.toInt()
        set(value) {
            field = value
            fillPaint.color = value
            fillPaint.setShadowLayer(22f, 0f, 0f, value)
            statusPaint.color = value
            pctPaint.color = value
            rebuildWaveShader()
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.resources.displayMetrics.scaledDensity * 11f
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.resources.displayMetrics.scaledDensity * 12f
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }
    private val completeColor = Color.parseColor("#00FF66")

    private var waveOffset = 0f
    private var waveShader: LinearGradient? = null
    private val barRect = RectF()
    private val clipPath = Path()

    private fun rebuildWaveShader() {
        val c = accentColor
        waveShader = LinearGradient(
            -30f, 0f, 30f, 0f,
            intArrayOf(
                Color.argb(0, Color.red(c), Color.green(c), Color.blue(c)),
                Color.argb(140, Color.red(c), Color.green(c), Color.blue(c)),
                Color.argb(0, Color.red(c), Color.green(c), Color.blue(c))
            ),
            null, Shader.TileMode.CLAMP
        )
    }

    init {
        rebuildWaveShader()
    }

    /** بيتنادى كل فريم من حلقة الرندر الخارجية عشان يحرّك الموجة */
    fun onFrameTick() {
        waveOffset += 3.5f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val barH = height * 0.4f
        val barTop = height - barH
        barRect.set(0f, barTop, width.toFloat(), barTop + barH)
        val fillW = barRect.width() * progress / 100f

        // نص الحالة + النسبة فوق الشريط
        val labelY = barTop - 18f
        statusPaint.color = if (progress >= 100) completeColor else accentColor
        canvas.drawText(statusText, 0f, labelY, statusPaint)

        // النسبة بتمشي فوق حافة الجزء المعبّى من الشريط بدل ما تفضل ثابتة يمين الشريط
        pctPaint.color = if (progress >= 100) completeColor else accentColor
        val minX = 24f
        val maxX = width - 4f
        val pctX = fillW.coerceIn(minX, maxX)
        pctPaint.textAlign = if (pctX >= maxX - 6f) Paint.Align.RIGHT else Paint.Align.CENTER
        canvas.drawText("$progress%", pctX, labelY, pctPaint)

        // خلفية الشريط
        val c = accentColor
        trackPaint.color = Color.argb(28, Color.red(c), Color.green(c), Color.blue(c))
        canvas.drawRoundRect(barRect, barH / 2f, barH / 2f, trackPaint)

        if (progress <= 0) return
        val fillRect = RectF(0f, barTop, fillW, barTop + barH)
        canvas.drawRoundRect(fillRect, barH / 2f, barH / 2f, fillPaint)

        // موجة لمعان متحركة فوق الجزء المعبّى
        if (waveOffset > fillW + 60f) waveOffset = -60f
        val shader = waveShader ?: return
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(fillRect, barH / 2f, barH / 2f, Path.Direction.CW)
        canvas.clipPath(clipPath)
        val matrix = android.graphics.Matrix().apply { setTranslate(waveOffset, 0f) }
        shader.setLocalMatrix(matrix)
        wavePaint.shader = shader
        canvas.drawRect(0f, barTop, fillW, barTop + barH, wavePaint)
        canvas.restore()
    }
}
