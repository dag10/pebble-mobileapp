package coredevices.pebble.claude

import com.russhwolf.settings.MapSettings
import coredevices.util.AppResumed
import io.rebble.libpebblecommon.connection.FakeLibPebble
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

class ClaudeNotificationsTest {
    private val settings = MapSettings()
    private val bridge = FakeBridge()
    private val pairingString = "pclaude://10.0.0.5:8742/k7q2-m9xd-4hbt-wz3c"
    private val clock = object : Clock {
        override fun now() = Instant.fromEpochMilliseconds(bridge.now)
    }

    private fun notifications() = ClaudeNotifications(FakeLibPebble(), settings, AppResumed(), clock, bridge.client)

    @Test
    fun doesNothingUntilEnabledAndPaired() = runTest {
        val n = notifications()
        n.check()
        n.setEnabled(true)
        n.check()
        assertEquals(emptyList(), bridge.requests)
        assertEquals("That doesn't look like a pairing string. It should look like pclaude://host:port/xxxx-xxxx-xxxx-xxxx.", n.setPairing("nope"))
        assertNull(n.setPairing(pairingString))
        n.check()
        assertEquals(listOf("/v1/status", "/v1/permissions"), bridge.paths())
    }

    @Test
    fun notifiesNewRepliesOnceAcrossARelaunch() = runTest {
        bridge.addReply("From before it was on.")
        val n = notifications()
        n.setPairing(pairingString)
        n.setEnabled(true)
        n.check()
        assertEquals(0, n.data.value.alertCount)

        val reply = bridge.addReply("Build finished.")
        n.check()
        assertEquals(1, n.data.value.alertCount)
        assertEquals(reply.id, n.data.value.alerts.cursor)
        assertNotNull(n.data.value.lastAlertMs)

        // A relaunch reads the same state and doesn't notify again.
        val relaunched = notifications()
        relaunched.check()
        assertEquals(1, relaunched.data.value.alertCount)
        assertEquals(reply.id, relaunched.data.value.alerts.cursor)
    }

    @Test
    fun anUnreachableBridgeIsReportedAndCaughtUpLater() = runTest {
        val n = notifications()
        n.setPairing(pairingString)
        n.setEnabled(true)
        n.check()
        bridge.down = true
        bridge.addReply("While the bridge was unreachable.")
        n.check()
        assertEquals("Could not connect to 10.0.0.5", n.data.value.lastError)
        bridge.down = false
        n.check()
        assertNull(n.data.value.lastError)
        assertEquals(1, n.data.value.alertCount)
    }

    @Test
    fun turningItBackOnOrPairingAnotherBridgeStartsFromTheNewest() = runTest {
        val n = notifications()
        n.setPairing(pairingString)
        n.setEnabled(true)
        n.check()
        n.setEnabled(false)
        bridge.addReply("While it was off.")
        n.setEnabled(true)
        n.check()
        assertEquals(0, n.data.value.alertCount)

        // The same bridge typed differently keeps its place.
        val cursor = n.data.value.alerts.cursor
        n.setPairing("pclaude://10.0.0.5:8742/K7Q2M9XD4HBTWZ3C")
        assertEquals(cursor, n.data.value.alerts.cursor)
        n.setPairing("pclaude://10.0.0.6:8742/k7q2-m9xd-4hbt-wz3c")
        assertNull(n.data.value.alerts.cursor)
    }
}
