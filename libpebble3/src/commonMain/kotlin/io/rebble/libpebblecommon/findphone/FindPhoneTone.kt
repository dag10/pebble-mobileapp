package io.rebble.libpebblecommon.findphone

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.time.Duration.Companion.seconds

/** A bright two-note ping, repeated, as mono 16-bit PCM. */
object FindPhoneTone {
    const val SAMPLE_RATE = 44100
    const val PINGS = 5
    val PING_PERIOD = 1.seconds
    val DURATION = PING_PERIOD * PINGS

    private class Note(val frequency: Double, val startMs: Int, val decayMs: Double)

    private val notes = listOf(
        Note(frequency = 1760.0, startMs = 0, decayMs = 70.0),
        Note(frequency = 2637.0, startMs = 150, decayMs = 140.0),
    )
    private const val ATTACK_MS = 4.0
    private const val PEAK = 0.9

    fun pcm16(sampleRate: Int = SAMPLE_RATE): ShortArray {
        val periodSamples = (sampleRate * PING_PERIOD.inWholeMilliseconds / 1000).toInt()
        val period = ShortArray(periodSamples) { i ->
            val tMs = i * 1000.0 / sampleRate
            var value = 0.0
            for (note in notes) {
                val sinceStart = tMs - note.startMs
                if (sinceStart < 0) continue
                val envelope = minOf(1.0, sinceStart / ATTACK_MS) * exp(-sinceStart / note.decayMs)
                value += envelope * sin(2 * PI * note.frequency * sinceStart / 1000.0)
            }
            (value.coerceIn(-1.0, 1.0) * PEAK * Short.MAX_VALUE).roundToInt().toShort()
        }
        return ShortArray(periodSamples * PINGS) { period[it % periodSamples] }
    }

    fun wav(sampleRate: Int = SAMPLE_RATE): ByteArray = pcm16MonoWav(pcm16(sampleRate), sampleRate)
}

fun pcm16MonoWav(samples: ShortArray, sampleRate: Int): ByteArray {
    val dataSize = samples.size * 2
    val out = ByteArray(WAV_HEADER_SIZE + dataSize)
    var pos = 0
    fun ascii(s: String) = s.forEach { out[pos++] = it.code.toByte() }
    fun le(value: Int, bytes: Int) = repeat(bytes) { out[pos++] = (value shr (8 * it)).toByte() }

    ascii("RIFF"); le(WAV_HEADER_SIZE - 8 + dataSize, 4); ascii("WAVE")
    ascii("fmt "); le(16, 4); le(1, 2); le(1, 2)
    le(sampleRate, 4); le(sampleRate * 2, 4); le(2, 2); le(16, 2)
    ascii("data"); le(dataSize, 4)
    samples.forEach { le(it.toInt(), 2) }
    return out
}

private const val WAV_HEADER_SIZE = 44
