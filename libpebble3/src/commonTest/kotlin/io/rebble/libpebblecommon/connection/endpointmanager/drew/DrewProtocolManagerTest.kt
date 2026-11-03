package io.rebble.libpebblecommon.connection.endpointmanager.drew

import TestPebbleProtocolHandler
import io.rebble.libpebblecommon.BleConfig
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.DrewFeature
import io.rebble.libpebblecommon.packets.DrewFeatures
import io.rebble.libpebblecommon.packets.DrewHeartbeat
import io.rebble.libpebblecommon.packets.DrewHeartbeatAck
import io.rebble.libpebblecommon.packets.DrewHello
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.services.FirmwareVersion
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

private fun firmware(tag: String) = FirmwareVersion.from(
    tag = tag,
    isRecovery = false,
    gitHash = "",
    timestamp = Instant.DISTANT_PAST,
    isDualSlot = false,
    isSlot0 = false,
)!!

private val ALL_FEATURES = DrewFeatures.of(DrewFeature.entries)

class DrewProtocolManagerTest {
    private val sent = mutableListOf<PebblePacket>()
    private val handler = TestPebbleProtocolHandler { sent += it }
    private val config = MutableStateFlow(LibPebbleConfig())

    private fun TestScope.manager(): DrewProtocolManager {
        val manager = DrewProtocolManager(
            protocolHandler = handler,
            scope = ConnectionCoroutineScope(backgroundScope.coroutineContext),
            libPebbleConfigFlow = LibPebbleConfigFlow(config),
            phoneBuild = DrewPhoneBuild("1.16.0.4-drew.3"),
        )
        manager.init()
        runCurrent()
        return manager
    }

    private suspend fun TestScope.receive(packet: PebblePacket) {
        handler.receivePacket(packet)
        runCurrent()
    }

    @Test
    fun announcesWhatThePhoneImplements() {
        assertEquals(
            DrewFeatures.of(
                DrewFeature.Updates,
                DrewFeature.Heartbeat,
                DrewFeature.FindPhone,
                DrewFeature.NotificationFlags,
            ),
            localDrewFeatures(LibPebbleConfig()),
        )
        val noHeartbeat = LibPebbleConfig(bleConfig = BleConfig(drewHeartbeat = false))
        assertFalse(DrewFeature.Heartbeat in localDrewFeatures(noHeartbeat))
    }

    @Test
    fun tellsTheWatchWhenTheHeartbeatSettingChanges() = runTest {
        val manager = manager()
        receive(DrewHello.Watch(features = ALL_FEATURES))
        assertTrue(DrewFeature.Heartbeat in manager.drewFeatures.value)

        config.value = LibPebbleConfig(bleConfig = BleConfig(drewHeartbeat = false))
        runCurrent()

        val hello = assertIs<DrewHello.Phone>(sent.single())
        assertFalse(hello.replyRequested)
        assertFalse(DrewFeature.Heartbeat in hello.features)
        assertFalse(DrewFeature.Heartbeat in manager.drewFeatures.value)
    }

    @Test
    fun keepsQuietAboutSettingChangesBeforeTheWatchSaysHello() = runTest {
        manager()
        config.value = LibPebbleConfig(bleConfig = BleConfig(drewHeartbeat = false))
        runCurrent()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun answersAHelloThatAsksForOne() = runTest {
        val manager = manager()
        assertEquals(DrewFeatures.None, manager.watchFeatures.value)

        receive(DrewHello.Watch(DrewHello.FLAG_REPLY_REQUESTED, ALL_FEATURES, "v4.44.1-drew.6"))

        assertEquals(ALL_FEATURES, manager.watchFeatures.value)
        assertEquals(manager.localFeatures, manager.drewFeatures.value)
        val reply = assertIs<DrewHello.Phone>(sent.single())
        assertFalse(reply.replyRequested)
        assertEquals(manager.localFeatures, reply.features)
        assertEquals("1.16.0.4-drew.3", reply.build.get())
    }

    @Test
    fun anUnaskedHelloReplacesTheFeaturesWithoutAnAnswer() = runTest {
        val manager = manager()
        receive(DrewHello.Watch(features = ALL_FEATURES))
        receive(DrewHello.Watch(features = DrewFeatures.of(DrewFeature.Updates)))

        assertEquals(DrewFeatures.of(DrewFeature.Updates), manager.watchFeatures.value)
        assertEquals(DrewFeatures.of(DrewFeature.Updates) and manager.localFeatures, manager.drewFeatures.value)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun acksAHeartbeatWithItsCookie() = runTest {
        manager()
        receive(DrewHeartbeat(0xC0FFEEu))
        assertEquals(0xC0FFEEu, assertIs<DrewHeartbeatAck>(sent.single()).cookie.get())
    }

    @Test
    fun doesNotWaitForStockFirmware() = runTest {
        val manager = manager()
        manager.awaitWatchHello(firmware("v4.44.1"))
        assertTrue(sent.isEmpty())
        assertEquals(0, currentTime)
    }

    @Test
    fun asksATaggedWatchForItsHelloAndWaitsForIt() = runTest {
        val manager = manager()
        val wait = async { manager.awaitWatchHello(firmware("v4.44.1-drew.6")) }
        runCurrent()
        val ask = assertIs<DrewHello.Phone>(sent.single())
        assertTrue(ask.replyRequested)

        advanceTimeBy(1_000)
        receive(DrewHello.Watch(features = ALL_FEATURES))
        assertTrue(wait.isCompleted)
        assertEquals(ALL_FEATURES, manager.watchFeatures.value)
        assertEquals(1, sent.size)
    }

    @Test
    fun givesUpOnATaggedWatchAfterThreeSeconds() = runTest {
        val manager = manager()
        manager.awaitWatchHello(firmware("v4.44.1-drew.6"))
        assertEquals(3_000, currentTime)
        assertEquals(DrewFeatures.None, manager.drewFeatures.value)
    }

    @Test
    fun doesNotAskAWatchThatAlreadySaidHello() = runTest {
        val manager = manager()
        receive(DrewHello.Watch(features = ALL_FEATURES))
        manager.awaitWatchHello(firmware("v4.44.1-drew.6"))
        assertTrue(sent.isEmpty())
    }
}
