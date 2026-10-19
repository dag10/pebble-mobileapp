package coredevices.pebble.claude

import kotlin.test.Test
import kotlin.test.assertEquals

class ClaudeAlertPlannerTest {
    private fun reply(id: Long, at: Long = 10_000 + id, final: Boolean = true) =
        BridgeReply(id = id, text = "reply $id", at = at, final = final)

    private fun permission(id: String, at: Long = 20_000) =
        BridgePermission(requestId = id, tool = "Bash", description = "Run $id", at = at)

    private fun snapshot(
        replies: List<BridgeReply> = emptyList(),
        permissions: List<BridgePermission> = emptyList(),
        lastReplyId: Long = replies.maxOfOrNull { it.id } ?: 0,
        clockOffsetMs: Long = 0,
    ) = ClaudeBridgeSnapshot(clockOffsetMs, "Pebble Watch", lastReplyId, replies, permissions)

    private fun ClaudeAlertPlan.replyIds() = alerts.filterIsInstance<ClaudeAlert.Reply>().map { it.reply.id }
    private fun ClaudeAlertPlan.permissionIds() = alerts.filterIsInstance<ClaudeAlert.Permission>().map { it.permission.requestId }

    @Test
    fun firstCheckOnlyNotesTheNewestReply() {
        val plan = planClaudeAlerts(ClaudeAlertState(), snapshot(lastReplyId = 500))
        assertEquals(emptyList(), plan.alerts)
        assertEquals(500L, plan.state.cursor)
    }

    @Test
    fun notifiesNewFinalRepliesInOrderAndSkipsProgressNotes() {
        val plan = planClaudeAlerts(
            ClaudeAlertState(cursor = 100),
            snapshot(listOf(reply(99), reply(101, final = false), reply(102), reply(103))),
        )
        assertEquals(listOf(102L, 103L), plan.replyIds())
        assertEquals(103L, plan.state.cursor)
        assertEquals("Pebble Watch", (plan.alerts.first() as ClaudeAlert.Reply).sessionName)
    }

    @Test
    fun aProgressNoteAloneMovesTheCursorWithoutNotifying() {
        val plan = planClaudeAlerts(ClaudeAlertState(cursor = 100), snapshot(listOf(reply(101, final = false))))
        assertEquals(emptyList(), plan.alerts)
        assertEquals(101L, plan.state.cursor)
    }

    @Test
    fun theSameRepliesAreNotNotifiedTwice() {
        val first = planClaudeAlerts(ClaudeAlertState(cursor = 100), snapshot(listOf(reply(101))))
        assertEquals(listOf(101L), first.replyIds())
        val again = planClaudeAlerts(first.state, snapshot(listOf(reply(101))))
        assertEquals(emptyList(), again.alerts)
    }

    @Test
    fun atMostTheNewestFewRepliesAreNotified() {
        val plan = planClaudeAlerts(ClaudeAlertState(cursor = 100), snapshot((101L..108L).map { reply(it) }))
        assertEquals(listOf(106L, 107L, 108L), plan.replyIds())
        assertEquals(MAX_REPLY_ALERTS, plan.alerts.size)
        assertEquals(108L, plan.state.cursor)
    }

    @Test
    fun repliesThatArrivedWhileTheWatchAppWasOpenCountAsShown() {
        // The bridge's clock runs 5 s ahead of the phone's; the app was open until phone time 10_103.
        val state = ClaudeAlertState(cursor = 100, seenUntilMs = 10_103)
        val plan = planClaudeAlerts(
            state,
            snapshot(
                listOf(reply(101, at = 15_100), reply(102, at = 15_103), reply(103, at = 15_104)),
                clockOffsetMs = 5_000,
            ),
        )
        assertEquals(listOf(103L), plan.replyIds())
        assertEquals(103L, plan.state.cursor)
    }

    @Test
    fun aBridgeWithOlderIdsStartsOverFromItsNewest() {
        val plan = planClaudeAlerts(ClaudeAlertState(cursor = 900), snapshot(lastReplyId = 40))
        assertEquals(emptyList(), plan.alerts)
        assertEquals(40L, plan.state.cursor)
    }

    @Test
    fun permissionPromptsAreNotifiedOnceEachAndForgottenWhenAnswered() {
        val first = planClaudeAlerts(
            ClaudeAlertState(cursor = 0),
            snapshot(permissions = listOf(permission("bbbbb", at = 20_002), permission("aaaaa", at = 20_001))),
        )
        assertEquals(listOf("aaaaa", "bbbbb"), first.permissionIds())
        assertEquals(listOf("aaaaa", "bbbbb"), first.state.notifiedPermissions.sorted())

        val second = planClaudeAlerts(
            first.state,
            snapshot(permissions = listOf(permission("bbbbb", at = 20_002), permission("ccccc", at = 20_003))),
        )
        assertEquals(listOf("ccccc"), second.permissionIds())
        assertEquals(listOf("bbbbb", "ccccc"), second.state.notifiedPermissions)
    }

    @Test
    fun promptsAreNotifiedOnTheFirstCheckButNotWhenTheWatchAppShowedThem() {
        val fresh = planClaudeAlerts(ClaudeAlertState(), snapshot(permissions = listOf(permission("aaaaa"))))
        assertEquals(listOf("aaaaa"), fresh.permissionIds())

        val seen = planClaudeAlerts(
            ClaudeAlertState(cursor = 0, seenUntilMs = 20_000),
            snapshot(permissions = listOf(permission("aaaaa", at = 20_000))),
        )
        assertEquals(emptyList(), seen.alerts)
    }

    @Test
    fun keepsSeenUntil() {
        val plan = planClaudeAlerts(ClaudeAlertState(cursor = 1, seenUntilMs = 42), snapshot(listOf(reply(2))))
        assertEquals(42L, plan.state.seenUntilMs)
    }
}
