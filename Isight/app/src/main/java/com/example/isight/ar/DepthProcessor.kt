package com.example.isight.ar

import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder

/**
 * Reads ARCore's per-pixel Depth API output for a single frame.
 *
 * Phase 5 only needs a single center-of-frame distance for the debug
 * overlay. Per-object depth sampling (multiple points inside a detection's
 * bounding box, median/robust fusion) is added once YOLO detections exist —
 * see the object+depth fusion phase.
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
}
