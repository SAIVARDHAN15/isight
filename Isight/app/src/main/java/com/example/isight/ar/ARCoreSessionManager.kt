package com.example.isight.ar

import android.app.Activity
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException

private const val TAG = "ARCoreSessionManager"

/**
 * Result of a single [ARCoreSessionManager.tryCreateSession] attempt.
 *
 * [InstallRequested] means Play Store / Google Play Services for AR has been
 * launched on top of this activity; the activity will be paused and then
 * resumed again automatically once that flow completes, at which point
 * [tryCreateSession] should be called again.
 */
sealed class ArSessionResult {
    data class Ready(val session: Session) : ArSessionResult()
    object InstallRequested : ArSessionResult()
    data class Unavailable(val reason: String) : ArSessionResult()
}

/**
 * Owns the lifecycle of a single ARCore [Session] for one activity.
 *
 * Callers must invoke [tryCreateSession] (which also resumes an existing
 * session) from `onResume`, [pause] from `onPause`, and [close] from
 * `onDestroy`. Camera permission must already be granted before calling
 * [tryCreateSession].
 */
class ARCoreSessionManager(private val activity: Activity) {

    var session: Session? = null
        private set

    private var installRequested = false

    fun tryCreateSession(): ArSessionResult {
        session?.let { existing ->
            return if (resume(existing)) {
                ArSessionResult.Ready(existing)
            } else {
                ArSessionResult.Unavailable("Camera unavailable")
            }
        }

        return try {
            val installStatus = ArCoreApk.getInstance().requestInstall(activity, !installRequested)
            when (installStatus) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    ArSessionResult.InstallRequested
                }
                ArCoreApk.InstallStatus.INSTALLED -> {
                    val newSession = Session(activity)
                    configure(newSession)
                    session = newSession
                    if (resume(newSession)) {
                        ArSessionResult.Ready(newSession)
                    } else {
                        ArSessionResult.Unavailable("Camera unavailable")
                    }
                }
                else -> ArSessionResult.Unavailable("Unknown ARCore install status: $installStatus")
            }
        } catch (e: UnavailableArcoreNotInstalledException) {
            ArSessionResult.Unavailable("ARCore is not installed on this device")
        } catch (e: UnavailableUserDeclinedInstallationException) {
            ArSessionResult.Unavailable("User declined ARCore installation")
        } catch (e: UnavailableApkTooOldException) {
            ArSessionResult.Unavailable("Installed ARCore APK is too old")
        } catch (e: UnavailableSdkTooOldException) {
            ArSessionResult.Unavailable("This app was built against an outdated ARCore SDK")
        } catch (e: UnavailableDeviceNotCompatibleException) {
            ArSessionResult.Unavailable("This device does not support ARCore")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ARCore session", e)
            ArSessionResult.Unavailable("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun configure(session: Session) {
        val config = Config(session)
        config.focusMode = Config.FocusMode.AUTO
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        session.configure(config)
    }

    private fun resume(session: Session): Boolean {
        return try {
            session.resume()
            true
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available - is another app using it?", e)
            this.session = null
            false
        }
    }

    fun pause() {
        session?.pause()
    }

    fun close() {
        session?.close()
        session = null
    }
}
