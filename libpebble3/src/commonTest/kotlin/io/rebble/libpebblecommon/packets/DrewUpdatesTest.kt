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

    private fun header(command: Int) = drewBytes("44 52 45 57 01 01").toList() + command.toUByte()

    private fun ascii(text: String) = text.encodeToByteArray().map { it.toUByte() }

    @Test
    fun deserializeListRequest() {
        val packet = PebblePacket.deserialize(drewPacket("44 52 45 57 01 01 01 | 01"))
        assertTrue(assertIs<DrewUpdatesMessage.ListRequest>(packet).recheck)

        val cachedOnly = PebblePacket.deserialize(drewPacket("44 52 45 57 01 01 01 | 00"))
        assertFalse(assertIs<DrewUpdatesMessage.ListRequest>(cachedOnly).recheck)
    }

    @Test
    fun deserializeInstallRequest() {
        val packet = PebblePacket.deserialize(
            (bytes(0x00, 0x0F, 0xDE, 0x01) + header(0x02) + bytes(0x07) + ascii("fw:drew")).toUByteArray()
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
            bytes(0x00, 0x12, 0xDE, 0x01) + header(0x81) + bytes(
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
        val payload = header(0x82) + bytes(
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
            drewPacket("44 52 45 57 01 01 83 | 07 66 77 3a 64 72 65 77 02 2a 00").toList(),
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
        val decoded = DrewUpdatesMessage.ListEntry()
        PebblePacket.deserialize(packet.serialize().drop(4).toUByteArray(), decoded)
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
