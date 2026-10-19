package coredevices.pebble.claude

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClaudeBridgeClientTest {
    @Test
    fun readsStatusRepliesAndPermissionsWithTheSecretAndCompanionHeader() = runTest {
        val bridge = FakeBridge()
        bridge.addReply("Working on it.", final = false)
        val done = bridge.addReply("Done.")
        bridge.permissions += BridgePermission(requestId = "qwert", tool = "Bash", description = "Run the tests", at = 5)

        val status = bridge.client.status(bridge.pairing)
        assertEquals(1_000_000L, status.now)
        assertEquals(BridgeSessionState("main", "Pebble Watch", done.id), status.state)

        val page = bridge.client.replies(bridge.pairing, after = 0, limit = 10)
        assertEquals(listOf("Working on it." to false, "Done." to true), page.replies.map { it.text to it.final })
        assertEquals(done.id, page.lastReplyId)

        assertEquals(listOf("qwert"), bridge.client.permissions(bridge.pairing).map { it.requestId })

        val request = bridge.requests.first()
        assertEquals("http://10.0.0.5:8742/v1/status", request.url.toString())
        assertEquals("drewmobileapp", request.headers[ClaudeBridgeClient.COMPANION_HEADER])
    }

    @Test
    fun aRejectedKeySaysToPairAgain() = runTest {
        val bridge = FakeBridge()
        val wrongKey = bridge.pairing.copy(secret = "0000000000000000")
        val e = assertFailsWith<ClaudeBridgeException> { bridge.client.status(wrongKey) }
        assertTrue(e.message!!.contains("Pair again"))
    }

    @Test
    fun fetchOnlyAsksForRepliesWhenThereAreNewOnes() = runTest {
        val bridge = FakeBridge()
        val old = bridge.addReply("Old.")
        val state = ClaudeAlertState(cursor = old.id)

        val quiet = fetchClaudeAlerts(bridge.client, bridge.pairing, now = { 1_000_000 }) { state }
        assertEquals(emptyList(), quiet.alerts)
        assertEquals(listOf("/v1/status", "/v1/permissions"), bridge.paths())

        bridge.requests.clear()
        val new = bridge.addReply("New.")
        val plan = fetchClaudeAlerts(bridge.client, bridge.pairing, now = { 1_000_000 }) { state }
        assertEquals(listOf("/v1/status", "/v1/replies", "/v1/permissions"), bridge.paths())
        assertEquals("after=${old.id}&limit=10", bridge.requests[1].url.encodedQuery)
        assertEquals(listOf(new.id), plan.alerts.map { (it as ClaudeAlert.Reply).reply.id })
        assertEquals(new.id, plan.state.cursor)
    }

    @Test
    fun fetchMeasuresTheBridgeClockAgainstThePhones() = runTest {
        // The bridge's clock is 30 s ahead. The app was open until 10 s before this check, phone time.
        val bridge = FakeBridge(now = 1_030_000)
        val state = ClaudeAlertState(cursor = 1000, seenUntilMs = 990_000)
        bridge.replies += BridgeReply(id = 1001, text = "Seen.", at = 1_019_000)
        bridge.replies += BridgeReply(id = 1002, text = "Not seen.", at = 1_021_000)
        val plan = fetchClaudeAlerts(bridge.client, bridge.pairing, now = { 1_000_000 }) { state }
        assertEquals(listOf("Not seen."), plan.alerts.map { (it as ClaudeAlert.Reply).reply.text })
    }
}
