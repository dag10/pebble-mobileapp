package coredevices.pebble.actions

import io.rebble.libpebblecommon.database.entity.BaseAttribute
import io.rebble.libpebblecommon.database.entity.DrewNotificationFlags
import io.rebble.libpebblecommon.database.entity.TimelineNotification
import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class WatchNotificationTest {
    private val now = Instant.fromEpochSeconds(1_760_000_000)

    private fun TimelineNotification.text(attribute: TimelineAttribute): String? =
        content.attributes.filterIsInstance<BaseAttribute.TextAttribute>()
            .firstOrNull { it.attribute == attribute }?.text

    private fun TimelineNotification.flags(): UByte? =
        content.attributes.filterIsInstance<BaseAttribute.UByteAttribute>()
            .firstOrNull { it.attribute == TimelineAttribute.DrewNotificationFlags }?.value

    @Test
    fun titleAndMessageAreSent() {
        val notification = watchNotification("Door", "Front door opened", DrewNotificationFlags(), now)
        assertEquals("Door", notification.text(TimelineAttribute.Title))
        assertEquals("Front door opened", notification.text(TimelineAttribute.Body))
        assertEquals(TimelineItem.Layout.GenericNotification, notification.content.layout)
        assertEquals(now, notification.content.timestamp.instant)
        assertNull(notification.flags())
    }

    @Test
    fun missingTitleOrMessageFallBackLikeTheOtherShortcuts() {
        val noTitle = watchNotification(null, "Just a message", DrewNotificationFlags(), now)
        assertEquals("Notification", noTitle.text(TimelineAttribute.Title))
        assertEquals("Just a message", noTitle.text(TimelineAttribute.Body))

        val noMessage = watchNotification("Just a title", "", DrewNotificationFlags(), now)
        assertEquals("Just a title", noMessage.text(TimelineAttribute.Title))
        assertEquals(" ", noMessage.text(TimelineAttribute.Body))
    }

    @Test
    fun flagsAreSentAsOneAttribute() {
        val flags = DrewNotificationFlags(critical = true, discreet = true, breakThroughQuietTime = true)
        assertEquals(0x07u.toUByte(), watchNotification("A", "B", flags, now).flags())
        assertEquals(
            0x01u.toUByte(),
            watchNotification("A", "B", DrewNotificationFlags(critical = true), now).flags(),
        )
    }
}
