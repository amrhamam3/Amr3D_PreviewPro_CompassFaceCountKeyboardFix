package com.amr3d.preview.pro

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Touch gestures:
 * - One finger drag      -> rotate model
 * - Two finger pinch     -> zoom
 * - Two finger drag      -> pan
 * - Two finger twist     -> rotate (helps reach awkward orientations)
 * - Single tap           -> measurement point picking
 */
class GLViewerView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {

    val stlRenderer = STLRenderer()

    private var previousX = 0f
    private var previousY = 0f
    private var previousSpan = 0f
    private var previousAngle = 0f
    private var lastTouchCount = 0
    private var moved = false

    var onSingleTap: ((Float, Float) -> Unit)? = null

    /** لازم تتظبط من ViewerFragment: true لما وضع القياس مفعّل */
    var measurementModeActive = false

    /** لازم تتظبط من ViewerFragment: true لما أداة الإضاءة (الحلقة 360°) مفعّلة —
     * البند 2.5: تجميد كامل لدوران/زووم/Pan الموديل طول ما الأداة دي شغالة، عشان
     * تركيز المستخدم يفضل على تغيير اتجاه الضوء من غير ما يحرّك الموديل بالغلط. */
    var lightModeActive = false

    /** بتتنادى باستمرار أثناء سحب الإصبع بعد اختيار أول نقطة قياس — عشان المعاينة الحية */
    var onMeasureDrag: ((Float, Float) -> Unit)? = null
    /** بينادى لما اللمس اليدوي يوقف الدوران التلقائي، عشان الـ Fragment يزامن شكل الزرار */
    var onAutoRotateStopped: (() -> Unit)? = null
    /** بتتنادى بس لما تأكدنا إن اللمسة "ضغطة مطوّلة" فعلاً (مش تدوير ولا لمسة عادية) —
     * الـ Fragment بيستخدمها عشان يحدد مركز الدوران (pivot) الجديد من نقطة اللمس على
     * سطح الموديل، ويظهر دايرة تأكيد بصري في نفس المكان */
    var onLongPressPivot: ((Float, Float) -> Unit)? = null

    private val longPressRunnable = Runnable {
        longPressTriggered = true
        onLongPressPivot?.invoke(pendingPivotX, pendingPivotY)
    }
    private var pendingPivotX = 0f
    private var pendingPivotY = 0f
    private var longPressTriggered = false

    companion object {
        /** المدة اللي لازم الإصبع يفضل فيها ثابت عشان تتحسب "ضغطة مطوّلة" */
        private const val LONG_PRESS_TIMEOUT_MS = 500L
        /** أقصى مسافة حركة مسموح بيها من غير ما نلغي الضغطة المطوّلة (px) */
        private const val LONG_PRESS_CANCEL_SLOP = 20f
    }

    init {
        setEGLContextClientVersion(2)
        // بيخلي onPause()/onResume() يوقف/يشغّل خيط الرندر بس، من غير ما يدمّر الـ EGL
        // context والـ VBOs — مهم جدًا عشان لما نستخدمهم وقت التبديل لوضع DXF (شوف
        // switchTo2DMode/switchTo3DMode في ViewerFragment)، الموديل يفضل موجود جاهز
        // للعرض فورًا لو المستخدم رجع لوضع الـ 3D من غير ما يعيد تحميل الملف.
        preserveEGLContextOnPause = true
        setRenderer(stlRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /** true لما يكون فيه نقطة قياس واحدة مثبّتة بس، ومستنيين تحديد التانية */
    private fun isAwaitingSecondMeasurePoint() =
        measurementModeActive && stlRenderer.getMeasurementPoints().size == 1

    /** بيقلل حساسية الدوران (درجات لكل بيكسل سحب) تناسبيًا عكسيًا مع مستوى
     * الزووم الحالي — كل ما تكبّر أكتر، الدوران يبقى أدق/أبطأ بدل حساسية ثابتة
     * دايمًا (البند 3.2). عند scaleFactor=1 (بدون تكبير) الحساسية زي ما كانت
     * بالظبط. حد أدنى 0.15 عشان يفضل فيه تحكم عملي حتى في أقصى تكبير (12x). */
    private fun rotationSensitivityFactor(): Float = (1f / stlRenderer.scaleFactor).coerceIn(0.15f, 1f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // تجميد كامل طول ما أداة الإضاءة مفعّلة — نستهلك اللمسة من غير أي تأثير
        // على الدوران/الزووم/الـPan (البند 2.5). أداة الإضاءة نفسها (LightDialOverlayView)
        // بتاخد اللمس فوق الـ view ده أصلاً، ده مجرد حماية إضافية.
        if (lightModeActive) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousX = event.x
                previousY = event.y
                moved = false
                lastTouchCount = 1
                stlRenderer.showPivotIndicator = false
                stlRenderer.isUserInteracting = true
                if (stlRenderer.autoRotate) {
                    stlRenderer.autoRotate = false
                    onAutoRotateStopped?.invoke()
                }
                if (!measurementModeActive) {
                    pendingPivotX = event.x
                    pendingPivotY = event.y
                    longPressTriggered = false
                    removeCallbacks(longPressRunnable)
                    postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPressRunnable)
                lastTouchCount = event.pointerCount
                previousX = averageX(event)
                previousY = averageY(event)
                previousSpan = currentSpan(event)
                previousAngle = currentAngle(event)
            }

            MotionEvent.ACTION_MOVE -> {
                val curX = averageX(event)
                val curY = averageY(event)
                val dx = curX - previousX
                val dy = curY - previousY

                if (abs(dx) > 1f || abs(dy) > 1f) moved = true

                if (event.pointerCount == 1) {
                    val distFromDown = hypot(event.x - pendingPivotX, event.y - pendingPivotY)
                    if (distFromDown > LONG_PRESS_CANCEL_SLOP) removeCallbacks(longPressRunnable)
                }

                if (event.pointerCount == 1 && isAwaitingSecondMeasurePoint()) {
                    // في وضع القياس وبعد اختيار أول نقطة: الإصبع بيحرّك نقطة القياس التانية
                    // مش بيدوّر الموديل — عشان المستخدم يشوف المسافة بتتغيّر لحظياً وهو بيسحب
                    onMeasureDrag?.invoke(event.x, event.y)
                    previousX = curX
                    previousY = curY
                    return true
                }

                if (event.pointerCount >= 2) {
                    stlRenderer.showPivotIndicator = false
                    // Zoom via pinch — بيفضل شغال حتى أثناء وضع القياس، لأنه فعليًا بيساعد
                    // على الدقة (تكبير المنطقة اللي المستخدم عايز يحدد نقطة فيها بيسهّل
                    // اللمس الدقيق للتفاصيل الصغيرة، عكس الدوران اللي بس بيلخبط الاتجاه).
                    // بيتم حوالين نقطة تلاقي الإصبعين نفسها (مش مركز الموديل الثابت) —
                    // البند 3.1، شوف الشرح الكامل في STLRenderer.applyPinchZoom.
                    val curSpan = currentSpan(event)
                    if (previousSpan > 10f && curSpan > 10f) {
                        val spanRatio = curSpan / previousSpan
                        stlRenderer.applyPinchZoom(curX, curY, spanRatio)
                    }
                    previousSpan = curSpan

                    if (!measurementModeActive) {
                        // Detect if it's mostly a pan or a twist — الدوران (حتى بإصبعين) بيتقفل
                        // بالكامل أثناء وضع القياس عشان مايغيّرش زاوية العرض وهو المستخدم
                        // بيحاول يحدد نقطة بدقة (ده اللي كان بيصعّب القياس فعليًا)
                        val curAngle = currentAngle(event)
                        val angleDelta = curAngle - previousAngle
                        val normAngle = when {
                            angleDelta > 180f -> angleDelta - 360f
                            angleDelta < -180f -> angleDelta + 360f
                            else -> angleDelta
                        }
                        if (abs(normAngle) > 0.3f) {
                            stlRenderer.rotationY += normAngle * 1.5f * rotationSensitivityFactor()
                        }
                        previousAngle = curAngle
                    }

                    // Two-finger pan — يفضل شغال دايمًا (مفيد أثناء القياس كمان عشان تشوف
                    // زاوية تانية من غير ما تدوّر الموديل فعليًا). حساسيتها متوازنة أصلاً
                    // مع مستوى الزووم بشكل طبيعي (panScale في الرندرر مرتبط عكسيًا بـ
                    // scaleFactor)، فبتفضل بتتبع الإصبع بنفس المعدل البصري في أي مستوى
                    // تكبير من غير ما نحتاج نضيف تصحيح إضافي هنا.
                    stlRenderer.panX += dx * 0.003f
                    stlRenderer.panY -= dy * 0.003f

                } else if (measurementModeActive) {
                    // في وضع القياس وقبل ما تحدد أول نقطة: سحب إصبع واحد بيحرّك العرض
                    // (Pan) بس، من غير ما يدوّر الموديل — عشان مايحصلش تحريك غير مقصود
                    // يصعّب تحديد النقط (اقتراح المستخدم: الحركة تقتصر على الـ Pan فقط)
                    val panScaleTouch = 0.003f
                    stlRenderer.panX += dx * panScaleTouch
                    stlRenderer.panY -= dy * panScaleTouch
                } else {
                    // One finger rotate — حساسيتها بتقل مع الزووم العالي (البند 3.2) عشان
                    // تحكّم أدق وقت العمل على تفاصيل صغيرة مكبّرة (زي وقت التحضير للقياس)
                    stlRenderer.showPivotIndicator = true
                    val rotFactor = rotationSensitivityFactor()
                    stlRenderer.rotationY += dx * 0.5f * rotFactor
                    stlRenderer.rotationX += dy * 0.5f * rotFactor
                    stlRenderer.rotationX = stlRenderer.rotationX.coerceIn(-90f, 90f)
                }

                previousX = curX
                previousY = curY
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // ⚠️ بج "الموديل بيفلت لما تشيل صباعك بعد الزووم" — السبب كان إن
                // event.x/event.y هنا بيرجعوا إحداثيات الإصبع رقم 0 في القايمة
                // (أول إصبع لمس الشاشة أصلاً)، مش بالضرورة نفس متوسط الإصبعين اللي
                // كان بيتحسب وقت الزووم (averageX/averageY)، ومش بالضرورة حتى نفس
                // الإصبع الباقي بعد الرفع! فكانت previousX/Y بتتسجّل بقيمة غلط
                // تمامًا، وأول حركة (أو حتى نفس اللحظة) بعد كده بتحسب "قفزة" ضخمة
                // كدلتا = المسافة بين القيمة الغلط دي ومتوسط الإصبع (الأصابع)
                // الباقية فعليًا — فيظهر إن الموديل "طار" فجأة. الحل: نحسب متوسط
                // كل الأصابع الباقية (من غير الإصبع اللي بيتشال بالظبط)، عشان يطابق
                // بالظبط اللي averageX/averageY هيحسبوه في أي حدث جاي بعد كده.
                val liftedIndex = event.actionIndex
                var sumX = 0f; var sumY = 0f; var remaining = 0
                for (i in 0 until event.pointerCount) {
                    if (i == liftedIndex) continue
                    sumX += event.getX(i); sumY += event.getY(i); remaining++
                }
                lastTouchCount = remaining.coerceAtLeast(1)
                if (remaining > 0) {
                    previousX = sumX / remaining
                    previousY = sumY / remaining
                }
                stlRenderer.showPivotIndicator = false
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                stlRenderer.showPivotIndicator = false
                stlRenderer.isUserInteracting = false
                if (lastTouchCount == 1 && (!moved || isAwaitingSecondMeasurePoint())) {
                    // في وضع "منتظرين ثاني نقطة قياس" بنثبّت مكان آخر لمسة حتى لو الإصبع اتحرك
                    // (السحب هنا مقصود، مش تدوير بالغلط)
                    onSingleTap?.invoke(event.x, event.y)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                // لو النظام قاطع اللمسة (زي سحب إشعار) — نتأكد إن حركة "التنفس" مش هتفضل واقفة للأبد
                stlRenderer.showPivotIndicator = false
                stlRenderer.isUserInteracting = false
            }
        }
        return true
    }

    private fun currentSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return hypot(dx, dy)
    }

    private fun currentAngle(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    private fun averageX(event: MotionEvent): Float {
        var total = 0f
        for (i in 0 until event.pointerCount) total += event.getX(i)
        return total / event.pointerCount
    }

    private fun averageY(event: MotionEvent): Float {
        var total = 0f
        for (i in 0 until event.pointerCount) total += event.getY(i)
        return total / event.pointerCount
    }
}
