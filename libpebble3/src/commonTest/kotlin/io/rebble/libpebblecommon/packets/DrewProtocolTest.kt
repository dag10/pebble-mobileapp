package io.rebble.libpebblecommon.packets

import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Wire bytes as the spec writes them, e.g. `44 52 45 57 01 00 02 | 07 00 00 00`. */
internal fun drewBytes(spec: String): UByteArray =
    spec.replace("|", " ").split(" ").filter { it.isNotBlank() }.map { it.toUByte(16) }.toUByteArray()

/** [drewBytes] with the Pebble Protocol header for endpoint 0xde01. */
internal fun drewPacket(spec: String): UByteArray {
    val payload = drewBytes(spec)
    return ubyteArrayOf((payload.size shr 8).toUByte(), payload.size.toUByte(), 0xDEu, 0x01u) + payload
}

internal class DrewProtocolTest {
    private val allFeatures = DrewFeatures.of(DrewFeature.entries)

    @Test
    fun watchHelloExample() {
        val example = drewPacket(
            "44 52 45 57 01 00 01 | 01 08 1f 00 00 00 00 00 00 00 0e 76 34 2e 34 34 2e 31 2d 64 72 65 77 2e 36"
        )
        val hello = assertIs<DrewHello.Watch>(PebblePacket.deserialize(example))
        assertTrue(hello.replyRequested)
        assertEquals(allFeatures, hello.features)
        assertEquals("v4.44.1-drew.6", hello.build.get())
        assertEquals(
            example.toList(),
            DrewHello.Watch(DrewHello.FLAG_REPLY_REQUESTED, allFeatures, "v4.44.1-drew.6").serialize().toList(),
        )
    }

    @Test
    fun phoneHelloExample() {
        assertEquals(
            drewPacket(
                "44 52 45 57 01 00 81 | 00 08 1f 00 00 00 00 00 00 00 0f 31 2e 31 36 2e 30 2e 34 2d 64 72 65 77 2e 33"
            ).toList(),
            DrewHello.Phone(features = allFeatures, build = "1.16.0.4-drew.3").serialize().toList(),
        )
    }

    @Test
    fun heartbeatExample() {
        val heartbeat = PebblePacket.deserialize(drewPacket("44 52 45 57 01 00 02 | 07 00 00 00"))
        assertEquals(7u, assertIs<DrewHeartbeat>(heartbeat).cookie.get())
        assertEquals(
            drewPacket("44 52 45 57 01 00 82 | 07 00 00 00").toList(),
            DrewHeartbeatAck(7u).serialize().toList(),
        )
    }

    @Test
    fun pingPhoneExamples() {
        assertIs<DrewFindPhone.Start>(PebblePacket.deserialize(drewPacket("44 52 45 57 01 02 01")))
        assertIs<DrewFindPhone.Stop>(PebblePacket.deserialize(drewPacket("44 52 45 57 01 02 02")))
        assertEquals(drewPacket("44 52 45 57 01 02 01").toList(), DrewFindPhone.Start().serialize().toList())
    }

    @Test
    fun trailingBytesAreIgnored() {
        val heartbeat = PebblePacket.deserialize(drewPacket("44 52 45 57 01 00 02 | 07 00 00 00 aa bb"))
        assertEquals(7u, assertIs<DrewHeartbeat>(heartbeat).cookie.get())

        val hello = PebblePacket.deserialize(drewPacket("44 52 45 57 01 00 01 | 00 01 05 00 ff"))
        assertEquals(DrewFeatures.of(DrewFeature.Updates, DrewFeature.FindPhone), assertIs<DrewHello.Watch>(hello).features)
    }

    @Test
    fun badFramesAreDropped() {
        val dropped = listOf(
            "44 52 45 57 01 00" to "shorter than the header",
            "44 52 45 58 01 00 02 | 07 00 00 00" to "magic",
            "00 01 01 02 03 04 05" to "magic",
            "44 52 45 57 02 00 02 | 07 00 00 00" to "frame version",
            "44 52 45 57 01 00 82 | 07 00 00 00" to "phone -> watch",
            "44 52 45 57 01 00 81 | 00 00 00" to "phone -> watch",
            "44 52 45 57 01 03 01" to "unknown service",
            "44 52 45 57 01 00 03" to "unknown command",
            "44 52 45 57 01 00 02 | 07 00 00" to "malformed",
            "44 52 45 57 01 00 01 | 00 08 1f 00" to "malformed",
            "44 52 45 57 01 00 01 | 00 00" to "malformed",
            "44 52 45 57 01 00 01 | 00 00 03 61 62" to "malformed",
        )
        for ((bytes, reason) in dropped) {
            val packet = assertIs<DrewPacket>(PebblePacket.deserialize(drewPacket(bytes)), bytes)
            assertTrue(reason in packet.reason, "$bytes: ${packet.reason}")
        }
    }

    @Test
    fun featureBitmapsOfAnyLengthUpTo32Bytes() {
        fun hello(bitmap: String): DrewHello.Watch {
            val length = drewBytes(bitmap).size.toString(16).padStart(2, '0')
            return assertIs(PebblePacket.deserialize(drewPacket("44 52 45 57 01 00 01 | 00 $length $bitmap 00")))
        }
        assertEquals(DrewFeatures.None, hello("").features)
        assertEquals(DrewFeatures.of(DrewFeature.Heartbeat, DrewFeature.Prefs), hello("12").features)
        assertEquals(DrewFeatures(0x8000_0000_0000_0001u), hello("01 00 00 00 00 00 00 80").features)
        val bitsAbove63 = "08" + " ff".repeat(31)
        assertEquals(DrewFeatures(0xffff_ffff_ffff_ff08u), hello(bitsAbove63).features)
        assertEquals(DrewFeatures.of(DrewFeature.NotificationFlags), DrewFeatures.fromBitmap(drewBytes("08 00 00 00")))

        val tooLong = "44 52 45 57 01 00 01 | 00 21" + " 00".repeat(33) + " 00"
        assertTrue("malformed" in assertIs<DrewPacket>(PebblePacket.deserialize(drewPacket(tooLong))).reason)
    }

    @Test
    fun featuresAreSentAsEightBytes() {
        assertEquals(
            drewBytes("1f 00 00 00 00 00 00 00").toList(),
            DrewFeatures.of(DrewFeature.entries).toBitmap().toList(),
        )
        assertEquals(drewBytes("00 00 00 00 00 00 00 80").toList(), DrewFeatures(1uL shl 63).toBitmap().toList())
    }

    @Test
    fun aFeatureIsInBothSets() {
        val watch = DrewFeatures.of(DrewFeature.Updates, DrewFeature.FindPhone, DrewFeature.Prefs)
        val phone = DrewFeatures.of(DrewFeature.Updates, DrewFeature.Heartbeat, DrewFeature.Prefs)
        val both = watch and phone
        assertTrue(DrewFeature.Updates in both)
        assertTrue(DrewFeature.Prefs in both)
        assertFalse(DrewFeature.FindPhone in both)
        assertFalse(DrewFeature.Heartbeat in both)
    }

    @Test
    fun longBuildsAreCut() {
        val hello = DrewHello.Phone(build = "x".repeat(100))
        assertEquals(DrewHello.MAX_BUILD_BYTES, hello.build.get().length)
    }
}
