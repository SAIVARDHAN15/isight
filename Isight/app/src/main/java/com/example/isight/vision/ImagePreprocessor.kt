package com.example.isight.vision

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer

/**
 * Result of preprocessing one camera frame into the YOLO input tensor.
 *
 * Carries the letterbox transform (scale + padding) needed later to map a
 * detection box from 640x640 model-input space back to real camera-image
 * pixel space — see [mapModelPointToImage]. [imageWidth]/[imageHeight] are
 * the dimensions of the UPRIGHT (post-rotation) image, i.e. what the model
 * actually "saw" before letterboxing, since that's the space callers will
 * want box coordinates in.
 */
data class PreprocessResult(
    val inputBuffer: ByteBuffer,
    val imageWidth: Int,
    val imageHeight: Int,
    val sensorWidth: Int,
    val sensorHeight: Int,
    val scale: Float,
    val padX: Float,
    val padY: Float
) {
    /** Maps a point in [0, ImagePreprocessor.MODEL_INPUT_SIZE) model space to upright-image pixel space. */
    fun mapModelPointToImage(modelX: Float, modelY: Float): Pair<Float, Float> =
        (modelX - padX) / scale to (modelY - padY) / scale
}

/**
 * Converts a YUV_420_888 camera [Image] into the letterboxed, uint8 RGB
 * 640x640x3 input this project's YOLOv11 model expects (input dtype uint8,
 * quantization scale≈1/255, zero_point=0 — i.e. the model wants raw 0-255
 * pixel bytes, NOT additionally float-normalized on our end).
 *
 * Two things this deliberately does NOT assume, per the "everything detects
 * as person" bug this project needs to avoid repeating:
 *
 * 1. ORIENTATION. `Frame.acquireCameraImage()` returns the buffer in the
 *    camera sensor's native orientation, which for a phone's back camera is
 *    landscape — NOT auto-rotated the way ARCore's own GL background quad
 *    is. Left uncorrected, every frame fed to the model would be rotated
 *    90° from upright, which would measurably hurt detection accuracy.
 *    ASSUMPTION (documented, not silently guessed): the activity is locked
 *    to portrait (see AndroidManifest) and the device's back-camera sensor
 *    orientation is 90° — true for the very large majority of Android
 *    phones, including the iQOO 15, but not architecturally guaranteed by
 *    ARCore itself. If detections ever look systematically sideways, this
 *    fixed 90° rotation is the first thing to revisit.
 *
 * 2. ASPECT RATIO. The camera image is not square; naive stretch-resizing
 *    to 640x640 would squash objects and skew scale-dependent detection.
 *    This uses a proper letterbox resize (scale-to-fit + pad with
 *    Ultralytics' conventional mid-gray (114,114,114)), matching how
 *    YOLO models are normally trained/exported.
 *
 * RGB channel order is assumed (not BGR) — standard for Ultralytics/PyTorch
 * exports; first thing to flip if colors look systematically swapped.
 */
object ImagePreprocessor {

    const val MODEL_INPUT_SIZE = 640

    // See ORIENTATION note above.
    private const val ASSUMED_SENSOR_ROTATION_DEGREES = 90

    private const val PAD_VALUE: Byte = 114.toByte()

    fun preprocess(image: Image): PreprocessResult {
        require(ASSUMED_SENSOR_ROTATION_DEGREES == 90) {
            "Only the 90-degree rotation case is implemented; revisit this if that assumption changes."
        }

        // After rotating the raw (landscape) sensor buffer 90 degrees
        // clockwise, its width/height swap.
        val uprightWidth = image.height
        val uprightHeight = image.width

        val scale = minOf(
            MODEL_INPUT_SIZE.toFloat() / uprightWidth,
            MODEL_INPUT_SIZE.toFloat() / uprightHeight
        )
        val scaledWidth = (uprightWidth * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (uprightHeight * scale).toInt().coerceAtLeast(1)
        val padX = (MODEL_INPUT_SIZE - scaledWidth) / 2f
        val padY = (MODEL_INPUT_SIZE - scaledHeight) / 2f

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val outputBuffer = ByteBuffer.allocateDirect(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 3)

        for (outY in 0 until MODEL_INPUT_SIZE) {
            val uprightY = outY - padY
            val rowIsPadding = uprightY < 0f || uprightY >= scaledHeight
            val uprightYInt = if (rowIsPadding) -1 else (uprightY / scale).toInt().coerceIn(0, uprightHeight - 1)

            for (outX in 0 until MODEL_INPUT_SIZE) {
                if (rowIsPadding) {
                    outputBuffer.put(PAD_VALUE).put(PAD_VALUE).put(PAD_VALUE)
                    continue
                }

                val uprightX = outX - padX
                if (uprightX < 0f || uprightX >= scaledWidth) {
                    outputBuffer.put(PAD_VALUE).put(PAD_VALUE).put(PAD_VALUE)
                    continue
                }
                val uprightXInt = (uprightX / scale).toInt().coerceIn(0, uprightWidth - 1)

                val (sensorCol, sensorRow) = mapUprightToSensor(uprightXInt, uprightYInt, uprightWidth)

                val yIndex = sensorRow * yRowStride + sensorCol * yPixelStride
                val uvCol = sensorCol / 2
                val uvRow = sensorRow / 2
                val uIndex = uvRow * uRowStride + uvCol * uPixelStride
                val vIndex = uvRow * vRowStride + uvCol * vPixelStride

                val yValue = yBuffer.get(yIndex).toInt() and 0xFF
                val uValue = (uBuffer.get(uIndex).toInt() and 0xFF) - 128
                val vValue = (vBuffer.get(vIndex).toInt() and 0xFF) - 128

                val r = (yValue + 1.402f * vValue).coerceIn(0f, 255f)
                val g = (yValue - 0.344136f * uValue - 0.714136f * vValue).coerceIn(0f, 255f)
                val b = (yValue + 1.772f * uValue).coerceIn(0f, 255f)

                outputBuffer.put(r.toInt().toByte())
                outputBuffer.put(g.toInt().toByte())
                outputBuffer.put(b.toInt().toByte())
            }
        }

        outputBuffer.rewind()

        return PreprocessResult(
            inputBuffer = outputBuffer,
            imageWidth = uprightWidth,
            imageHeight = uprightHeight,
            sensorWidth = image.width,
            sensorHeight = image.height,
            scale = scale,
            padX = padX,
            padY = padY
        )
    }

    /**
     * Maps an upright-image pixel back to raw sensor-native pixel space
     * (col, row) — the inverse of the 90-degree-clockwise rotation applied
     * above. Shared with [com.example.isight.ar.DepthProcessor] so per-object
     * depth sampling (which reads the depth buffer in its own sensor-native
     * orientation) uses the exact same, once-verified transform rather than
     * a second hand-derived copy.
     *
     * Derived from the standard transpose+flip decomposition of a 90-degree
     * clockwise rotation, verified against a hand-worked 2x2 example.
     */
    fun mapUprightToSensor(uprightX: Int, uprightY: Int, uprightWidth: Int): Pair<Int, Int> {
        val sensorRow = uprightWidth - 1 - uprightX
        val sensorCol = uprightY
        return sensorCol to sensorRow
    }

    /**
     * Debug helper: renders exactly what the model received, for visual
     * verification (orientation / color channel order / letterbox bars).
     * Uses a duplicate of the buffer so the original's position (needed by
     * the interpreter later) is left untouched.
     */
    fun toDebugBitmap(result: PreprocessResult): Bitmap {
        val buffer = result.inputBuffer.duplicate()
        buffer.rewind()

        val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        for (i in pixels.indices) {
            val r = buffer.get().toInt() and 0xFF
            val g = buffer.get().toInt() and 0xFF
            val b = buffer.get().toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        return Bitmap.createBitmap(pixels, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, Bitmap.Config.ARGB_8888)
    }
}
