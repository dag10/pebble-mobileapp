package io.rebble.libpebblecommon.packets

import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute
import io.rebble.libpebblecommon.protocolhelpers.ProtocolEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fails when a rebase gives a Drew id an upstream meaning, or the reverse. */
internal class DrewNamespaceTest {
    private fun <T, V> assertUnique(entries: List<T>, value: (T) -> V) {
        val clashes = entries.groupBy(value).filterValues { it.size > 1 }
        assertTrue(clashes.isEmpty(), "Values used twice: $clashes")
    }

    @Test
    fun upstreamNumberingsHaveUniqueValues() {
        assertUnique(ProtocolCapsFlag.entries) { it.value }
        assertUnique(SystemMessage.Message.entries) { it.value }
        assertUnique(TimelineAttribute.entries) { it.id }
        assertUnique(ProtocolEndpoint.entries) { it.value }
    }

    @Test
    fun drewNumberingsHaveUniqueValues() {
        assertUnique(DrewFeature.entries) { it.bit }
        assertUnique(DrewService.entries) { it.value }
    }

    @Test
    fun noDrewCapabilityBitsOrSystemMessages() {
        assertEquals(emptyList(), ProtocolCapsFlag.entries.filter { "Drew" in it.name })
        assertEquals(emptyList(), SystemMessage.Message.entries.filter { "Drew" in it.name })
    }

    @Test
    fun onlyDrewTimelineAttributesUseTheDrewBlock() {
        val drewBlock = 0xD0u..0xDFu
        assertEquals(
            TimelineAttribute.entries.filter { it.name.startsWith("Drew") },
            TimelineAttribute.entries.filter { it.id.toUInt() in drewBlock },
        )
    }
}
