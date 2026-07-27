package com.amr3d.preview.pro

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * Animated wireframe cube rotating in background - Blur/Neon game style
 */
class WireframeAnimView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var angle = 0f
    private var angleY = 0f

    // ألوان النيون
    private val neonOrange = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8A1E")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        alpha = 180
        maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
    }
    private val neonBlue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E90FF")
        style = Paint.Style.STROKE
        strokeWidth = 1f
        alpha = 120
        maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8A1E")
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
    }

    // جسيمات عشوائية
    private val particles = Array(40) {
        floatArrayOf(
            (Math.random() * 1000).toFloat(),
            (Math.random() * 2000).toFloat(),
            (Math.random() * 3 + 1).toFloat(),  // speed
            (Math.random() * 3 + 1).toFloat()   // size
        )
    }

    // نقاط المكعب
    private val cubeVertices = arrayOf(
        floatArrayOf(-1f, -1f, -1f), floatArrayOf(1f, -1f, -1f),
        floatArrayOf(1f, 1f, -1f),   floatArrayOf(-1f, 1f, -1f),
        floatArrayOf(-1f, -1f, 1f),  floatArrayOf(1f, -1f, 1f),
        floatArrayOf(1f, 1f, 1f),    floatArrayOf(-1f, 1f, 1f)
    )
    private val cubeEdges = arrayOf(
        intArrayOf(0,1), intArrayOf(1,2), intArrayOf(2,3), intArrayOf(3,0),
        intArrayOf(4,5), intArrayOf(5,6), intArrayOf(6,7), intArrayOf(7,4),
        intArrayOf(0,4), intArrayOf(1,5), intArrayOf(2,6), intArrayOf(3,7)
    )

    // مثمن أيضاً في الخلفية
    private val octaVertices = arrayOf(
        floatArrayOf(0f, 1.5f, 0f),  floatArrayOf(0f, -1.5f, 0f),
        floatArrayOf(1.5f, 0f, 0f),  floatArrayOf(-1.5f, 0f, 0f),
        floatArrayOf(0f, 0f, 1.5f),  floatArrayOf(0f, 0f, -1.5f)
    )
    private val octaEdges = arrayOf(
        intArrayOf(0,2), intArrayOf(0,3), intArrayOf(0,4), intArrayOf(0,5),
        intArrayOf(1,2), intArrayOf(1,3), intArrayOf(1,4), intArrayOf(1,5),
        intArrayOf(2,4), intArrayOf(4,3), intArrayOf(3,5), intArrayOf(5,2)
    )

    private fun project(v: FloatArray, cx: Float, cy: Float, scale: Float, rx: Float, ry: Float): FloatArray {
        // Rotate Y
        val cosY = cos(ry); val sinY = sin(ry)
        val x1 = v[0] * cosY - v[2] * sinY
        val z1 = v[0] * sinY + v[2] * cosY

        // Rotate X
        val cosX = cos(rx); val sinX = sin(rx)
        val y1 = v[1] * cosX - z1 * sinX
        val z2 = v[1] * sinX + z1 * cosX

        // Perspective projection
        val fov = 4f / (z2 + 6f)
        return floatArrayOf(cx + x1 * scale * fov, cy + y1 * scale * fov)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w * 0.5f; val cy = h * 0.4f

        // رسم الـ particles
        for (p in particles) {
            p[1] -= p[2]
            if (p[1] < 0) p[1] = h
            particlePaint.alpha = (80 + Math.random() * 80).toInt()
            canvas.drawCircle(p[0] % w, p[1], p[3], particlePaint)
        }

        // رسم المثمن في الخلفية (أكبر وأشفاف)
        neonBlue.alpha = 60
        neonBlue.strokeWidth = 0.8f
        val octScale = minOf(w, h) * 0.35f
        for (edge in octaEdges) {
            val p1 = project(octaVertices[edge[0]], cx, cy, octScale, angle * 0.7f, angleY * 0.5f)
            val p2 = project(octaVertices[edge[1]], cx, cy, octScale, angle * 0.7f, angleY * 0.5f)
            canvas.drawLine(p1[0], p1[1], p2[0], p2[1], neonBlue)
        }

        // رسم المكعب الرئيسي
        val scale = minOf(w, h) * 0.22f
        for (edge in cubeEdges) {
            val p1 = project(cubeVertices[edge[0]], cx, cy, scale, angle, angleY)
            val p2 = project(cubeVertices[edge[1]], cx, cy, scale, angle, angleY)

            // Glow effect - رسمين، واحد سميك شفاف وواحد رفيع واضح
            neonOrange.strokeWidth = 4f
            neonOrange.alpha = 40
            canvas.drawLine(p1[0], p1[1], p2[0], p2[1], neonOrange)

            neonOrange.strokeWidth = 1.5f
            neonOrange.alpha = 200
            canvas.drawLine(p1[0], p1[1], p2[0], p2[1], neonOrange)
        }

        // خطوط السرعة في الأسفل (Blur style)
        val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            maskFilter = BlurMaskFilter(3f, BlurMaskFilter.Blur.NORMAL)
        }
        for (i in 0..15) {
            val x = w * 0.1f + (w * 0.8f / 15f) * i
            val lineH = (30 + Math.random() * 60).toFloat()
            speedPaint.color = if (i % 3 == 0) Color.parseColor("#FF8A1E") else Color.parseColor("#1E90FF")
            speedPaint.alpha = (60 + Math.random() * 80).toInt()
            canvas.drawLine(x, h * 0.85f, x, h * 0.85f + lineH, speedPaint)
        }

        // تحديث الزوايا
        angle += 0.012f
        angleY += 0.018f

        postInvalidateDelayed(16) // ~60fps
    }
}
