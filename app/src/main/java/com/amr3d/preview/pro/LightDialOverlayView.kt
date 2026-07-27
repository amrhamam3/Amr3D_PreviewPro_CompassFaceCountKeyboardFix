package com.amr3d.preview.pro

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * إعادة تصميم كاملة لأداة الإضاءة — البند 2 من دفعة التحسينات.
 *
 * الفرق عن القديمة (SemiCircleLightView، اتشالت):
 * 1) دائرة كاملة 360° (كانت نص دائرة 180° بس).
 * 2) Overlay شفاف يتعرض حوالين الموديل مباشرة على شاشة العارض نفسها (فوق
 *    GLViewerView جوه viewerContainer)، مش Dialog/لوحة منفصلة بخلفية صلبة —
 *    المستخدم يفضل شايف الموديل بالكامل وهو بيغيّر اتجاه الضوء.
 * 3) التفاعل "عجلة دوارة" فعلية حوالين محيط الدائرة: لازم تمسك قريب من مسار
 *    الحلقة نفسها عشان تبدأ تسحب (مش أي نقطة في الشاشة كلها زي القديمة)،
 *    وبعدين الزاوية بتتغيّر بمقدار حركة إصبعك الزاويّة الفعلية حوالين المركز
 *    (Delta rotation) — زي ما تلف عجلة حقيقية، مش "قفزة" مباشرة لمكان لمسك.
 *
 * ⚠️ تجميد حركة الموديل (دوران/زووم/Pan) طول ما الأداة دي مفعّلة بيتم من
 * ViewerFragment عن طريق GLViewerView.lightModeActive — مش من الكلاس ده.
 */
class LightDialOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onAngleChanged: ((Float) -> Unit)? = null

    /** الزاوية بالدرجات 0..360 (بتتلف تلقائيًا لو خرجت برة النطاق) */
    var angleDeg: Float = 45f
        set(value) {
            field = ((value % 360f) + 360f) % 360f
            invalidate()
        }

    private val trackColor = Color.parseColor("#332D35")
    private val activeColor = Color.parseColor("#FF8A1E")
    private val tickColor = Color.parseColor("#5A5D65")
    private val tickLabelColor = Color.parseColor("#888888")

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val handleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = activeColor
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = tickColor
    }
    private val tickLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tickLabelColor
        textAlign = Paint.Align.CENTER
    }
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = activeColor
    }
    private val angleLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 0f, Color.parseColor("#CC000000"))
    }

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var trackWidth = 0f
    private var handleRadius = 0f
    /** هامش سماحية حوالين مسار الحلقة — لازم أول لمسة (ACTION_DOWN) تكون جواه
     * عشان نعتبرها بداية سحب للعجلة، وإلا بنعتبرها لمسة على الموديل ونتجاهلها. */
    private var bandTolerance = 0f

    private var isDragging = false
    private var lastTouchAngleDeg = 0f

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        cx = w / 2f
        cy = h / 2f
        val minSide = min(w, h).toFloat()
        radius = minSide / 2f * 0.88f
        trackWidth = minSide * 0.016f
        handleRadius = trackWidth * 1.7f
        bandTolerance = trackWidth * 4.5f

        trackPaint.strokeWidth = trackWidth
        handleRingPaint.strokeWidth = trackWidth * 0.5f
        sunPaint.strokeWidth = minSide * 0.006f
        tickLabelPaint.textSize = minSide * 0.032f
        angleLabelPaint.textSize = minSide * 0.038f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return

        // ══ مسار الحلقة الكامل (360°) ══
        trackPaint.color = trackColor
        canvas.drawCircle(cx, cy, radius, trackPaint)

        // ══ علامات الاتجاهات الأربعة (0° / 90° / 180° / 270°) ══
        drawTickMark(canvas, 0f)
        drawTickMark(canvas, 90f)
        drawTickMark(canvas, 180f)
        drawTickMark(canvas, 270f)

        // ══ موضع المقبض على محيط الحلقة ══
        val angleRad = Math.toRadians(angleDeg.toDouble())
        val hx = cx + radius * cos(angleRad).toFloat()
        val hy = cy + radius * sin(angleRad).toFloat()

        // توهج خفيف حوالين المقبض
        val glowR = handleRadius * 2.4f
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                hx, hy, glowR,
                intArrayOf(Color.parseColor("#99FF8A1E"), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(hx, hy, glowR, glowPaint)

        // خط رفيع من المقبض للمركز — بيوضّح اتجاه الضوء بصريًا نحو الموديل
        val dirLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#33FF8A1E")
            style = Paint.Style.STROKE
            strokeWidth = trackWidth * 0.35f
        }
        canvas.drawLine(hx, hy, cx, cy, dirLinePaint)

        // جسم المقبض
        handlePaint.color = activeColor
        canvas.drawCircle(hx, hy, handleRadius, handlePaint)
        canvas.drawCircle(hx, hy, handleRadius, handleRingPaint)
        drawSunIcon(canvas, hx, hy, handleRadius * 0.5f)

        // ══ قيمة الزاوية — شارة صغيرة جنب المقبض (مش في نص الشاشة عشان تفضل
        // شايف الموديل بالكامل) ══
        val labelR = radius + trackWidth * 3.2f
        val lx = cx + labelR * cos(angleRad).toFloat()
        val ly = cy + labelR * sin(angleRad).toFloat()
        canvas.drawText("${angleDeg.roundToInt()}°", lx, ly + angleLabelPaint.textSize / 3f, angleLabelPaint)
    }

    private fun drawSunIcon(canvas: Canvas, hx: Float, hy: Float, r: Float) {
        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        canvas.drawCircle(hx, hy, r * 0.5f, centerPaint)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val x1 = hx + (r * 0.7f) * cos(a).toFloat()
            val y1 = hy + (r * 0.7f) * sin(a).toFloat()
            val x2 = hx + r * cos(a).toFloat()
            val y2 = hy + r * sin(a).toFloat()
            canvas.drawLine(x1, y1, x2, y2, sunPaint)
        }
    }

    private fun drawTickMark(canvas: Canvas, angle: Float) {
        val rad = Math.toRadians(angle.toDouble())
        val tx = cx + radius * cos(rad).toFloat()
        val ty = cy + radius * sin(rad).toFloat()
        canvas.drawCircle(tx, ty, trackWidth * 0.45f, tickPaint)
    }

    /** بيرجع الزاوية (0..360) من المركز لنقطة اللمس — نفس نظام إحداثيات الرسم
     * بالظبط (Y بينزل لتحت) عشان التفاعل يتماشى تمامًا مع الرسم. */
    private fun touchAngleDeg(x: Float, y: Float): Float {
        val a = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
        return ((a % 360f) + 360f) % 360f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dist = hypot((event.x - cx).toDouble(), (event.y - cy).toDouble()).toFloat()
                if (abs(dist - radius) <= bandTolerance) {
                    isDragging = true
                    lastTouchAngleDeg = touchAngleDeg(event.x, event.y)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                // حتى لو اللمسة برة نطاق الحلقة (يعني على الموديل نفسه)، بنستهلكها
                // برضو من غير ما نبدأ سحب — الأداة مفعّلة فبنمنع أي حركة تسرّب
                // للموديل تحتها (التجميد الكامل بيتم أساسًا من GLViewerView.lightModeActive،
                // ده مجرد طبقة حماية إضافية).
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val curAngleDeg = touchAngleDeg(event.x, event.y)
                    var delta = curAngleDeg - lastTouchAngleDeg
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    angleDeg += delta
                    lastTouchAngleDeg = curAngleDeg
                    onAngleChanged?.invoke(angleDeg)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }
}
