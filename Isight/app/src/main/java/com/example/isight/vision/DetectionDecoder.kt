package com.example.isight.vision

import kotlin.math.max
import kotlin.math.min

/**
 * Decodes [RawDetection]s (model-640-space, unlabeled) into final
 * [Detection]s in upright-image pixel space, with labels resolved and
 * duplicate overlapping boxes for the same object suppressed via NMS.
 *
 * BOX ENCODING ASSUMPTION (documented, not confirmed on-device — this
 * model's export wasn't inspectable ahead of time): the 4 raw box values
 * are assumed to be (centerX, centerY, width, height) in 640x640
 * model-input pixel space — Ultralytics' standard TFLite export layout.
 * Once boxes are visible on screen (Phase 11's overlay), if they look
 * systematically wrong — e.g. offset by roughly half their own size, or
 * the wrong scale entirely — the fix is switching [BoxFormat] below to
 * XYXY (corner-encoded) instead of CXCYWH.
 */
object DetectionDecoder {

    private enum class BoxFormat { CXCYWH, XYXY }
    private val boxFormat = BoxFormat.CXCYWH

    private const val NMS_IOU_THRESHOLD = 0.45f

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

    fun decode(
        rawDetections: List<RawDetection>,
        preprocessResult: PreprocessResult,
        labels: List<String>
    ): List<Detection> {
        val decoded = rawDetections.map { raw -> toDetection(raw, preprocessResult, labels) }
        return nonMaxSuppression(decoded)
    }

    private fun toDetection(raw: RawDetection, result: PreprocessResult, labels: List<String>): Detection {
        val modelBox = when (boxFormat) {
            BoxFormat.CXCYWH -> {
                val halfW = raw.rawBox2 / 2f
                val halfH = raw.rawBox3 / 2f
                Quad(raw.rawBox0 - halfW, raw.rawBox1 - halfH, raw.rawBox0 + halfW, raw.rawBox1 + halfH)
            }
            BoxFormat.XYXY -> Quad(raw.rawBox0, raw.rawBox1, raw.rawBox2, raw.rawBox3)
        }

        val (left, top) = result.mapModelPointToImage(modelBox.a, modelBox.b)
        val (right, bottom) = result.mapModelPointToImage(modelBox.c, modelBox.d)

        val label = labels.getOrElse(raw.classId) { "unknown(${raw.classId})" }

        return Detection(
            classId = raw.classId,
            label = label,
            confidence = raw.score,
            left = min(left, right).coerceIn(0f, result.imageWidth.toFloat()),
            top = min(top, bottom).coerceIn(0f, result.imageHeight.toFloat()),
            right = max(left, right).coerceIn(0f, result.imageWidth.toFloat()),
            bottom = max(top, bottom).coerceIn(0f, result.imageHeight.toFloat())
        )
    }

    /** Greedy per-class NMS: highest confidence first, drop lower-confidence boxes that overlap it too much. */
    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        val kept = mutableListOf<Detection>()

        for ((_, group) in detections.groupBy { it.classId }) {
            val remaining = group.sortedByDescending { it.confidence }.toMutableList()
            while (remaining.isNotEmpty()) {
                val best = remaining.removeAt(0)
                kept.add(best)
                remaining.removeAll { iou(best, it) > NMS_IOU_THRESHOLD }
            }
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - interArea
        return if (union <= 0f) 0f else interArea / union
    }
}
