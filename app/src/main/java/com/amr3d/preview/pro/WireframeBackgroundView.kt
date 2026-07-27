package com.amr3d.preview.pro

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class WireframeBackgroundView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private data class Node(var x: Float, var y: Float, val vx: Float, val vy: Float)

    private val nodes = mutableListOf<Node>()

    // overall alpha (0f..0.35f)
    private var alpha = 0f

    private val handler = Handler(Looper.getMainLooper())
    private var running = true
    private var fadeInRunnable: Runnable? = null

    // paints
    private val linePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
    }

    private val nodePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val glowPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            update()
            invalidate()
            handler.postDelayed(this, 16) // ~60fps for smooth subtle animation
        }
    }

    init {
        // Use software layer for BlurMaskFilter glow on some devices
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        // initialize node paints; stroke sizes will be set in onSizeChanged (dp-aware)
        val baseColor = Color.rgb(255, 138, 30) // orange
        linePaint.color = baseColor
        nodePaint.color = baseColor
        glowPaint.color = baseColor

        // create normalized nodes (0..1)
        for (i in 0 until 30) {
            nodes.add(Node(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                vx = (Random.nextFloat() - 0.5f) * 0.0035f,
                vy = (Random.nextFloat() - 0.5f) * 0.0035f
            ))
        }
        handler.post(ticker)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        handler.removeCallbacks(ticker)
        fadeInRunnable?.let { handler.removeCallbacks(it) }
    }

    fun fadeIn() {
        val start = System.currentTimeMillis()
        val dur = 1500L
        fadeInRunnable = object : Runnable {
            override fun run() {
                val t = ((System.currentTimeMillis() - start).toFloat() / dur).coerceIn(0f, 1f)
                alpha = t * 0.35f // max overall opacity ~35%
                invalidate()
                if (t < 1f) handler.postDelayed(this, 16)
            }
        }
        handler.post(fadeInRunnable!!)
    }

    private fun update() {
        // keep positions normalized (0..1)
        for (n in nodes) {
            n.x += n.vx
            n.y += n.vy

            // Wrap around the edges
            if (n.x < 0f) n.x += 1f
            if (n.x > 1f) n.x -= 1f
            if (n.y < 0f) n.y += 1f
            if (n.y > 1f) n.y -= 1f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density

        // thin lines and small nodes (density-aware)
        linePaint.strokeWidth = 1f * density
        // glow radius and blur
        val glowRadiusDp = 8f * density
        glowPaint.maskFilter = BlurMaskFilter(glowRadiusDp, BlurMaskFilter.Blur.NORMAL)

        // node radius in pixels
        nodeRadiusPx = 3f * density
    }

    private var nodeRadiusPx = 3f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat().takeIf { it > 0 } ?: return
        val h = height.toFloat().takeIf { it > 0 } ?: return

        if (alpha <= 0f) return

        // subtle dark overlay for dark theme feeling; multiplied by alpha so it fades in
        val overlayAlpha = (alpha * 80).toInt().coerceIn(0, 255)
        if (overlayAlpha > 0) {
            canvas.drawColor(Color.argb(overlayAlpha, 0, 0, 0))
        }

        val threshold = 0.25f // normalized distance threshold for connections

        // Draw connecting lines (consider wrap-around shortest distance)
        for (i in nodes.indices) {
            val n1 = nodes[i]
            val x1 = n1.x * w
            val y1 = n1.y * h
            for (j in i + 1 until nodes.size) {
                val n2 = nodes[j]

                // compute normalized shortest vector considering wrap-around (toroidal)
                var dx = n1.x - n2.x
                var dy = n1.y - n2.y

                if (dx > 0.5f) dx -= 1f
                else if (dx < -0.5f) dx += 1f

                if (dy > 0.5f) dy -= 1f
                else if (dy < -0.5f) dy += 1f

                val dist = sqrt(dx * dx + dy * dy)

                if (dist < threshold) {
                    // compute screen coords for second point using the adjusted dx/dy
                    val x2 = x1 - dx * w
                    val y2 = y1 - dy * h

                    // alpha per line: stronger when closer, scaled by overall alpha
                    val lineAlphaFactor = ((1f - dist / threshold) * alpha).coerceIn(0f, 1f)
                    val lineAlpha = (lineAlphaFactor * 255).toInt().coerceIn(0, 255)

                    linePaint.color = Color.argb(lineAlpha, 255, 138, 30)
                    linePaint.style = Paint.Style.STROKE
                    canvas.drawLine(x1, y1, x2, y2, linePaint)
                }
            }
        }

        // Draw node glow then node
        for (n in nodes) {
            val px = n.x * w
            val py = n.y * h

            // glow: bigger radius and lower alpha
            val glowAlpha = (alpha * 180).toInt().coerceIn(0, 255) // glow slightly stronger than fill
            glowPaint.color = Color.argb(glowAlpha, 255, 138, 30)
            val glowRadius = nodeRadiusPx * 3.5f
            canvas.drawCircle(px, py, glowRadius, glowPaint)

            // core node
            val nodeAlpha = (alpha * 220).toInt().coerceIn(0, 255)
            nodePaint.color = Color.argb(nodeAlpha, 255, 138, 30)
            canvas.drawCircle(px, py, nodeRadiusPx, nodePaint)
        }
    }
}
