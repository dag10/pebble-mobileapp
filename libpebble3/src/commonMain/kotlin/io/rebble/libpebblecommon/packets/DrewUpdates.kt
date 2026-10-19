package io.rebble.libpebblecommon.packets

import io.rebble.libpebblecommon.protocolhelpers.PacketRegistry
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.protocolhelpers.ProtocolEndpoint
import io.rebble.libpebblecommon.structmapper.SLongString
import io.rebble.libpebblecommon.structmapper.SString
import io.rebble.libpebblecommon.structmapper.SUByte
import io.rebble.libpebblecommon.structmapper.SUInt
import io.rebble.libpebblecommon.structmapper.SUShort
import io.rebble.libpebblecommon.util.Endian

/**
 * DrewPebbleOS only: the watch's Settings > Updates page lists the updates the phone knows about
 * and asks it to install one. Spoken only between a phone and a watch that both announce
 * [ProtocolCapsFlag.SupportsDrewUpdates], and the phone only ever answers a watch request.
 * Multi-byte fields are little-endian. Strings are UTF-8 with a length prefix and no NUL: one byte
 * for [SString], two for the changelog. The spec is in pebble-workspace's docs/drew-os-extensions.md
 * ("Watch Updates protocol").
 */
open class DrewUpdatesMessage(message: Message) : PebblePacket(endpoint) {
    enum class Message(val value: UByte) {
        /** Watch -> phone: send the update list (and re-check if [ListRequest.FLAG_RECHECK]). */
        ListRequest(0x01u),
        /** Watch -> phone: install one entry, by id. */
        InstallRequest(0x02u),
        /** Phone -> watch: a list's status, followed by its entries. */
        ListHeader(0x81u),
        /** Phone -> watch: one entry of the list announced by the last [ListHeader]. */
        ListEntry(0x82u),
        /** Phone -> watch: how an install the watch asked for is going. */
        InstallStatus(0x83u),
    }

    val command = SUByte(m, message.value)

    init {
        type = command.get()
    }

    class ListRequest(flags: UByte = 0u) : DrewUpdatesMessage(Message.ListRequest) {
        val flags = SUByte(m, flags)

        val recheck: Boolean get() = (flags.get() and FLAG_RECHECK) != 0.toUByte()

        companion object {
            /** Also check online now; without it the phone only sends what it already knows. */
            const val FLAG_RECHECK: UByte = 0x01u
        }
    }

    class InstallRequest(id: String = "") : DrewUpdatesMessage(Message.InstallRequest) {
        val id = SString(m, id.utf8Prefix(MAX_ID_BYTES))
    }

    class ListHeader(
        listId: UShort = 0u,
        status: ListStatus = ListStatus.Ready,
        entryCount: UByte = 0u,
        lastCheckedUtc: UInt = 0u,
        message: String = "",
    ) : DrewUpdatesMessage(Message.ListHeader) {
        val listId = SUShort(m, listId, Endian.Little)
        val status = SUByte(m, status.value)
        val entryCount = SUByte(m, entryCount)
        /** Last successful check, UTC seconds; 0 if never. */
        val lastCheckedUtc = SUInt(m, lastCheckedUtc, Endian.Little)
        /** Short status detail for the watch to show, e.g. why a check failed; often empty. */
        val message = SString(m, message.utf8Prefix(MAX_MESSAGE_BYTES))
    }

    class ListEntry(
        listId: UShort = 0u,
        index: UByte = 0u,
        kind: UpdateKind = UpdateKind.Firmware,
        flags: UByte = 0u,
        sizeBytes: UInt = 0u,
        id: String = "",
        name: String = "",
        currentVersion: String = "",
        newVersion: String = "",
        changelog: String = "",
    ) : DrewUpdatesMessage(Message.ListEntry) {
        val listId = SUShort(m, listId, Endian.Little)
        val index = SUByte(m, index)
        val kind = SUByte(m, kind.value)
        val flags = SUByte(m, flags)
        /** Download size in bytes; 0 if unknown. */
        val sizeBytes = SUInt(m, sizeBytes, Endian.Little)
        val id = SString(m, id.utf8Prefix(MAX_ID_BYTES))
        val name = SString(m, name.utf8Prefix(MAX_NAME_BYTES))
        val currentVersion = SString(m, currentVersion.utf8Prefix(MAX_VERSION_BYTES))
        val newVersion = SString(m, newVersion.utf8Prefix(MAX_VERSION_BYTES))
        val changelog = SLongString(m, changelog.utf8Prefix(MAX_CHANGELOG_BYTES), Endian.Little)

        companion object {
            /** An install of this entry is running. */
            const val FLAG_INSTALLING: UByte = 0x01u
            /** Installing it reboots the watch (firmware). */
            const val FLAG_REBOOTS: UByte = 0x02u
            /** Official PebbleOS: installing it replaces DrewPebbleOS, so warn first. */
            const val FLAG_NOT_DREW: UByte = 0x04u
        }
    }

    class InstallStatus(
        id: String = "",
        state: InstallState = InstallState.Starting,
        /** 0-100, or [PERCENT_UNKNOWN]. */
        percent: UByte = PERCENT_UNKNOWN,
        message: String = "",
    ) : DrewUpdatesMessage(Message.InstallStatus) {
        val id = SString(m, id.utf8Prefix(MAX_ID_BYTES))
        val state = SUByte(m, state.value)
        val percent = SUByte(m, percent)
        val message = SString(m, message.utf8Prefix(MAX_MESSAGE_BYTES))

        companion object {
            const val PERCENT_UNKNOWN: UByte = 0xffu
        }
    }

    enum class UpdateKind(val value: UByte) {
        Firmware(0u),
        Watchface(1u),
        Watchapp(2u),
    }

    enum class ListStatus(val value: UByte) {
        /** The list is the result of the last check, which succeeded. */
        Ready(0u),
        /** The phone's cached list; a check is running and a fresh list follows. */
        Checking(1u),
        /** The last check failed (see the message); the list is the last one known. */
        CheckFailed(2u),
    }

    enum class InstallState(val value: UByte) {
        /** The phone accepted the request. */
        Starting(0u),
        /** The phone is downloading it. */
        Downloading(1u),
        /** The phone is sending it to the watch; the percent is set when known. */
        Transferring(2u),
        /** Installed. A fresh list follows. */
        Done(3u),
        /** Not installed; the message says why. */
        Failed(4u),
        /** Firmware sent; the watch reboots into it now. Nothing follows. */
        Rebooting(5u),
    }

    companion object {
        val endpoint = ProtocolEndpoint.DREW_UPDATES

        const val MAX_ID_BYTES = 48
        const val MAX_NAME_BYTES = 32
        const val MAX_VERSION_BYTES = 32
        const val MAX_MESSAGE_BYTES = 64
        const val MAX_CHANGELOG_BYTES = 512
    }
}

/** The longest prefix of this string that is at most [maxBytes] of UTF-8, cut between code points. */
fun String.utf8Prefix(maxBytes: Int): String {
    val bytes = encodeToByteArray()
    if (bytes.size <= maxBytes) {
        return this
    }
    var end = maxBytes
    // Back up over continuation bytes (10xxxxxx) to the start of the cut code point.
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) {
        end--
    }
    return bytes.decodeToString(0, end)
}

fun drewUpdatesPacketsRegister() {
    PacketRegistry.register(DrewUpdatesMessage.endpoint, DrewUpdatesMessage.Message.ListRequest.value) {
        DrewUpdatesMessage.ListRequest()
    }
    PacketRegistry.register(DrewUpdatesMessage.endpoint, DrewUpdatesMessage.Message.InstallRequest.value) {
        DrewUpdatesMessage.InstallRequest()
    }
    PacketRegistry.register(DrewUpdatesMessage.endpoint, DrewUpdatesMessage.Message.ListHeader.value) {
        DrewUpdatesMessage.ListHeader()
    }
    PacketRegistry.register(DrewUpdatesMessage.endpoint, DrewUpdatesMessage.Message.ListEntry.value) {
        DrewUpdatesMessage.ListEntry()
    }
    PacketRegistry.register(DrewUpdatesMessage.endpoint, DrewUpdatesMessage.Message.InstallStatus.value) {
        DrewUpdatesMessage.InstallStatus()
    }
}
