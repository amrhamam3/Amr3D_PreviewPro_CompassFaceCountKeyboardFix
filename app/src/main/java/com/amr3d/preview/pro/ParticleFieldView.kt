package com.amr3d.preview.pro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

/**
 * جسيمات عائمة خفيفة جدًا بتتحرك لفوق ببطء في خلفية شاشة العارض — تأثير زينة بس
 * (زي particles في amr3d_enhanced_v3.html)، أخف بكتير من شبكة الـ Splash (مفيش خطوط
 * توصيل بين النقط، وشفافية أقل، وعدد نقط أقل).
 */
class ParticleFieldView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private class P(var x: Float, var y: Float, var speed: Float, var radius: Float, var alpha: Float)

    private val particles = mutableListOf<P>()
    private val particleCount = 22
    private var seeded = false

    var accentColor: Int = 0xFFFF8A1E.toInt()
        set(value) { field = value; paint.color = value }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }

    /** بيتحكم في ظهور الجسيمات (شفافية عامة) — بيتنادى من الفراجمنت لما الموديل يتحمّل
     * عشان تخف شوية بدل ما تختفي خالص، بنفس فكرة الخطوط في الخلفية */
    @Volatile var globalAlpha: Float = 1f

    private fun seed() {
        if (width <= 0 || height <= 0) return
        particles.clear()
        repeat(particleCount) {
            particles.add(
                P(
                    x = Random.nextFloat() * width,
                    y = Random.nextFloat() * height,
                    speed = 0.25f + Random.nextFloat() * 0.55f,
                    radius = 1.2f + Random.nextFloat() * 1.6f,
                    alpha = 0.15f + Random.nextFloat() * 0.35f
                )
            )
        }
        seeded = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        seeded = false
    }

    /** بينادى مرة كل فريم (من نفس حلقة الرندر بتاعة GLViewerView أو Handler خفيف) */
    fun update() {
        if (!seeded) { seed(); if (!seeded) return }
        for (p in particles) {
            p.y -= p.speed
            if (p.y < -10f) {
                p.y = height + 10f
                p.x = Random.nextFloat() * width
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!seeded) return
        for (p in particles) {
            paint.alpha = (p.alpha * globalAlpha * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(p.x, p.y, p.radius, paint)
        }
    }
}
