package com.example.isight.ar

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import com.google.ar.core.Session

/**
 * Tracks display-rotation and surface-size changes and forwards them to an
 * ARCore [Session] via [Session.setDisplayGeometry], which ARCore needs in
 * order to correctly map the camera sensor image onto the current screen
 * orientation/aspect ratio.
 *
 * Mirrors the DisplayRotationHelper used in Google's own ARCore samples.
 */
@Suppress("DEPRECATION") // Display.getRotation() via defaultDisplay is deprecated
// since API 30 in favor of Context#getDisplay(), which requires minSdk 30;
// ARCore's own current samples still use this same approach for broad compat.
class DisplayRotationHelper(context: Context) : DisplayManager.DisplayListener {

    private var viewportChanged = false
    private var viewportWidth = 0
    private var viewportHeight = 0

    private val display: Display =
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay

    private val displayManager: DisplayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    fun onResume() {
        displayManager.registerDisplayListener(this, null)
    }

    fun onPause() {
        displayManager.unregisterDisplayListener(this)
    }

    /** Call from GLSurfaceView.Renderer#onSurfaceChanged. */
    fun onSurfaceChanged(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    /** Call once per frame, before Session#update(). */
    fun updateSessionIfNeeded(session: Session) {
        if (viewportChanged) {
            session.setDisplayGeometry(display.rotation, viewportWidth, viewportHeight)
            viewportChanged = false
        }
    }

    override fun onDisplayAdded(displayId: Int) {}

    override fun onDisplayRemoved(displayId: Int) {}

    override fun onDisplayChanged(displayId: Int) {
        viewportChanged = true
    }
}
