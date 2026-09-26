package com.example.isight.haptics

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Which situation a haptic cue represents. Patterns are intentionally
 * simple constants here so they're easy to retune.
 *
 * NOTE: almost all phones have exactly ONE vibration motor, so true
 * spatial (left-vs-right) haptic feedback isn't physically possible on
 * this hardware — LEFT/RIGHT are distinguished by pulse COUNT/pattern
 * instead (1 pulse vs 2), not by location.
 */
enum class HapticCue { LEFT_OBSTACLE, RIGHT_OBSTACLE, FRONT_OBSTACLE, DESTINATION_REACHED }

class HapticFeedbackManager(context: Context) {

    private val vibrator: Vibrator?
    private val vibratorManager: VibratorManager?

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibrator = null
        } else {
            @Suppress("DEPRECATION")
            vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibratorManager = null
        }
    }

    fun play(cue: HapticCue) {
        val effect = when (cue) {
            HapticCue.LEFT_OBSTACLE -> VibrationEffect.createWaveform(longArrayOf(0, 60), -1)
            HapticCue.RIGHT_OBSTACLE -> VibrationEffect.createWaveform(longArrayOf(0, 60, 70, 60), -1)
            HapticCue.FRONT_OBSTACLE -> VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120, 80, 120), -1)
            HapticCue.DESTINATION_REACHED -> VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 80, 60, 200), -1)
        }
        vibrate(effect)
    }

    private fun vibrate(effect: VibrationEffect) {
        vibratorManager?.vibrate(CombinedVibration.createParallel(effect)) ?: vibrator?.vibrate(effect)
    }
}
