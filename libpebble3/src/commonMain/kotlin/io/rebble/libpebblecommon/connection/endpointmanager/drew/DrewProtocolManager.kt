package io.rebble.libpebblecommon.connection.endpointmanager.drew

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.drew.DrewExperiments
import io.rebble.libpebblecommon.packets.DrewFeature
import io.rebble.libpebblecommon.packets.DrewFeatures
import io.rebble.libpebblecommon.packets.DrewHeartbeat
import io.rebble.libpebblecommon.packets.DrewHeartbeatAck
import io.rebble.libpebblecommon.packets.DrewHello
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.isDrewPebbleOs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** This app's version, sent in its hello for the watch's logs. */
data class DrewPhoneBuild(val version: String)

/** The features this phone announces with [config]. */
internal fun localDrewFeatures(config: LibPebbleConfig): DrewFeatures = DrewFeatures.of(
    listOfNotNull(
        DrewFeature.Updates,
        DrewFeature.Heartbeat.takeIf { config.bleConfig.drewHeartbeat },
        DrewFeature.FindPhone,
    )
)

/** The phone's side of the Drew protocol's hellos and heartbeat for one connection. */
class DrewProtocolManager(
    private val protocolHandler: PebbleProtocolHandler,
    private val scope: ConnectionCoroutineScope,
    private val libPebbleConfigFlow: LibPebbleConfigFlow,
    private val phoneBuild: DrewPhoneBuild,
) : ConnectedPebble.Drew {
    private val logger = Logger.withTag("DrewProtocol")
    private val _watchFeatures = MutableStateFlow(DrewFeatures.None)
    private val _drewFeatures = MutableStateFlow(DrewFeatures.None)
    private val watchHelloReceived = MutableStateFlow(false)

    /** What the watch announced in its last hello; none until one arrives. */
    val watchFeatures: StateFlow<DrewFeatures> = _watchFeatures.asStateFlow()

    val localFeatures: DrewFeatures get() = localDrewFeatures(libPebbleConfigFlow.value)

    override val drewFeatures: StateFlow<DrewFeatures> = _drewFeatures.asStateFlow()

    /** Call before negotiation, so a hello the watch sends as its session opens isn't missed. */
    fun init() {
        scope.launch {
            protocolHandler.inboundMessages.collect { packet ->
                when (packet) {
                    is DrewHello.Watch -> onWatchHello(packet)
                    is DrewHeartbeat -> {
                        logger.d { "Heartbeat ${packet.cookie.get()}" }
                        DrewExperiments.count(DrewExperiments.HEARTBEAT_PING, "Heartbeat ${packet.cookie.get()}")
                        protocolHandler.send(DrewHeartbeatAck(packet.cookie.get()))
                    }
                }
            }
        }
        scope.launch {
            libPebbleConfigFlow.flow
                .map { localDrewFeatures(it) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    updateDrewFeatures()
                    if (watchHelloReceived.value) {
                        sendHello(replyRequested = false)
                    }
                }
        }
    }

    /**
     * After negotiation. If the watch hasn't said hello yet but its firmware is tagged
     * DrewPebbleOS, asks it to and waits up to [HELLO_WAIT]. Returns at once otherwise.
     */
    suspend fun awaitWatchHello(runningFirmware: FirmwareVersion) {
        if (watchHelloReceived.value || !runningFirmware.isDrewPebbleOs()) {
            return
        }
        sendHello(replyRequested = true)
        withTimeoutOrNull(HELLO_WAIT) { watchHelloReceived.first { it } }
            ?: logger.w { "No hello from ${runningFirmware.stringVersion} within $HELLO_WAIT" }
    }

    private suspend fun onWatchHello(hello: DrewHello.Watch) {
        logger.i { "Watch hello: $hello" }
        _watchFeatures.value = hello.features
        updateDrewFeatures()
        watchHelloReceived.value = true
        if (hello.replyRequested) {
            sendHello(replyRequested = false)
        }
    }

    private fun updateDrewFeatures() {
        _drewFeatures.value = _watchFeatures.value and localFeatures
    }

    private suspend fun sendHello(replyRequested: Boolean) {
        protocolHandler.send(
            DrewHello.Phone(
                flags = if (replyRequested) DrewHello.FLAG_REPLY_REQUESTED else 0u,
                features = localFeatures,
                build = phoneBuild.version,
            )
        )
    }

    companion object {
        private val HELLO_WAIT = 3.seconds
    }
}
