package com.amr3d.preview.pro

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * حلقة دوّارة بتدرج لوني — تحيط باللوجو في شاشة الـ Splash.
 */
class RingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var accent1: Int = Color.parseColor("#FF8A1E")
    var accent2: Int = Color.parseColor("#FFD23F")
    var touchForce: Float = 0f
        get() = field
        set(v) { field = v; invalidate() }

    private var ringAngle = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun tick() {
        ringAngle = (ringAngle + 2.2f + touchForce * 5f) % 360f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(cx, cy) - 8f

        // الحلقة الأساسية الداكنة
        paint.strokeWidth = 3.5f + touchForce * 4f
        paint.color = Color.parseColor("#1A1D24")
        paint.alpha = 255
        canvas.drawCircle(cx, cy, r, paint)

        // القوس المتدرج
        paint.strokeWidth = 3.5f + touchForce * 4f
        val r1 = Color.red(accent1); val g1 = Color.green(accent1); val b1 = Color.blue(accent1)
        val r2 = Color.red(accent2); val g2 = Color.green(accent2); val b2 = Color.blue(accent2)

        for (i in 0 until 360 step 2) {
            val t = i / 360f
            val a1 = (i + ringAngle) * PI.toFloat() / 180f
            val a2 = (i + 2 + ringAngle) * PI.toFloat() / 180f
            val alpha = sin(t * PI.toFloat()).pow(1.2f) * 0.98f + 0.02f + touchForce * 0.5f

            val ri = (r1 + (r2-r1)*t).toInt()
            val gi = (g1 + (g2-g1)*t).toInt()
            val bi = (b1 + (b2-b1)*t).toInt()
            paint.color = Color.rgb(ri, gi, bi)
            paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)

            val rect = RectF(cx-r, cy-r, cx+r, cy+r)
            canvas.drawArc(rect, a1*180f/PI.toFloat(), 2f, false, paint)
        }

        // نقطة الضوء المتحركة
        val gx = cx + r * cos(ringAngle * PI.toFloat() / 180f)
        val gy = cy + r * sin(ringAngle * PI.toFloat() / 180f)
        val glowR = 28f + touchForce * 20f
        val glow = RadialGradient(gx, gy, glowR, intArrayOf(accent2, Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        glowPaint.shader = glow
        canvas.drawCircle(gx, gy, glowR, glowPaint)
    }
}
