package coredevices.pebble.claude

import io.rebble.libpebblecommon.database.entity.BaseAttribute
import io.rebble.libpebblecommon.database.entity.TimelineNotification
import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ClaudeWatchNotificationTest {
    private val now = Instant.fromEpochMilliseconds(1_800_000_000_000)

    private fun TimelineNotification.text(attribute: TimelineAttribute) =
        content.attributes.filterIsInstance<BaseAttribute.TextAttribute>().firstOrNull { it.attribute == attribute }?.text

    private fun reply(id: Long, text: String = "All tests pass.", session: String? = "Pebble Watch") =
        ClaudeAlert.Reply(BridgeReply(id = id, text = text, at = 0), session)

    @Test
    fun aReplyComesFromTheClaudeAppAndCanOpenIt() {
        val notification = claudeWatchNotification(reply(1234), now)
        assertEquals(CLAUDE_APP_UUID, notification.content.parentId)
        assertEquals(TimelineItem.Layout.GenericNotification, notification.content.layout)
        assertEquals("Claude Code", notification.text(TimelineAttribute.Title))
        assertEquals("Pebble Watch", notification.text(TimelineAttribute.Subtitle))
        assertEquals("All tests pass.", notification.text(TimelineAttribute.Body))
        val action = notification.content.actions.single()
        assertEquals(TimelineItem.Action.Type.OpenWatchapp, action.type)
        assertEquals("Open Claude", (action.attributes.single() as BaseAttribute.TextAttribute).text)
    }

    @Test
    fun aReplyWithoutASessionNameHasNoSubtitle() {
        assertNull(claudeWatchNotification(reply(1, session = null), now).text(TimelineAttribute.Subtitle))
        assertNull(claudeWatchNotification(reply(1, session = " "), now).text(TimelineAttribute.Subtitle))
    }

    @Test
    fun aPermissionPromptNamesTheToolAndWhatItWouldDo() {
        val alert = ClaudeAlert.Permission(
            BridgePermission(requestId = "abcde", tool = "Bash", description = "Run the tests", preview = "npm test"),
        )
        val notification = claudeWatchNotification(alert, now)
        assertEquals("Claude Code", notification.text(TimelineAttribute.Title))
        assertEquals("Allow Bash?", notification.text(TimelineAttribute.Subtitle))
        assertEquals("Run the tests\n\nnpm test", notification.text(TimelineAttribute.Body))
        assertEquals(TimelineItem.Action.Type.OpenWatchapp, notification.content.actions.single().type)
    }

    @Test
    fun itemIdsAreStablePerReplyOrPromptAndDistinct() {
        assertEquals(claudeWatchNotification(reply(7), now).itemId, claudeWatchNotification(reply(7, "edited"), now).itemId)
        assertNotEquals(claudeWatchNotification(reply(7), now).itemId, claudeWatchNotification(reply(8), now).itemId)
        val prompt = ClaudeAlert.Permission(BridgePermission(requestId = "abcde"))
        assertEquals(prompt.itemId(), ClaudeAlert.Permission(BridgePermission(requestId = "abcde")).itemId())
        assertNotEquals(prompt.itemId(), ClaudeAlert.Permission(BridgePermission(requestId = "abcdf")).itemId())
        assertNotEquals(prompt.itemId(), reply(7).itemId())
    }
}
