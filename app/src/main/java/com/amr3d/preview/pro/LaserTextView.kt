package com.amr3d.preview.pro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * نص بيتحرق بشعاع ليزر بيرسم (يحفر) شكل كل حرف بنفسه — حرف حرف — بدل ما يظهر
 * الحرف فجأة. الشعاع بيتبع تمامًا خطوط رسم الحرف (outline) باستخدام PathMeasure،
 * فبيبان وكأنه بيحفر الحرف فعليًا زي ماكينة ليزر حقيقية، مش مجرد Fade-in.
 * بتتغذى بـ update() من حلقة الرندر الخارجية في SplashActivity زي باقي عناصر
 * الـ Splash (WireframeSplashView / RingView) عشان تفضل كل الأنيميشن متزامنة.
 */
class LaserTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var text: String = "AMR3D PREVIEW"
        set(value) {
            field = value
            charsBuilt = false
            invalidate()
        }

    /** لون الثيم الحالي — بيتلوّن بيه الحرف بعد ما يتحرق، وشعاع الليزر، والشرر */
    var accentColor: Int = 0xFFFF8A1E.toInt()
        set(value) {
            field = value
            burnedPaint.color = value
            traceStrokePaint.color = value
            sparkPaint.color = value
        }

    /** true لما كل الحروف خلصت تتحرق */
    var isBurnComplete: Boolean = false
        private set

    /** حرف واحد بكل بياناته: مكانه، شكل حفره (path)، وطول كل contour فيه (بعض
     * الحروف زي D/R/A/P عندها أكتر من contour — الإطار الخارجي + الفتحة الداخلية) */
    private class Ch(val c: Char, val x: Float) {
        var burned = false
        var glow = 0f
        val path = Path()
        var contourLengths: List<Float> = emptyList()
        var totalLength = 0f
        var traceLen = 0f
    }

    private class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, var alpha: Float = 1f)

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        textSize = context.resources.displayMetrics.scaledDensity * 26f
        textAlign = Paint.Align.LEFT
    }
    private val unburnedPaint = Paint(basePaint).apply { color = 0xFF0A0F1A.toInt() }
    private val burnedPaint = Paint(basePaint).apply {
        color = accentColor
        setShadowLayer(28f, 0f, 0f, accentColor)
    }
    /** الخط اللي بيترسم تدريجيًا فوق شكل الحرف وهو بيتحفر */
    private val traceStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = accentColor
        setShadowLayer(18f, 0f, 0f, accentColor)
    }
    private val laserDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }

    private var chars = listOf<Ch>()
    private var charsBuilt = false
    private var currentIndex = 0
    private var laserX = 0f
    private var laserY = 0f
    private var textBaselineY = 0f
    private val sparks = mutableListOf<Spark>()
    private val traceScratch = Path()

    /** سرعة الحفر — بكسل من طول الخط في الفريم الواحد، متناسبة مع كثافة الشاشة */
    private val traceSpeedPerFrame = 9f * context.resources.displayMetrics.density

    private fun buildChars() {
        if (width <= 0 || height <= 0) return
        val cx = width / 2f
        val total = basePaint.measureText(text)
        val startX = cx - total / 2f
        textBaselineY = height * 0.62f

        val list = ArrayList<Ch>(text.length)
        for (i in text.indices) {
            val xBefore = basePaint.measureText(text, 0, i)
            val ch = Ch(text[i], startX + xBefore)
            if (!text[i].isWhitespace()) {
                basePaint.getTextPath(text[i].toString(), 0, 1, ch.x, textBaselineY, ch.path)
                val lens = mutableListOf<Float>()
                val measure = PathMeasure(ch.path, false)
                do { lens.add(measure.length) } while (measure.nextContour())
                ch.contourLengths = lens
                ch.totalLength = lens.sum()
            }
            list.add(ch)
        }
        chars = list
        charsBuilt = true
        currentIndex = 0
        laserX = startX
        laserY = textBaselineY
        isBurnComplete = false
        sparks.clear()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        charsBuilt = false
    }

    /** بيتنادى مرة كل فريم من حلقة الرندر الخارجية (زي wireframeView.updatePhysics()) */
    fun update() {
        if (!charsBuilt) {
            buildChars()
            if (!charsBuilt) return
        }
        if (currentIndex < chars.size) {
            val target = chars[currentIndex]
            if (target.totalLength <= 0f) {
                // مسافة (space) أو حرف من غير شكل مرئي — يتخطّى فورًا من غير حفر
                target.burned = true
                target.glow = 1f
                currentIndex++
            } else {
                target.traceLen += traceSpeedPerFrame
                val headPos = headPosition(target.path, target.contourLengths, target.traceLen.coerceAtMost(target.totalLength))
                laserX = headPos[0]
                laserY = headPos[1]

                if (target.traceLen >= target.totalLength) {
                    target.traceLen = target.totalLength
                    target.burned = true
                    target.glow = 1f
                    repeat(8) { spawnSpark(laserX, laserY) }
                    currentIndex++
                } else if (Random.nextInt(3) == 0) {
                    spawnSpark(laserX, laserY) // شرر خفيف أثناء الحفر نفسه، مش بس في الآخر
                }
            }
        } else if (!isBurnComplete) {
            laserY += (-150f - laserY) * 0.12f
            if (laserY < -140f) isBurnComplete = true
        }

        for (c in chars) if (c.glow > 0f) c.glow = (c.glow - 0.025f).coerceAtLeast(0f)

        val it = sparks.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.x += s.vx
            s.y += s.vy
            s.vy += 0.25f
            s.alpha -= 0.035f
            if (s.alpha <= 0f) it.remove()
        }
    }

    private fun spawnSpark(x: Float, y: Float) {
        val angle = Random.nextDouble(0.0, Math.PI * 2).toFloat()
        val speed = 2f + Random.nextFloat() * 5f
        sparks.add(Spark(x, y, cos(angle) * speed, sin(angle) * speed - 1f))
    }

    /** بيرجّع نقطة رأس الليزر (x,y) على شكل الحرف نفسه عند طول معيّن من بداية الحفر */
    private fun headPosition(path: Path, contourLengths: List<Float>, targetLen: Float): FloatArray {
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        if (contourLengths.isEmpty()) return pos
        val measure = PathMeasure(path, false)
        var cumulative = 0f
        for ((idx, len) in contourLengths.withIndex()) {
            val isLast = idx == contourLengths.lastIndex
            if (targetLen <= cumulative + len || isLast) {
                val localTarget = (targetLen - cumulative).coerceIn(0f, len)
                measure.getPosTan(localTarget, pos, tan)
                return pos
            }
            cumulative += len
            if (!measure.nextContour()) break
        }
        return pos
    }

    /** بيبني شكل جزء الحرف اللي اتحفر لحد دلوقتي (من أول الحرف لحد طول معيّن) */
    private fun tracePathUpTo(path: Path, contourLengths: List<Float>, targetLen: Float, outPath: Path) {
        outPath.reset()
        if (contourLengths.isEmpty() || targetLen <= 0f) return
        val measure = PathMeasure(path, false)
        var cumulative = 0f
        val seg = Path()
        for (len in contourLengths) {
            if (targetLen <= cumulative) break
            val localTarget = (targetLen - cumulative).coerceAtMost(len)
            if (localTarget > 0f) {
                seg.reset()
                measure.getSegment(0f, localTarget, seg, true)
                outPath.addPath(seg)
            }
            cumulative += len
            if (!measure.nextContour()) break
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!charsBuilt) {
            buildChars()
            if (!charsBuilt) return
        }

        for ((idx, c) in chars.withIndex()) {
            if (c.burned) {
                burnedPaint.setShadowLayer(28f + c.glow * 20f, 0f, 0f, accentColor)
                canvas.drawText(c.c.toString(), c.x, textBaselineY, burnedPaint)
            } else {
                // شكل الحرف "الخام" لسه ملموش الليزر — بيبان غامق زي المادة قبل الحفر
                canvas.drawText(c.c.toString(), c.x, textBaselineY, unburnedPaint)
                if (idx == currentIndex && c.totalLength > 0f) {
                    tracePathUpTo(c.path, c.contourLengths, c.traceLen, traceScratch)
                    canvas.drawPath(traceScratch, traceStrokePaint)
                }
            }
        }

        for (s in sparks) {
            sparkPaint.alpha = (s.alpha.coerceIn(0f, 1f) * 255).toInt()
            canvas.drawRect(s.x, s.y, s.x + 5f, s.y + 5f, sparkPaint)
        }

        if (!isBurnComplete) {
            canvas.drawCircle(laserX, laserY, 6f, laserDotPaint.apply {
                setShadowLayer(20f, 0f, 0f, accentColor)
            })
        }
    }
}
