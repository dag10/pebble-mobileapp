package io.rebble.libpebblecommon.findphone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import co.touchlab.kermit.Logger

/**
 * The alarm stream plays through silent and vibrate ringer modes, and Do Not Disturb lets alarms
 * through by default. Its volume is raised to the maximum and put back afterwards.
 */
class AndroidPhoneRinger(context: Context) : PhoneRinger {
    private val logger = Logger.withTag("AndroidPhoneRinger")
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val pcm: ShortArray by lazy { FindPhoneTone.pcm16() }
    private val finishRunnable = Runnable { finish() }
    private var track: AudioTrack? = null
    private var volumeToRestore: Int? = null

    override fun ring() {
        handler.post { start() }
    }

    override fun stop() {
        handler.post {
            handler.removeCallbacks(finishRunnable)
            finish()
        }
    }

    private fun start() {
        handler.removeCallbacks(finishRunnable)
        track?.release()
        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(FindPhoneTone.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        newTrack.write(pcm, 0, pcm.size)
        track = newTrack

        if (volumeToRestore == null) volumeToRestore = audioManager.getStreamVolume(STREAM)
        setVolume(audioManager.getStreamMaxVolume(STREAM))
        newTrack.play()
        handler.postDelayed(finishRunnable, FindPhoneTone.DURATION.inWholeMilliseconds + 300)
    }

    private fun finish() {
        track?.release()
        track = null
        volumeToRestore?.let { setVolume(it) }
        volumeToRestore = null
    }

    private fun setVolume(volume: Int) {
        try {
            audioManager.setStreamVolume(STREAM, volume, 0)
        } catch (e: SecurityException) {
            logger.w(e) { "Couldn't set the alarm volume" }
        }
    }

    private companion object {
        const val STREAM = AudioManager.STREAM_ALARM
    }
}
