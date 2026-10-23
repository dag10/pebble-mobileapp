package io.rebble.libpebblecommon.database.entity

import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.database.dao.ValueParams
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.util.DataBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class DrewNotificationFlagsTest {
    private val firmware = FirmwareVersion(
        stringVersion = "v4.41.2-drew.3",
        timestamp = Instant.DISTANT_PAST,
        major = 4,
        minor = 41,
        patch = 2,
        suffix = "drew.3",
        gitHash = "",
        isRecovery = false,
        isDualSlot = true,
        isSlot0 = true,
    )

    private val drewWatch = setOf(
        ProtocolCapsFlag.SupportsUnknownTimelineAttributes,
        ProtocolCapsFlag.SupportsDrewNotificationFlags,
    )
    private val stockWatch = setOf(ProtocolCapsFlag.SupportsUnknownTimelineAttributes)

    private fun notification(flags: DrewNotificationFlags) = buildTimelineNotification(
        parentId = Uuid.NIL,
        timestamp = Instant.fromEpochSeconds(1_760_000_000),
    ) {
        attributes {
            title { "Bank" }
            subtitle { "Card ending 1234" }
            body { "Your code is 123456" }
            tinyIcon { TimelineIcon.GenericSms }
            drewNotificationFlags(flags)
        }
    }

    private fun sentAttributes(
        item: TimelineNotification,
        capabilities: Set<ProtocolCapsFlag>,
    ): Map<UByte, UByteArray> {
        val params = ValueParams(
            platform = WatchType.EMERY,
            capabilities = capabilities,
            firmwareVersion = firmware,
            libPebbleConfigFlow = LibPebbleConfigFlow(MutableStateFlow(LibPebbleConfig())),
        )
        val decoded = TimelineItem()
        decoded.fromBytes(DataBuffer(item.value(params)!!))
        return decoded.attributes.list.associate { it.attributeId.get() to it.content.get() }
    }

    private fun Map<UByte, UByteArray>.text(attribute: TimelineAttribute): String? =
        this[attribute.id]?.toByteArray()?.decodeToString()

    @Test
    fun flagsRoundTripThroughTheirByte() {
        val all = DrewNotificationFlags(critical = true, discreet = true, breakThroughQuietTime = true)
        assertEquals(0x07u.toUByte(), all.toUByte())
        assertEquals(0x01u.toUByte(), DrewNotificationFlags(critical = true).toUByte())
        assertEquals(0x02u.toUByte(), DrewNotificationFlags(discreet = true).toUByte())
        assertEquals(0x04u.toUByte(), DrewNotificationFlags(breakThroughQuietTime = true).toUByte())
        assertEquals(all, DrewNotificationFlags.fromUByte(0x07u))
        assertEquals(DrewNotificationFlags(), DrewNotificationFlags.fromUByte(0u))
    }

    @Test
    fun noFlagsAddsNoAttribute() {
        val sent = sentAttributes(notification(DrewNotificationFlags()), drewWatch)
        assertNull(sent[TimelineAttribute.DrewNotificationFlags.id])
    }

    @Test
    fun drewWatchGetsTheFlagsAndTheRealContent() {
        val flags = DrewNotificationFlags(critical = true, discreet = true)
        val sent = sentAttributes(notification(flags), drewWatch)
        assertEquals(listOf(0x03u.toUByte()), sent[TimelineAttribute.DrewNotificationFlags.id]?.toList())
        assertEquals("Bank", sent.text(TimelineAttribute.Title))
        assertEquals("Your code is 123456", sent.text(TimelineAttribute.Body))
    }

    @Test
    fun stockWatchGetsAPlaceholderForADiscreetNotification() {
        val sent = sentAttributes(notification(DrewNotificationFlags(discreet = true)), stockWatch)
        assertEquals(DISCREET_FALLBACK_TITLE, sent.text(TimelineAttribute.Title))
        assertEquals(DISCREET_FALLBACK_BODY, sent.text(TimelineAttribute.Body))
        assertNull(sent[TimelineAttribute.Subtitle.id])
        assertNull(sent[TimelineAttribute.TinyIcon.id])
        assertNull(sent[TimelineAttribute.DrewNotificationFlags.id])
    }

    @Test
    fun stockWatchGetsTheContentWithoutTheFlagsOtherwise() {
        val flags = DrewNotificationFlags(critical = true, breakThroughQuietTime = true)
        val sent = sentAttributes(notification(flags), stockWatch)
        assertEquals("Bank", sent.text(TimelineAttribute.Title))
        assertEquals("Card ending 1234", sent.text(TimelineAttribute.Subtitle))
        assertEquals("Your code is 123456", sent.text(TimelineAttribute.Body))
        assertNull(sent[TimelineAttribute.DrewNotificationFlags.id])
    }
}
