package io.rebble.libpebblecommon.plugin

import co.touchlab.kermit.Logger
import platform.CoreGraphics.CGRectMake
import platform.MediaPlayer.MPVolumeView
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UIKit.UISlider
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Temporarily raises the media volume to the maximum and puts it back afterwards. iOS has no
 * public API to set the volume, so this goes through MPVolumeView's slider, on the main thread.
 */
internal class SystemVolumeRaiser {
    private val logger = Logger.withTag("SystemVolumeRaiser")
    private var volumeView: MPVolumeView? = null
    private var volumeToRestore: Float? = null
    private var generation = 0
    private var backgroundTask: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    fun raise() = onMain {
        val thisGeneration = ++generation
        // The app may be suspended once the sound stops, before the volume is put back.
        if (backgroundTask == UIBackgroundTaskInvalid) {
            backgroundTask = UIApplication.sharedApplication.beginBackgroundTaskWithName(
                "Raise volume",
                expirationHandler = { endBackgroundTask() },
            )
        }
        // AVAudioSession.outputVolume can be stale right after the session activates. A new
        // MPVolumeView fetches the real volume, and only adds its slider once it has.
        if (volumeView == null) volumeView = MPVolumeView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))
        after(SLIDER_READY_DELAY) {
            if (thisGeneration != generation) return@after
            val slider = volumeSlider() ?: return@after
            if (volumeToRestore == null) volumeToRestore = slider.value
            logger.d { "Raising the volume from $volumeToRestore" }
            slider.setValue(1f, animated = false)
        }
    }

    /** Puts the volume back, then calls [onRestored] once the change has landed. */
    fun restore(onRestored: () -> Unit) = onMain {
        val thisGeneration = ++generation
        volumeToRestore?.let {
            logger.d { "Restoring the volume to $it" }
            volumeSlider()?.setValue(it, animated = false)
        }
        volumeToRestore = null
        // Deactivating the audio session before the restore lands leaves outputVolume at the
        // raised level.
        after(RESTORE_DELAY) {
            if (thisGeneration != generation) return@after
            volumeView = null
            onRestored()
            endBackgroundTask()
        }
    }

    private fun volumeSlider(): UISlider? {
        val slider = volumeView?.subviews?.filterIsInstance<UISlider>()?.firstOrNull()
        if (slider == null) logger.w { "No volume slider; leaving the volume as it is" }
        return slider
    }

    private fun endBackgroundTask() {
        if (backgroundTask == UIBackgroundTaskInvalid) return
        UIApplication.sharedApplication.endBackgroundTask(backgroundTask)
        backgroundTask = UIBackgroundTaskInvalid
    }

    private fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue(), block)

    private fun after(delay: Duration, block: () -> Unit) = dispatch_after(
        dispatch_time(DISPATCH_TIME_NOW, delay.inWholeNanoseconds),
        dispatch_get_main_queue(),
        block,
    )

    private companion object {
        val SLIDER_READY_DELAY = 200.milliseconds
        val RESTORE_DELAY = 500.milliseconds
    }
}
