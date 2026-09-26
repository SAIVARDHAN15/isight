package com.example.isight.trigger

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

enum class TriggerState { INACTIVE, ACTIVE }

/**
 * Detects a long-press-and-hold on a single hardware volume key to
 * activate (~3s hold) / deactivate (~4s hold) iSight, so a blind user never
 * needs to find an on-screen button.
 *
 * ## Platform limitation — read before assuming this works everywhere
 * This only works while iSight's activity is the FOREGROUND, FOCUSED
 * window (wired up via `Activity#dispatchKeyEvent`). Android provides no
 * public API for an ordinary app to intercept hardware volume keys
 * system-wide — from the home screen, the lock screen, or while another app
 * is focused, iSight will not see these key events at all. The one real
 * escape hatch is an `AccessibilityService` with
 * `FLAG_REQUEST_FILTER_KEY_EVENTS`, which can observe hardware keys more
 * broadly, but that requires the user to explicitly grant a separate
 * Accessibility Service permission (a heavier, distinct setup flow) and
 * careful handling so it never breaks the user's normal system volume
 * control elsewhere. That is real, buildable future work, not implemented
 * here — this is deliberately "the closest reliable foreground approach."
 *
 * ## Why holding the volume key can still nudge the volume level
 * Android delivers a held key as repeated ACTION_DOWN events and keeps
 * applying its own default volume-change behavior for however long the key
 * is held BEFORE this class's multi-second threshold is reached — there is
 * no OS concept of "long-press" for volume keys; a long press is only
 * distinguishable from a tap in hindsight. To keep this invisible to the
 * user, the stream volume from right before the press began is restored via
 * [AudioManager] the instant the hold threshold actually fires. A short tap
 * (below the threshold) is never intercepted, so ordinary volume control is
 * unaffected.
 */
class VolumeButtonTrigger(
    context: Context,
    private val activateHoldMillis: Long = 3000L,
    private val deactivateHoldMillis: Long = 4000L,
    private val watchedKeyCode: Int = KeyEvent.KEYCODE_VOLUME_DOWN,
    private val onActivate: () -> Unit,
    private val onDeactivate: () -> Unit,
    private val onHoldProgress: (heldMillis: Long, thresholdMillis: Long) -> Unit = { _, _ -> }
) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var state = TriggerState.INACTIVE
    private var pressStartMillis: Long = -1L
    private var firedForThisPress = false
    private var volumeAtPressStart = -1

    /** Call from `Activity#dispatchKeyEvent`. Returns true if this event was consumed. */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != watchedKeyCode) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> handleDown(event)
            KeyEvent.ACTION_UP -> handleUp()
            else -> false
        }
    }

    private fun handleDown(event: KeyEvent): Boolean {
        if (event.repeatCount == 0) {
            pressStartMillis = System.currentTimeMillis()
            firedForThisPress = false
            volumeAtPressStart = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        }

        if (firedForThisPress) return true // already fired this hold - keep suppressing until release

        val heldMillis = System.currentTimeMillis() - pressStartMillis
        val thresholdMillis = if (state == TriggerState.INACTIVE) activateHoldMillis else deactivateHoldMillis
        onHoldProgress(heldMillis, thresholdMillis)

        if (heldMillis < thresholdMillis) {
            return false // still a short/ordinary press so far - let normal volume behavior happen
        }

        firedForThisPress = true
        restoreVolume()
        if (state == TriggerState.INACTIVE) {
            state = TriggerState.ACTIVE
            onActivate()
        } else {
            state = TriggerState.INACTIVE
            onDeactivate()
        }
        return true
    }

    private fun handleUp(): Boolean {
        pressStartMillis = -1L
        val consumed = firedForThisPress
        firedForThisPress = false
        return consumed
    }

    private fun restoreVolume() {
        if (volumeAtPressStart >= 0) {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, volumeAtPressStart, 0)
        }
    }
}
