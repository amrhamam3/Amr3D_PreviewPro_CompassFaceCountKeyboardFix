package com.amr3d.preview.pro

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * طبقة شفافة فوق شاشة العرض — بترسم دايرة صغيرة بتتلاشى تدريجيًا في مكان الضغطة
 * المطوّلة، عشان تأكد للمستخدم إن نقطة الدوران (pivot) اتغيّرت فعلاً ومكانها فين
 * بالظبط. المدة الكلية للتأثير حوالي ثانية واحدة.
 */
class PivotFeedbackView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var px = 0f
    private var py = 0f
    private var progress = 0f // 0..1
    private var animator: ValueAnimator? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#FF8A1E")
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF8A1E")
    }

    init {
        // اللمسات لازم تعدي لعنصر الـ GL تحتها زي ما بالظبط بيحصل مع particleField
        isClickable = false
        isFocusable = false
    }

    /** يبدأ تأثير الدايرة عند نقطة (x, y) بمقاييس الشاشة */
    fun pulseAt(x: Float, y: Float) {
        px = x
        py = y
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0f || progress >= 1f) return

        val maxRadius = 46f
        val radius = 14f + maxRadius * progress
        val alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)

        ringPaint.alpha = alpha
        canvas.drawCircle(px, py, radius, ringPaint)

        // نقطة صغيرة ثابتة في المركز تفضل واضحة أكتر شوية من الدايرة المتوسّعة
        dotPaint.alpha = (alpha * 0.9f).toInt().coerceIn(0, 255)
        canvas.drawCircle(px, py, 5f, dotPaint)
    }
}
