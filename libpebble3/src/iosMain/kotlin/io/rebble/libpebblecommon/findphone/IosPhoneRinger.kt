package io.rebble.libpebblecommon.findphone

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.outputVolume
import platform.AVFAudio.setActive
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.create
import platform.MediaPlayer.MPVolumeView
import platform.UIKit.UIApplication
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
 * The playback category ignores the Ring/Silent switch and Focus. Media volume has no public API,
 * so it's raised through MPVolumeView's slider and put back afterwards.
 */
class IosPhoneRinger : PhoneRinger {
    private val logger = Logger.withTag("IosPhoneRinger")
    private val wav: NSData by lazy {
        val bytes = FindPhoneTone.wav()
        bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    }
    private var volumeView: MPVolumeView? = null
    private var player: AVAudioPlayer? = null
    private var volumeToRestore: Float? = null
    private var generation = 0
    private var backgroundTask = UIBackgroundTaskInvalid

    override fun ring() {
        dispatch_async(dispatch_get_main_queue()) { start() }
    }

    override fun stop() {
        dispatch_async(dispatch_get_main_queue()) {
            if (player == null) return@dispatch_async
            generation++
            finish()
        }
    }

    private fun start() {
        player?.stop()
        // iOS can suspend the app once the sound stops, before the volume is put back.
        if (backgroundTask == UIBackgroundTaskInvalid) {
            backgroundTask = UIApplication.sharedApplication.beginBackgroundTaskWithName(
                "Find Phone",
                expirationHandler = { endBackgroundTask() },
            )
        }
        val session = AVAudioSession.sharedInstance()
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            // Mixable, so it can be activated while the app is in the background.
            session.setCategory(
                AVAudioSessionCategoryPlayback,
                withOptions = AVAudioSessionCategoryOptionDuckOthers,
                error = err.ptr,
            )
            if (!session.setActive(true, error = err.ptr)) {
                logger.e { "Couldn't activate the audio session: ${err.value?.localizedDescription}" }
                return
            }
            val newPlayer = AVAudioPlayer(data = wav, error = err.ptr)
            if (err.value != null) {
                logger.e { "Couldn't create the player: ${err.value?.localizedDescription}" }
                return
            }
            player = newPlayer
        }
        // outputVolume can be stale right after activation. A new MPVolumeView fetches the real
        // volume, and its slider only appears once it has.
        if (volumeView == null) volumeView = MPVolumeView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))
        after(200.milliseconds) { raiseVolume() }
        player?.play()

        val thisGeneration = ++generation
        after(FindPhoneTone.DURATION + 300.milliseconds) {
            if (thisGeneration == generation) finish()
        }
    }

    private fun finish() {
        player?.stop()
        player = null
        volumeToRestore?.let {
            logger.d { "Restoring the volume to $it" }
            setSystemVolume(it)
        }
        volumeToRestore = null
        // Deactivating before the restore lands leaves the session's outputVolume at the raised
        // level, which the next ping would then save. The app must also stay awake until then.
        val thisGeneration = generation
        after(500.milliseconds) {
            if (thisGeneration != generation) return@after
            volumeView = null
            deactivateSession()
            endBackgroundTask()
        }
    }

    private fun deactivateSession() = memScoped {
        val err = alloc<ObjCObjectVar<NSError?>>()
        AVAudioSession.sharedInstance().setActive(
            false,
            withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
            error = err.ptr,
        )
    }

    private fun endBackgroundTask() {
        if (backgroundTask == UIBackgroundTaskInvalid) return
        UIApplication.sharedApplication.endBackgroundTask(backgroundTask)
        backgroundTask = UIBackgroundTaskInvalid
    }

    private fun raiseVolume() {
        if (player == null) return
        val slider = volumeSlider() ?: return
        if (volumeToRestore == null) {
            volumeToRestore = slider.value
            logger.d {
                "Saved the volume, ${slider.value} (outputVolume " +
                    "${AVAudioSession.sharedInstance().outputVolume})"
            }
        }
        slider.setValue(1f, animated = false)
    }

    private fun setSystemVolume(volume: Float) {
        volumeSlider()?.setValue(volume, animated = false)
    }

    private fun volumeSlider(): UISlider? {
        val slider = volumeView?.subviews?.filterIsInstance<UISlider>()?.firstOrNull()
        if (slider == null) logger.w { "No volume slider; leaving the volume as it is" }
        return slider
    }

    private fun after(delay: Duration, block: () -> Unit) {
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, delay.inWholeNanoseconds),
            dispatch_get_main_queue(),
            block,
        )
    }
}
