package com.github.libretube.helpers

import android.app.Activity
import android.view.Window
import android.view.WindowManager
import kotlin.math.exp
import kotlin.math.ln

/**
 * @param windowProvider provides the window whose brightness should be changed. This must be the
 * window that is currently shown on top (e.g. the fullscreen dialog), since the system ignores the
 * brightness of windows that are covered by another full screen window.
 */
class BrightnessHelper(
    private val activity: Activity,
    private val windowProvider: () -> Window = { activity.window }
) {
    private val window get() = windowProvider()

    /**
     * Wrapper for the current screen brightness, linearly scaled between 0 and 1.
     */
    var windowBrightness: Float
        get() = gammaToLinear(window.attributes.screenBrightness)
        set(value) {
            window.attributes = window.attributes.apply {
                screenBrightness = linearToGamma(value).coerceIn(0.0f, 1.0f)
            }

            savedWindowBrightness = value
        }

    /**
     * Wrapper for the brightness saved per session.
     * Used to restore the previous fullscreen brightness when entering fullscreen.
     */
    var savedWindowBrightness = windowBrightness
        private set

    /**
     * Restore screen brightness to device system brightness.
     */
    fun resetToSystemBrightness(target: Window = window) {
        target.attributes = target.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    /**
     * Set current screen brightness to saved brightness value.
     */
    fun restoreSavedBrightness() {
        // nothing was saved yet, i.e. the brightness was never changed by the user
        if (!savedWindowBrightness.isFinite()) return

        windowBrightness = savedWindowBrightness
    }

    /**
     * Logarithmically scale brightness changes, in order to have more control over the lower brightness area
     * @param value brightness value in the interval [0, 1]
     * @return scaled brightness value in the same interval [0, 1]
     *
     * see https://tunjid.medium.com/reverse-engineering-android-pies-logarithmic-brightness-curve-ecd41739d7a2
     * and resolve the formula to x=... the constants are slightly adjusted to actually reach 100% max
     */
    private fun linearToGamma(value: Float): Float {
        // original formula: (Math.exp((value*100+9.411)/19.811) / 255.0).toFloat()
        return (exp((value * LINEAR_MAX + LINEAR_OFFSET) / SCALING_FACTOR) / GAMMA_MAX).toFloat()
    }

    /**
     * Inverse method for [linearToGamma]
     */
    private fun gammaToLinear(value: Float): Float {
        return ((SCALING_FACTOR * ln(value * GAMMA_MAX) - LINEAR_OFFSET) / LINEAR_MAX).toFloat()
    }

    companion object {
        // constants only used for linear-gamma conversion
        private const val GAMMA_MAX = 255.0
        private const val LINEAR_MAX = 100.0
        private const val LINEAR_OFFSET = 9.7
        private const val SCALING_FACTOR = 19.9
    }
}
