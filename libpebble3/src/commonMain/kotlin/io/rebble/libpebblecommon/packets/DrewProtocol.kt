package io.rebble.libpebblecommon.packets

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.protocolhelpers.PacketRegistry
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.protocolhelpers.ProtocolEndpoint
import io.rebble.libpebblecommon.structmapper.SBytes
import io.rebble.libpebblecommon.structmapper.SString
import io.rebble.libpebblecommon.structmapper.SUByte
import io.rebble.libpebblecommon.structmapper.SUInt
import io.rebble.libpebblecommon.structmapper.SUnboundBytes
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.Endian

/*
 * DrewPebbleOS's private protocol on [ProtocolEndpoint.DREW]: every message starts with the
 * [DrewFrame] header, and each side announces its [DrewFeatures] in a [DrewHello]. Multi-byte
 * integers are little-endian. The spec is pebble-workspace's docs/plans/drewos-extension-protocol.md.
 */

enum class DrewService(val value: UByte) {
    Core(0x00u),
    Updates(0x01u),
    FindPhone(0x02u),
}

enum class DrewCoreCommand(val value: UByte) {
    WatchHello(0x01u),
    Heartbeat(0x02u),
    PhoneHello(0x81u),
    HeartbeatAck(0x82u),
}

/** Numbered independently of every upstream namespace. Never reuse a retired bit. */
enum class DrewFeature(val bit: Int) {
    Updates(0),
    Heartbeat(1),
    FindPhone(2),
    NotificationFlags(3),
    Prefs(4),
}

/** Bits without a [DrewFeature] are kept, so they show in logs. */
data class DrewFeatures(val bits: ULong = 0u) {
    operator fun contains(feature: DrewFeature): Boolean = (bits and feature.mask) != 0uL

    infix fun and(other: DrewFeatures) = DrewFeatures(bits and other.bits)

    /** Bit k is bit k % 8 of byte k / 8. */
    fun toBitmap(): UByteArray = UByteArray(ULong.SIZE_BYTES) { (bits shr (8 * it)).toUByte() }

    override fun toString(): String =
        "DrewFeatures(${DrewFeature.entries.filter { it in this }.joinToString()}; 0x${bits.toString(16)})"

    companion object {
        val None = DrewFeatures()
        const val MAX_BITMAP_BYTES = 32

        fun of(features: Iterable<DrewFeature>) = DrewFeatures(features.fold(0uL) { bits, it -> bits or it.mask })

        fun of(vararg features: DrewFeature) = of(features.asIterable())

        /** Missing bytes read as 0; bits from 64 up are ignored. */
        fun fromBitmap(bitmap: UByteArray): DrewFeatures {
            require(bitmap.size <= MAX_BITMAP_BYTES) { "Feature bitmap of ${bitmap.size} bytes" }
            return DrewFeatures(
                bitmap.take(ULong.SIZE_BYTES).foldIndexed(0uL) { i, bits, byte -> bits or (byte.toULong() shl (8 * i)) }
            )
        }
    }
}

private val DrewFeature.mask: ULong get() = 1uL shl bit

object DrewFrame {
    val MAGIC = "DREW".encodeToByteArray().asUByteArray()
    const val VERSION: UByte = 0x01u
    const val HEADER_SIZE = 7
    const val SERVICE_OFFSET = 5
    const val COMMAND_OFFSET = 6

    /** Set in commands sent phone -> watch, clear in those sent watch -> phone. */
    const val PHONE_TO_WATCH: UByte = 0x80u

    /** Why the phone must drop [bytes] (a message after its Pebble Protocol header) unparsed; null if it may parse it. */
    fun dropReason(bytes: UByteArray): String? = when {
        bytes.size < HEADER_SIZE -> "${bytes.size} bytes, shorter than the header"
        !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) ->
            "0xde01 message without the DREW magic; is something else using the endpoint?"
        bytes[MAGIC.size] != VERSION -> "frame version ${bytes[MAGIC.size]}"
        (bytes[COMMAND_OFFSET] and PHONE_TO_WATCH) != 0.toUByte() ->
            "phone -> watch command 0x${bytes[COMMAND_OFFSET].toString(16)} from the watch"
        else -> null
    }
}

open class DrewMessage(service: DrewService, command: UByte) : PebblePacket(ProtocolEndpoint.DREW) {
    val magic = SBytes(m, DrewFrame.MAGIC.size, DrewFrame.MAGIC)
    val frameVersion = SUByte(m, DrewFrame.VERSION)
    val service = SUByte(m, service.value)
    val command = SUByte(m, command)

    /** False if the fields parsed but hold values the spec doesn't allow. */
    internal open fun isWellFormed(): Boolean = true
}

/** A message from the watch on [ProtocolEndpoint.DREW] that was dropped, and why. */
class DrewPacket(val reason: String = "") : PebblePacket(ProtocolEndpoint.DREW) {
    val bytes = SUnboundBytes(m)
}

sealed class DrewHello(
    command: DrewCoreCommand,
    flags: UByte,
    features: DrewFeatures,
    build: String,
) : DrewMessage(DrewService.Core, command.value) {
    private val bitmap = features.toBitmap()

    val flags = SUByte(m, flags)
    val featuresLength = SUByte(m, bitmap.size.toUByte())
    val featuresBitmap = SBytes(m, default = bitmap).apply { linkWithSize(featuresLength) }

    /** For logs: the watch's firmware version tag, or the phone app's version. */
    val build = SString(m, build.utf8Prefix(MAX_BUILD_BYTES))

    val replyRequested: Boolean get() = (flags.get() and FLAG_REPLY_REQUESTED) != 0.toUByte()
    val features: DrewFeatures get() = DrewFeatures.fromBitmap(featuresBitmap.get())

    override fun isWellFormed() = featuresLength.get().toInt() <= DrewFeatures.MAX_BITMAP_BYTES

    override fun toString() =
        "${this::class.simpleName}(replyRequested=$replyRequested, $features, build=${build.get()})"

    class Watch(flags: UByte = 0u, features: DrewFeatures = DrewFeatures.None, build: String = "") :
        DrewHello(DrewCoreCommand.WatchHello, flags, features, build)

    class Phone(flags: UByte = 0u, features: DrewFeatures = DrewFeatures.None, build: String = "") :
        DrewHello(DrewCoreCommand.PhoneHello, flags, features, build)

    companion object {
        /** Answer with your own hello. An answer never sets it. */
        const val FLAG_REPLY_REQUESTED: UByte = 0x01u
        const val MAX_BUILD_BYTES = 63
    }
}

class DrewHeartbeat(cookie: UInt = 0u) : DrewMessage(DrewService.Core, DrewCoreCommand.Heartbeat.value) {
    val cookie = SUInt(m, cookie, Endian.Little)
}

class DrewHeartbeatAck(cookie: UInt = 0u) : DrewMessage(DrewService.Core, DrewCoreCommand.HeartbeatAck.value) {
    val cookie = SUInt(m, cookie, Endian.Little)
}

private val logger = Logger.withTag("DrewProtocol")

/** Every watch -> phone message, by (service, command). */
private val DECODERS: Map<Pair<UByte, UByte>, () -> DrewMessage> = mapOf(
    (DrewService.Core.value to DrewCoreCommand.WatchHello.value) to { DrewHello.Watch() },
    (DrewService.Core.value to DrewCoreCommand.Heartbeat.value) to { DrewHeartbeat() },
    (DrewService.Updates.value to DrewUpdatesMessage.Message.ListRequest.value) to { DrewUpdatesMessage.ListRequest() },
    (DrewService.Updates.value to DrewUpdatesMessage.Message.InstallRequest.value) to { DrewUpdatesMessage.InstallRequest() },
)

/** Never throws: a message it can't parse comes back as a [DrewPacket]. */
private fun decodeDrewPacket(packet: UByteArray): PebblePacket {
    val bytes = packet.copyOfRange(PEBBLE_PROTOCOL_HEADER_SIZE, packet.size)
    DrewFrame.dropReason(bytes)?.let { return dropped(it) }
    val service = bytes[DrewFrame.SERVICE_OFFSET]
    val command = bytes[DrewFrame.COMMAND_OFFSET]
    val message = DECODERS[service to command]?.invoke() ?: return dropped(
        if (DrewService.entries.none { it.value == service }) {
            "unknown service 0x${service.toString(16)}"
        } else {
            "unknown command 0x${command.toString(16)} of service 0x${service.toString(16)}"
        }
    )
    val parsed = try {
        message.m.fromBytes(DataBuffer(bytes))
        message.isWellFormed()
    } catch (e: Exception) {
        false
    }
    return if (parsed) message else dropped("malformed ${message::class.simpleName} (${bytes.size} bytes)")
}

private fun dropped(reason: String): DrewPacket {
    logger.w { "Dropped a message from the watch: $reason" }
    return DrewPacket(reason)
}

private const val PEBBLE_PROTOCOL_HEADER_SIZE = 4

fun drewPacketsRegister() {
    PacketRegistry.register(ProtocolEndpoint.DREW, ::decodeDrewPacket)
}
