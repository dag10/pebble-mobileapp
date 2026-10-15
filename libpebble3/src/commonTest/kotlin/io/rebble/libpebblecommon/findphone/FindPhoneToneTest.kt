package io.rebble.libpebblecommon.findphone

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FindPhoneToneTest {
    private fun ByteArray.le(offset: Int, bytes: Int): Int =
        (0 until bytes).sumOf { (this[offset + it].toInt() and 0xff) shl (8 * it) }

    private fun ByteArray.ascii(offset: Int, length: Int) =
        decodeToString(offset, offset + length)

    @Test
    fun pcmLastsTheWholeDuration() {
        val pcm = FindPhoneTone.pcm16(8000)
        assertEquals((8000 * FindPhoneTone.DURATION.inWholeMilliseconds / 1000).toInt(), pcm.size)
    }

    @Test
    fun pcmIsLoudWithoutClipping() {
        val peak = FindPhoneTone.pcm16(8000).maxOf { abs(it.toInt()) }
        assertTrue(peak > Short.MAX_VALUE * 0.6, "peak $peak")
        assertTrue(peak < Short.MAX_VALUE, "peak $peak")
    }

    @Test
    fun eachPingEndsInSilence() {
        val pcm = FindPhoneTone.pcm16(8000)
        val period = 8000 * FindPhoneTone.PING_PERIOD.inWholeMilliseconds.toInt() / 1000
        repeat(FindPhoneTone.PINGS) { ping ->
            val end = (ping + 1) * period
            // Below -50 dBFS, so the repeat doesn't click.
            assertTrue(pcm.sliceArray(end - 100 until end).all { abs(it.toInt()) < 100 })
        }
    }

    @Test
    fun wavHeaderDescribesMono16BitPcm() {
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE)
        val wav = pcm16MonoWav(samples, 22050)

        assertEquals(44 + samples.size * 2, wav.size)
        assertEquals("RIFF", wav.ascii(0, 4))
        assertEquals(wav.size - 8, wav.le(4, 4))
        assertEquals("WAVE", wav.ascii(8, 4))
        assertEquals("fmt ", wav.ascii(12, 4))
        assertEquals(16, wav.le(16, 4))
        assertEquals(1, wav.le(20, 2)) // PCM
        assertEquals(1, wav.le(22, 2)) // channels
        assertEquals(22050, wav.le(24, 4))
        assertEquals(22050 * 2, wav.le(28, 4))
        assertEquals(2, wav.le(32, 2))
        assertEquals(16, wav.le(34, 2))
        assertEquals("data", wav.ascii(36, 4))
        assertEquals(samples.size * 2, wav.le(40, 4))
    }

    @Test
    fun wavSamplesAreLittleEndian() {
        val wav = pcm16MonoWav(shortArrayOf(0x1234, -2), 8000)
        assertEquals(listOf(0x34, 0x12, 0xfe, 0xff), wav.drop(44).map { it.toInt() and 0xff })
    }
}
