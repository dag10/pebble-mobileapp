package io.rebble.libpebblecommon.findphone

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.DrewFindPhone
import io.rebble.libpebblecommon.plugin.PhoneSoundPlayer
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Plays [FindPhoneTone] loudly, even when the phone is silenced or its volume is low. */
interface PhoneRinger {
    /** Starts the tone, or restarts it if it's already playing. */
    fun ring()

    /** Stops the tone early and puts the volume back. */
    fun stop()

    /** Stops the tone but keeps the raised volume and audio session until [stop]. */
    fun hold()
}

class FindPhoneManager(
    private val watchScope: ConnectionCoroutineScope,
    private val protocolHandler: PebbleProtocolHandler,
    private val phoneRinger: PhoneRinger,
    private val phoneSoundPlayer: PhoneSoundPlayer,
) {
    private val logger = Logger.withTag("FindPhoneManager")
    private var upstreamRinging = false

    fun init() {
        // Upstream's Find My Phone rings through PhoneSoundPlayer, which saves and restores the
        // volume itself, so this ringer steps aside until it's done and puts its own volume back last.
        phoneSoundPlayer.isRinging
            .onEach { ringing ->
                val wasRinging = upstreamRinging
                upstreamRinging = ringing
                if (ringing) phoneRinger.hold() else if (wasRinging) phoneRinger.stop()
            }
            .launchIn(watchScope)
        protocolHandler.inboundMessages
            .onEach {
                when (it) {
                    is DrewFindPhone.Start -> {
                        if (upstreamRinging) {
                            logger.i { "Watch asked to find the phone; already ringing for Find My Phone" }
                        } else {
                            logger.i { "Watch asked to find the phone" }
                            phoneRinger.ring()
                        }
                    }
                    is DrewFindPhone.Stop -> {
                        logger.i { "Watch asked to stop finding the phone" }
                        if (!upstreamRinging) phoneRinger.stop()
                    }
                }
            }
            .launchIn(watchScope)
    }
}
