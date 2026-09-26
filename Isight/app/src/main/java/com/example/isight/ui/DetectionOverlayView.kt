package com.example.isight.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.isight.vision.Detection

/**
 * Draws bounding boxes over the live camera preview.
 *
 * ALIGNMENT ASSUMPTION: maps upright-image-space detection boxes to view
 * pixels using a simple CENTER-CROP fit (scale = max(viewSize/imageSize),
 * centered, excess cropped) — matching the same "fill the screen, crop the
 * excess" behavior ARCore's own background-quad renderer uses when the
 * camera's aspect ratio doesn't match the screen's. ARCore doesn't expose
 * its internal fit math, so this isn't guaranteed pixel-identical to it —
 * boxes may sit slightly off near cropped edges. Good enough to verify
 * detection correctness by eye; not marketed as pixel-perfect AR
 * registration.
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var detections: List<Detection> = emptyList()
    private var imageWidth = 0
    private var imageHeight = 0

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.YELLOW
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.YELLOW
        textSize = 36f
        isAntiAlias = true
    }
    private val textBackgroundPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
    }

    fun update(detections: List<Detection>, imageWidth: Int, imageHeight: Int) {
        this.detections = detections
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageWidth == 0 || imageHeight == 0 || detections.isEmpty()) return

        val scale = maxOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val offsetX = (width - imageWidth * scale) / 2f
        val offsetY = (height - imageHeight * scale) / 2f

        for (detection in detections) {
            val left = detection.left * scale + offsetX
            val top = detection.top * scale + offsetY
            val right = detection.right * scale + offsetX
            val bottom = detection.bottom * scale + offsetY

            canvas.drawRect(left, top, right, bottom, boxPaint)

            val label = "${detection.label} %.2f".format(detection.confidence)
            val textWidth = textPaint.measureText(label)
            canvas.drawRect(left, top - 40f, left + textWidth + 12f, top, textBackgroundPaint)
            canvas.drawText(label, left + 6f, top - 10f, textPaint)
        }
    }
}
