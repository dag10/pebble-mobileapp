package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.SystemMessage
import io.rebble.libpebblecommon.plugin.PhoneSoundPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/** Rings the phone briefly when the watch's Ping Phone button asks it to. */
class FindPhoneManager(
    private val watchScope: ConnectionCoroutineScope,
    private val protocolHandler: PebbleProtocolHandler,
    private val soundPlayer: PhoneSoundPlayer,
) {
    private val logger = Logger.withTag("FindPhoneManager")
    private var ringJob: Job? = null

    fun init() {
        protocolHandler.inboundMessages.onEach {
            when (it) {
                is SystemMessage.FindPhone -> ring()
                is SystemMessage.FindPhoneStop -> stop()
            }
        }.launchIn(watchScope)
    }

    private suspend fun ring() {
        logger.d { "Watch asked to ring the phone" }
        ringJob?.cancelAndJoin()
        ringJob = watchScope.launch {
            soundPlayer.startRinging(raiseVolume = true)
            // Also stops the sound if the watch disconnects while it plays.
            try {
                delay(RING_DURATION)
            } finally {
                soundPlayer.stopRinging()
            }
        }
    }

    private suspend fun stop() {
        logger.d { "Watch asked to stop ringing the phone" }
        ringJob?.cancelAndJoin()
        ringJob = null
    }

    companion object {
        // Matches how long the watch shows that it is pinging.
        private val RING_DURATION = 5.seconds
    }
}
