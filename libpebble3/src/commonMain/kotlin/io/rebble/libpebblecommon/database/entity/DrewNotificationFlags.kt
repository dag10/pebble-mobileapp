package io.rebble.libpebblecommon.database.entity

import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute

/** DrewPebbleOS notification options, sent as [TimelineAttribute.DrewNotificationFlags]. */
data class DrewNotificationFlags(
    val critical: Boolean = false,
    val discreet: Boolean = false,
    val breakThroughQuietTime: Boolean = false,
) {
    fun toUByte(): UByte =
        ((if (critical) CRITICAL else 0) or
            (if (discreet) DISCREET else 0) or
            (if (breakThroughQuietTime) BREAK_QUIET_TIME else 0)).toUByte()

    companion object {
        private const val CRITICAL = 1 shl 0
        private const val DISCREET = 1 shl 1
        private const val BREAK_QUIET_TIME = 1 shl 2

        fun fromUByte(value: UByte): DrewNotificationFlags = DrewNotificationFlags(
            critical = value.toInt() and CRITICAL != 0,
            discreet = value.toInt() and DISCREET != 0,
            breakThroughQuietTime = value.toInt() and BREAK_QUIET_TIME != 0,
        )
    }
}

const val DISCREET_FALLBACK_TITLE = "Notification"
const val DISCREET_FALLBACK_BODY = "Discreet notification"

fun AttributesListBuilder.drewNotificationFlags(flags: DrewNotificationFlags) {
    if (flags != DrewNotificationFlags()) {
        uByte(TimelineAttribute.DrewNotificationFlags) { flags.toUByte() }
    }
}

private val DISCREET_CONTENT_ATTRIBUTES = setOf(
    TimelineAttribute.Title,
    TimelineAttribute.Subtitle,
    TimelineAttribute.Body,
    TimelineAttribute.ShortTitle,
    TimelineAttribute.ShortSubtitle,
    TimelineAttribute.LocationName,
    TimelineAttribute.Sender,
    TimelineAttribute.AppName,
    TimelineAttribute.Headings,
    TimelineAttribute.Paragraphs,
    TimelineAttribute.TinyIcon,
    TimelineAttribute.SmallIcon,
    TimelineAttribute.LargeIcon,
    TimelineAttribute.Icon,
    TimelineAttribute.ImageAspectRatio,
)

/**
 * A watch without [ProtocolCapsFlag.SupportsDrewNotificationFlags] can't hide a discreet
 * notification, so it gets placeholder content instead.
 */
internal fun List<BaseAttribute>.withDiscreetFallback(
    capabilities: Set<ProtocolCapsFlag>,
): List<BaseAttribute> {
    if (ProtocolCapsFlag.SupportsDrewNotificationFlags in capabilities) return this
    val flags = filterIsInstance<BaseAttribute.UByteAttribute>()
        .firstOrNull { it.attribute == TimelineAttribute.DrewNotificationFlags }
        ?.let { DrewNotificationFlags.fromUByte(it.value) }
    if (flags?.discreet != true) return this
    return filterNot { it.attribute in DISCREET_CONTENT_ATTRIBUTES } + listOf(
        BaseAttribute.TextAttribute(TimelineAttribute.Title, DISCREET_FALLBACK_TITLE),
        BaseAttribute.TextAttribute(TimelineAttribute.Body, DISCREET_FALLBACK_BODY),
    )
}
