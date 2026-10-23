package coredevices.pebble.actions

import io.rebble.libpebblecommon.database.entity.DrewNotificationFlags
import io.rebble.libpebblecommon.database.entity.TimelineNotification
import io.rebble.libpebblecommon.database.entity.buildTimelineNotification
import io.rebble.libpebblecommon.database.entity.drewNotificationFlags
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun watchNotification(
    title: String?,
    message: String?,
    flags: DrewNotificationFlags,
    timestamp: Instant,
): TimelineNotification {
    val titleText = title.orEmpty().ifEmpty { "Notification" }
    val bodyText = message.orEmpty().ifEmpty { " " }
    return buildTimelineNotification(parentId = Uuid.NIL, timestamp = timestamp) {
        layout = TimelineItem.Layout.GenericNotification
        attributes {
            title { titleText }
            body { bodyText }
            drewNotificationFlags(flags)
        }
    }
}
