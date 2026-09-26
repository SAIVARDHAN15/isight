package com.example.isight.vision

import android.media.Image
import android.util.Log
import java.util.concurrent.ExecutorService

private const val TAG = "DetectionPipeline"

/** One completed detection cycle's results, handed back on the worker thread. */
data class DetectionResult(
    val detections: List<Detection>,
    val preprocessResult: PreprocessResult,
    val debugBitmap: android.graphics.Bitmap
)

/**
 * Owns the "camera image -> preprocess -> TFLite inference -> decode" work
 * off the GL/UI thread, with a busy-flag so frames are DROPPED (not queued)
 * while a previous frame is still being processed — per this project's
 * explicit rule to never queue unlimited frames.
 *
 * [submitFrame] takes ownership of the [Image] passed to it: it is always
 * closed (whether the frame is processed or dropped), so the caller must
 * not touch it afterward.
 */
class DetectionPipeline(
    private val executor: ExecutorService,
    private val detector: YoloDetector
) {
    @Volatile
    private var busy = false

    /** Invoked on the worker thread — hop to the UI thread yourself. */
    var onResult: ((DetectionResult) -> Unit)? = null

    fun submitFrame(image: Image): Boolean {
        if (busy) {
            image.close()
            return false
        }
        busy = true

        executor.execute {
            try {
                val preprocessResult = ImagePreprocessor.preprocess(image)
                val raw = detector.detect(preprocessResult.inputBuffer)
                val detections = DetectionDecoder.decode(raw, preprocessResult, detector.labels)
                val debugBitmap = ImagePreprocessor.toDebugBitmap(preprocessResult)

                onResult?.invoke(DetectionResult(detections, preprocessResult, debugBitmap))
            } catch (e: Exception) {
                Log.e(TAG, "Detection pipeline failed for this frame", e)
            } finally {
                image.close()
                busy = false
            }
        }
        return true
    }
}
