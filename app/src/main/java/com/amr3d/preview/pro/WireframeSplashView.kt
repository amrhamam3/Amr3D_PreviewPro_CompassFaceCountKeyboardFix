package com.amr3d.preview.pro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class WireframeSplashView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val particles = mutableListOf<Particle>()
    private val particleCount = 130
    private val maxDistance = 170f
    private var touchX: Float? = null
    private var touchY: Float? = null
    private val touchRadius = 200f

    /** أقصى قوة تأثير باللمس في آخر إطار — بيتقرا من برّه (مثلاً RingView) */
    var touchForce: Float = 0f
        private set

    /** لون خلفية الشاشة — افتراضيًا الكحلي الغامق الأصلي دايمًا، لكن SplashActivity
     * بتظبطه حسب الوضع الفاتح/الغامق المحفوظ (شوف AppDisplayMode) قبل أول رسمة. */
    var backgroundColorValue: Int = 0xFF020510.toInt()

    /** لون التشيم الحالي — بيتغيّر مع الثيم المختار من الإعدادات */
    var accentColor: Int = 0xFFFF8A1E.toInt()
        set(value) {
            field = value
            pointPaint.color = value
            pointPaint.setShadowLayer(16f, 0f, 0f, value)
            linePaint.color = value
        }

    private val pointPaint = Paint().apply {
        color = 0xFFFF8A1E.toInt() // برتقاني اللوجو (افتراضي)
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(16f, 0f, 0f, 0xFFFF8A1E.toInt())
    }

    private val linePaint = Paint().apply {
        color = 0xFFFF8A1E.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }

    init {
        post { // عشان نجيب العرض والطول
            repeat(particleCount) {
                particles.add(
                    Particle(
                        x = (0..width).random().toFloat(),
                        y = (0..height).random().toFloat(),
                        vx = ((Math.random() - 0.5) * 1.2).toFloat(),
                        vy = ((Math.random() - 0.5) * 1.2).toFloat()
                    )
                )
            }
        }
    }

    /** بيستدعى برّه (مثلاً من SplashActivity لما المستخدم يلمس الشاشة كلها) */
    fun onTouch(x: Float, y: Float) {
        touchX = x
        touchY = y
    }

    /** بيتصفّر تأثير اللمس (اختياري) */
    fun clearTouch() {
        touchX = null
        touchY = null
    }

    /** تحديث فيزياء الجسيمات — بيتنادى من حلقة الرندر الخارجية (60fps) */
    fun updatePhysics() {
        var maxForce = 0f

        particles.forEach { p ->
            touchX?.let { tx ->
                touchY?.let { ty ->
                    val dx = tx - p.x
                    val dy = ty - p.y
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist < touchRadius) {
                        val force = (touchRadius - dist) / touchRadius
                        if (force > maxForce) maxForce = force
                        val angle = atan2(dy, dx)
                        p.vx -= cos(angle) * force * 0.8f
                        p.vy -= sin(angle) * force * 0.8f
                    }
                }
            }

            p.x += p.vx
            p.y += p.vy
            p.vx *= 0.98f
            p.vy *= 0.98f

            if (p.x < 0) p.x = width.toFloat()
            if (p.x > width) p.x = 0f
            if (p.y < 0) p.y = height.toFloat()
            if (p.y > height) p.y = 0f
        }

        touchForce = maxForce
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(backgroundColorValue)

        // رسم الخطوط
        for (i in particles.indices) {
            for (j in i + 1 until particles.size) {
                val p1 = particles[i]
                val p2 = particles[j]
                val dx = p1.x - p2.x
                val dy = p1.y - p2.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < maxDistance) {
                    linePaint.alpha = ((1 - dist / maxDistance) * 220).toInt()
                    canvas.drawLine(p1.x, p1.y, p2.x, p2.y, linePaint)
                }
            }
        }

        // رسم النقط
        particles.forEach { p ->
            canvas.drawCircle(p.x, p.y, 2.6f, pointPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                touchY = event.y
            }
            MotionEvent.ACTION_UP -> {
                touchX = null
                touchY = null
            }
        }
        return true
    }

    data class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float)
}
