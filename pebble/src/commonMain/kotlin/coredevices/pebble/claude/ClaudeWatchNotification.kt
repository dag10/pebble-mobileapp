package coredevices.pebble.claude

import io.rebble.libpebblecommon.database.entity.TimelineNotification
import io.rebble.libpebblecommon.database.entity.buildTimelineNotification
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The Claude watch app (apps/claude/package.json in pebble-workspace). */
val CLAUDE_APP_UUID = Uuid.parse("13910369-efcc-45de-a371-7ca92bcc6162")

const val CLAUDE_NOTIFICATION_TITLE = "Claude Code"

private const val REPLY_ITEM_KIND = 1L
private const val PERMISSION_ITEM_KIND = 2L
private const val ITEM_ID_HIGH = 0x13910369_efcc_4c1aL

/**
 * A watch notification for [alert], from the Claude app (its parent), with an action that opens
 * it. The item id comes from the reply or prompt, so a resend replaces the notification.
 */
fun claudeWatchNotification(alert: ClaudeAlert, timestamp: Instant): TimelineNotification =
    buildTimelineNotification(parentId = CLAUDE_APP_UUID, timestamp = timestamp) {
        itemID = alert.itemId()
        layout = TimelineItem.Layout.GenericNotification
        attributes {
            title { CLAUDE_NOTIFICATION_TITLE }
            when (alert) {
                is ClaudeAlert.Reply -> {
                    alert.sessionName?.takeIf { it.isNotBlank() }?.let { name -> subtitle { name } }
                    body { alert.reply.text.trim().ifEmpty { " " } }
                    tinyIcon { TimelineIcon.GenericSms }
                }
                is ClaudeAlert.Permission -> {
                    subtitle { "Allow ${alert.permission.tool.ifBlank { "a tool" }}?" }
                    body { alert.permission.body() }
                    tinyIcon { TimelineIcon.GenericQuestion }
                }
            }
        }
        actions {
            action(TimelineItem.Action.Type.OpenWatchapp) {
                attributes { title { "Open Claude" } }
            }
        }
    }

private fun BridgePermission.body(): String =
    listOf(description, preview).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
        .ifEmpty { "Claude is waiting for a permission answer." }

internal fun ClaudeAlert.itemId(): Uuid = when (this) {
    is ClaudeAlert.Reply -> Uuid.fromLongs(ITEM_ID_HIGH, (REPLY_ITEM_KIND shl 56) or reply.id)
    is ClaudeAlert.Permission -> Uuid.fromLongs(
        ITEM_ID_HIGH,
        (PERMISSION_ITEM_KIND shl 56) or (permission.requestId.hashCode().toLong() and 0xffffffffL),
    )
}
