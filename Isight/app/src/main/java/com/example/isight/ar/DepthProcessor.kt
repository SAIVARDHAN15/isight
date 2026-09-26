package com.example.isight.ar

import com.example.isight.vision.Detection
import com.example.isight.vision.ImagePreprocessor
import com.example.isight.vision.PreprocessResult
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder

/**
 * Reads ARCore's per-pixel Depth API output for a single frame — both a
 * single center-of-frame distance (debug overlay) and, per detected object,
 * a robust multi-point sample over its bounding box.
 */
object DepthProcessor {

    /**
     * Returns the distance in meters at the center of the depth image, or
     * null if depth data isn't available yet for this frame, or there is no
     * valid estimate for that specific pixel.
     */
    fun centerDepthMeters(frame: Frame): Float? {
        return try {
            frame.acquireDepthImage16Bits().use { depthImage ->
                val plane = depthImage.planes[0]
                val buffer = plane.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()

                val centerX = depthImage.width / 2
                val centerY = depthImage.height / 2
                // rowStride/pixelStride are reported in bytes; convert the
                // byte offset to a ShortBuffer index (2 bytes per 16-bit
                // depth sample).
                val shortIndex = (centerY * plane.rowStride + centerX * plane.pixelStride) / 2

                // acquireDepthImage16Bits() uses the FULL 16 bits per pixel
                // for millimeters (0-65535mm) — unlike the older, deprecated
                // acquireDepthImage(), which packed 13 bits of depth + 3 bits
                // of confidence into the same 16 bits. Do not mask this one.
                // A value of 0 means "no valid depth estimate for this pixel".
                val millimeters = buffer.get(shortIndex).toInt() and 0xFFFF
                if (millimeters == 0) null else millimeters / 1000f
            }
        } catch (e: NotYetAvailableException) {
            null
        }
    }

    /**
     * Returns a robust (median) distance in meters over a grid of sample
     * points inside [detection]'s bounding box, or null if depth isn't
     * available or every sampled point is invalid.
     *
     * ASSUMPTION: the depth buffer shares the same sensor-native orientation
     * and aspect ratio as the raw camera image (just possibly a different,
     * usually lower, resolution) — reasonable for ARCore's depth output, but
     * not something this project can confirm without running on-device.
     * Sensor-space coordinates are reused from [ImagePreprocessor] (same
     * verified rotation math, not re-derived) and simply rescaled by the
     * ratio of depth-image size to sensor-image size.
     *
     * NOTE ON STALENESS: [detection] may be a frame or two old by the time
     * this runs, since YOLO inference (Phase 12) takes longer than one
     * camera frame — this samples depth from the CURRENT frame regardless.
     * For a walking-pace navigation aid this small temporal offset is an
     * acceptable approximation, not a silent correctness claim otherwise.
     */
    fun boxDepthMeters(
        frame: Frame,
        detection: Detection,
        preprocessResult: PreprocessResult,
        gridSize: Int = 5
    ): Float? {
        return try {
            frame.acquireDepthImage16Bits().use { depthImage ->
                val plane = depthImage.planes[0]
                val buffer = plane.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride

                val scaleX = depthImage.width.toFloat() / preprocessResult.sensorWidth
                val scaleY = depthImage.height.toFloat() / preprocessResult.sensorHeight

                val samples = mutableListOf<Int>()
                for (gx in 0 until gridSize) {
                    for (gy in 0 until gridSize) {
                        val uprightX = (detection.left + detection.width * (gx + 0.5f) / gridSize)
                            .toInt().coerceIn(0, preprocessResult.imageWidth - 1)
                        val uprightY = (detection.top + detection.height * (gy + 0.5f) / gridSize)
                            .toInt().coerceIn(0, preprocessResult.imageHeight - 1)

                        val (sensorX, sensorY) = ImagePreprocessor.mapUprightToSensor(
                            uprightX, uprightY, preprocessResult.imageWidth
                        )

                        val depthX = (sensorX * scaleX).toInt().coerceIn(0, depthImage.width - 1)
                        val depthY = (sensorY * scaleY).toInt().coerceIn(0, depthImage.height - 1)

                        val shortIndex = (depthY * rowStride + depthX * pixelStride) / 2
                        val millimeters = buffer.get(shortIndex).toInt() and 0xFFFF
                        if (millimeters > 0) samples.add(millimeters)
                    }
                }

                if (samples.isEmpty()) {
                    null
                } else {
                    samples.sort()
                    samples[samples.size / 2] / 1000f
                }
            }
        } catch (e: NotYetAvailableException) {
            null
        }
    }
}
