package coredevices.pebble.claude

import kotlinx.serialization.Serializable

/** What has been handled so far, persisted so a relaunch doesn't notify again. */
@Serializable
data class ClaudeAlertState(
    /** The newest reply id handled (notified or skipped). Null until the first check. */
    val cursor: Long? = null,
    /** Pending permission prompts already notified. */
    val notifiedPermissions: List<String> = emptyList(),
    /**
     * Phone clock, ms: when the Claude watch app was last in the foreground. Its PebbleKit JS
     * showed whatever reached the bridge before then.
     */
    val seenUntilMs: Long = 0,
)

/** One check's view of the bridge. */
data class ClaudeBridgeSnapshot(
    /** Bridge clock minus phone clock. */
    val clockOffsetMs: Long,
    val sessionName: String?,
    val lastReplyId: Long,
    /** Replies after the state's cursor, oldest first. */
    val replies: List<BridgeReply>,
    val permissions: List<BridgePermission>,
)

sealed interface ClaudeAlert {
    data class Reply(val reply: BridgeReply, val sessionName: String?) : ClaudeAlert
    data class Permission(val permission: BridgePermission) : ClaudeAlert
}

data class ClaudeAlertPlan(val alerts: List<ClaudeAlert>, val state: ClaudeAlertState)

/** At most this many replies are notified from one check, the newest. */
const val MAX_REPLY_ALERTS = 3

/**
 * Decides which new final replies and permission prompts to notify about. Anything that
 * reached the bridge while the Claude watch app was in the foreground counts as shown. The
 * first check only notes where the replies are.
 */
fun planClaudeAlerts(state: ClaudeAlertState, snapshot: ClaudeBridgeSnapshot): ClaudeAlertPlan {
    fun shown(bridgeAtMs: Long) = bridgeAtMs - snapshot.clockOffsetMs <= state.seenUntilMs

    val cursor = state.cursor
    val replyAlerts: List<ClaudeAlert>
    val newCursor: Long
    if (cursor == null || snapshot.lastReplyId < cursor) {
        replyAlerts = emptyList()
        newCursor = snapshot.lastReplyId
    } else {
        val newer = snapshot.replies.filter { it.id > cursor }
        replyAlerts = newer
            .filter { it.final && !shown(it.at) }
            .takeLast(MAX_REPLY_ALERTS)
            .map { ClaudeAlert.Reply(it, snapshot.sessionName) }
        newCursor = maxOf(cursor, newer.maxOfOrNull { it.id } ?: cursor)
    }

    val pendingIds = snapshot.permissions.map { it.requestId }.toSet()
    val permissionAlerts = snapshot.permissions
        .filter { it.requestId !in state.notifiedPermissions && !shown(it.at) }
        .sortedBy { it.at }
        .map { ClaudeAlert.Permission(it) }
    val notified = state.notifiedPermissions.filter { it in pendingIds } +
        permissionAlerts.map { it.permission.requestId }

    return ClaudeAlertPlan(
        alerts = replyAlerts + permissionAlerts,
        state = state.copy(cursor = newCursor, notifiedPermissions = notified),
    )
}
