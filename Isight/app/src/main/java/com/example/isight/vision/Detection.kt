package com.example.isight.vision

/**
 * A single decoded, labeled detection in UPRIGHT camera-image pixel space
 * (i.e. [ImagePreprocessor]'s `imageWidth`/`imageHeight` coordinate system —
 * already un-letterboxed, NOT model-640-space, NOT screen/view space).
 */
data class Detection(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}
