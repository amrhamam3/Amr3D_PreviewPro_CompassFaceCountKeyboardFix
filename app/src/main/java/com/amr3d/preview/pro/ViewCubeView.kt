package com.amr3d.preview.pro

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class ViewCubeView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Face(val label: String, val rotX: Float, val rotY: Float) {
        FRONT("FRONT", 0f, 0f),
        BACK("BACK", 0f, 180f),
        LEFT("LEFT", 0f, -90f),
        RIGHT("RIGHT", 0f, 90f),
        TOP("TOP", -90f, 0f),
        BOTTOM("BOTTOM", 90f, 0f)
    }

    var onFaceSelected: ((Face) -> Unit)? = null

    private val colorGray = Color.parseColor("#E8E8E8")
    private val colorBlue = Color.parseColor("#4A90E2")
    private val colorBlueStroke = Color.parseColor("#2E7BD6")
    private val colorText = Color.parseColor("#333333")
    private val colorTextSelected = Color.WHITE
    private val colorButtonBackground = Color.parseColor("#F0F0F0")
    private val colorButtonText = Color.parseColor("#555555")

    private companion object {
        const val CORNER_RADIUS = 6f
        const val BUTTON_HEIGHT = 50f
        const val BUTTON_SPACING = 6f
        const val BUTTON_TOP_PADDING = 15f
        const val BUTTON_TEXT_SIZE = 18f
        const val STROKE_WIDTH = 4f
        const val CUBE_HEIGHT_RATIO = 0.8f
        const val CUBE_SIZE_RATIO = 0.45f
        const val ANIMATION_DURATION = 400L
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
        color = colorBlueStroke
    }

    private var selectedFace = Face.FRONT
    private val buttonRects = mutableMapOf<Face, RectF>()

    private var rotateX = -25f
    private var rotateY = 45f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDraggingCube = false

    private val camera3D = Camera()
    private val matrix3D = Matrix()
    private val facePath = Path()

    private val vertices = arrayOf(
        floatArrayOf(-1f, -1f,  1f),
        floatArrayOf( 1f, -1f,  1f),
        floatArrayOf( 1f,  1f,  1f),
        floatArrayOf(-1f,  1f,  1f),
        floatArrayOf(-1f, -1f, -1f),
        floatArrayOf( 1f, -1f, -1f),
        floatArrayOf( 1f,  1f, -1f),
        floatArrayOf(-1f,  1f, -1f)
    )

    private val faceIndices = mapOf(
        Face.FRONT  to intArrayOf(0, 1, 2, 3),
        Face.BACK   to intArrayOf(5, 4, 7, 6),
        Face.LEFT   to intArrayOf(4, 0, 3, 7),
        Face.RIGHT  to intArrayOf(1, 5, 6, 2),
        Face.TOP    to intArrayOf(4, 5, 1, 0),
        Face.BOTTOM to intArrayOf(3, 2, 6, 7)
    )

    private var rotationAnimatorSet: AnimatorSet? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 10f || h < 10f) return

        val cubeHeight = h * CUBE_HEIGHT_RATIO
        val centerX = w / 2
        val centerY = cubeHeight / 2
        val size = min(w, cubeHeight) * CUBE_SIZE_RATIO

        draw3DCube(canvas, centerX, centerY, size)
        drawButtons(canvas, cubeHeight)
    }

    private fun draw3DCube(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val faceDepths = Face.values().map { face ->
            val normal = getFaceNormal(face, rotateX, rotateY)
            face to normal[2]
        }.sortedBy { it.second }

        faceDepths.forEach { (face, _) ->
            val indices = faceIndices[face] ?: return@forEach

            camera3D.save()
            camera3D.rotateX(rotateX)
            camera3D.rotateY(rotateY)
            camera3D.getMatrix(matrix3D)
            camera3D.restore()

            matrix3D.preTranslate(0f, 0f)
            matrix3D.postScale(size, size)
            matrix3D.postTranslate(cx, cy)

            val points2D = FloatArray(8)
            val srcPoints = FloatArray(8)
            for (i in 0..3) {
                val vertex = vertices[indices[i]]
                srcPoints[i * 2] = vertex[0]
                srcPoints[i * 2 + 1] = vertex[1]
            }
            matrix3D.mapPoints(points2D, srcPoints)

            facePath.reset()
            facePath.moveTo(points2D[0], points2D[1])
            facePath.lineTo(points2D[2], points2D[3])
            facePath.lineTo(points2D[4], points2D[5])
            facePath.lineTo(points2D[6], points2D[7])
            facePath.close()

            paint.color = if (selectedFace == face) colorBlue else colorGray
            canvas.drawPath(facePath, paint)

            if (selectedFace == face) {
                canvas.drawPath(facePath, strokePaint)
            }

            val textX = (points2D[0] + points2D[2] + points2D[4] + points2D[6]) / 4
            val textY = (points2D[1] + points2D[3] + points2D[5] + points2D[7]) / 4
            textPaint.color = if (selectedFace == face) colorTextSelected else colorText
            textPaint.textSize = size * 0.3f

            val fontMetrics = textPaint.fontMetrics
            val adjustedY = textY - (fontMetrics.ascent + fontMetrics.descent) / 2
            canvas.drawText(face.label, textX, adjustedY, textPaint)
        }
    }

    private fun getFaceNormal(face: Face, rotX: Float, rotY: Float): FloatArray {
        val radX = Math.toRadians(rotX.toDouble())
        val radY = Math.toRadians(rotY.toDouble())

        val nx = when(face) { Face.LEFT -> -1f; Face.RIGHT -> 1f; else -> 0f }
        val ny = when(face) { Face.TOP -> -1f; Face.BOTTOM -> 1f; else -> 0f }
        val nz = when(face) { Face.BACK -> -1f; Face.FRONT -> 1f; else -> 0f }

        val cosX = cos(radX).toFloat()
        val sinX = sin(radX).toFloat()
        val cosY = cos(radY).toFloat()
        val sinY = sin(radY).toFloat()

        val x1 = nx * cosY + nz * sinY
        val y1 = ny * cosX - (-nx * sinY + nz * cosY) * sinX
        val z1 = ny * sinX + (-nx * sinY + nz * cosY) * cosX

        return floatArrayOf(x1, y1, z1)
    }

    private fun drawButtons(canvas: Canvas, startY: Float) {
        val faces = Face.values()
        val btnWidth = width / 6.5f
        val spacing = BUTTON_SPACING
        val totalWidth = btnWidth * faces.size + spacing * (faces.size - 1)
        var startX = (width - totalWidth) / 2

        buttonRects.clear()
        faces.forEach { face ->
            val rect = buttonRects.getOrPut(face) { RectF() }
            rect.set(
                startX,
                startY + BUTTON_TOP_PADDING,
                startX + btnWidth,
                startY + BUTTON_TOP_PADDING + BUTTON_HEIGHT
            )

            paint.color = if (selectedFace == face) colorBlueStroke else colorButtonBackground
            canvas.drawRoundRect(rect, CORNER_RADIUS, CORNER_RADIUS, paint)

            textPaint.color = if (selectedFace == face) Color.WHITE else colorButtonText
            textPaint.textSize = BUTTON_TEXT_SIZE
            canvas.drawText(face.label, rect.centerX(), rect.centerY() + 6f, textPaint)

            startX += btnWidth + spacing
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y
                isDraggingCube = true

                buttonRects.forEach { (face, rect) ->
                    if (rect.contains(x, y)) {
                        isDraggingCube = false
                        selectFace(face)
                        return true
                    }
                }
                rotationAnimatorSet?.cancel()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingCube) {
                    val dx = x - lastTouchX
                    val dy = y - lastTouchY

                    rotateY += dx * 0.5f
                    rotateX -= dy * 0.5f
                    rotateX = rotateX.coerceIn(-89f, 89f)

                    lastTouchX = x
                    lastTouchY = y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isDraggingCube) {
                    isDraggingCube = false
                    performClick()
                }
            }
        }
        return true
    }

    private fun selectFace(face: Face) {
        selectedFace = face
        onFaceSelected?.invoke(face)
        animateCubeToFace(face)
    }

    private fun animateCubeToFace(face: Face) {
        rotationAnimatorSet?.cancel()

        val startX = rotateX
        val startY = rotateY

        val targetX = face.rotX
        var targetY = face.rotY

        val diffY = ((targetY - startY + 180) % 360 - 180)
        targetY = startY + diffY

        val animX = ValueAnimator.ofFloat(startX, targetX)
        animX.addUpdateListener {
            rotateX = it.animatedValue as Float
            invalidate()
        }

        val animY = ValueAnimator.ofFloat(startY, targetY)
        animY.addUpdateListener {
            rotateY = it.animatedValue as Float
            invalidate()
        }

        rotationAnimatorSet = AnimatorSet().apply {
            playTogether(animX, animY)
            duration = ANIMATION_DURATION
            interpolator = DecelerateInterpolator()
            start()
        }
    }
}
