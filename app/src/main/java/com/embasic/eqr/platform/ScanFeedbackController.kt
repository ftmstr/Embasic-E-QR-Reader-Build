package com.embasic.eqr.platform

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import com.embasic.eqr.data.settings.AppSettings

object ScanFeedbackController {
    fun notifySuccess(
        context: Context,
        view: View,
        settings: AppSettings,
    ) {
        if (settings.hapticsEnabled) {
            runCatching {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }

        if (settings.soundEnabled) {
            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
                Handler(Looper.getMainLooper()).postDelayed(
                    { runCatching { tone.release() } },
                    140L,
                )
            }
        }
    }
}
