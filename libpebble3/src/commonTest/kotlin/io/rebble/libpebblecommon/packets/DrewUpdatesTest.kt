package io.rebble.libpebblecommon.packets

import io.rebble.libpebblecommon.packets.DrewUpdatesMessage.InstallState
import io.rebble.libpebblecommon.packets.DrewUpdatesMessage.ListStatus
import io.rebble.libpebblecommon.packets.DrewUpdatesMessage.UpdateKind
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class DrewUpdatesTest {
    private fun bytes(vararg values: Int) = values.map { it.toUByte() }

    private fun ascii(text: String) = text.encodeToByteArray().map { it.toUByte() }

    @Test
    fun capabilityIsBit28() {
        val flags = ProtocolCapsFlag.makeFlags(listOf(ProtocolCapsFlag.SupportsDrewUpdates))
        assertEquals(bytes(0, 0, 0, 0x10, 0, 0, 0, 0), flags.toList())
        assertEquals(setOf(ProtocolCapsFlag.SupportsDrewUpdates), ProtocolCapsFlag.fromFlags(flags))
    }

    @Test
    fun deserializeListRequest() {
        val packet = PebblePacket.deserialize(
            ubyteArrayOf(0x00u, 0x02u, 0xDEu, 0x01u, 0x01u, 0x01u)
        )
        assertTrue(assertIs<DrewUpdatesMessage.ListRequest>(packet).recheck)

        val cachedOnly = PebblePacket.deserialize(
            ubyteArrayOf(0x00u, 0x02u, 0xDEu, 0x01u, 0x01u, 0x00u)
        )
        assertFalse(assertIs<DrewUpdatesMessage.ListRequest>(cachedOnly).recheck)
    }

    @Test
    fun deserializeInstallRequest() {
        val packet = PebblePacket.deserialize(
            (bytes(0x00, 0x09, 0xDE, 0x01, 0x02, 0x07) + ascii("fw:drew")).toUByteArray()
        )
        assertEquals("fw:drew", assertIs<DrewUpdatesMessage.InstallRequest>(packet).id.get())
    }

    @Test
    fun serializeListHeader() {
        val packet = DrewUpdatesMessage.ListHeader(
            listId = 0x0102u,
            status = ListStatus.Checking,
            entryCount = 2u,
            lastCheckedUtc = 0x6A000000u,
            message = "hi",
        )
        assertEquals(
            bytes(
                0x00, 0x0C, 0xDE, 0x01, // length 12, endpoint
                0x81, // command
                0x02, 0x01, // list id, little-endian
                0x01, // status: checking
                0x02, // entry count
                0x00, 0x00, 0x00, 0x6A, // last checked, little-endian
                0x02, 'h'.code, 'i'.code,
            ),
            packet.serialize().toList(),
        )
    }

    @Test
    fun serializeListEntry() {
        val packet = DrewUpdatesMessage.ListEntry(
            listId = 7u,
            index = 1u,
            kind = UpdateKind.Watchface,
            flags = DrewUpdatesMessage.ListEntry.FLAG_INSTALLING,
            sizeBytes = 446_858u,
            id = "app:1",
            name = "Q",
            currentVersion = "1.8",
            newVersion = "1.9",
            changelog = "• x",
        )
        val payload = bytes(
            0x82,
            0x07, 0x00, // list id
            0x01, // index
            0x01, // kind: watchface
            0x01, // flags: installing
            0x8A, 0xD1, 0x06, 0x00, // 446858, little-endian
        ) + bytes(5) + ascii("app:1") +
            bytes(1) + ascii("Q") +
            bytes(3) + ascii("1.8") +
            bytes(3) + ascii("1.9") +
            bytes(5, 0) + "• x".encodeToByteArray().map { it.toUByte() }
        assertEquals(bytes(0x00, payload.size, 0xDE, 0x01) + payload, packet.serialize().toList())
    }

    @Test
    fun serializeInstallStatus() {
        val packet = DrewUpdatesMessage.InstallStatus(
            id = "fw:drew",
            state = InstallState.Transferring,
            percent = 42u,
        )
        assertEquals(
            bytes(0x00, 0x0C, 0xDE, 0x01, 0x83, 0x07) + ascii("fw:drew") + bytes(0x02, 42, 0x00),
            packet.serialize().toList(),
        )
    }

    @Test
    fun listEntryRoundTrips() {
        val packet = DrewUpdatesMessage.ListEntry(
            listId = 3u,
            kind = UpdateKind.Firmware,
            flags = DrewUpdatesMessage.ListEntry.FLAG_REBOOTS,
            id = "fw:drew",
            name = "DrewPebbleOS",
            currentVersion = "v4.41.2-drew.0",
            newVersion = "v4.41.2-drew.1",
            changelog = "line\n".repeat(200),
        )
        val decoded = assertIs<DrewUpdatesMessage.ListEntry>(PebblePacket.deserialize(packet.serialize()))
        assertEquals("v4.41.2-drew.1", decoded.newVersion.get())
        assertEquals(DrewUpdatesMessage.MAX_CHANGELOG_BYTES, decoded.changelog.get().encodeToByteArray().size)
    }

    @Test
    fun stringsAreCutToTheirLimitsBetweenCodePoints() {
        val packet = DrewUpdatesMessage.ListEntry(name = "é".repeat(40))
        assertEquals("é".repeat(16), packet.name.get())
        assertEquals("ab", "ab€".utf8Prefix(4))
        assertEquals("ab€", "ab€".utf8Prefix(5))
    }
}
