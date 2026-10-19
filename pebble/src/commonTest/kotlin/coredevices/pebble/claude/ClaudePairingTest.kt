package coredevices.pebble.claude

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClaudePairingTest {
    private fun parsed(input: String) = ClaudePairing.parse(input).getOrThrow()
    private fun error(input: String) = ClaudePairing.parse(input).exceptionOrNull()?.message.orEmpty()

    @Test
    fun parsesWhatBridgePairPrints() {
        assertEquals(
            ClaudePairing("192.168.1.20", 8742, "k7q2m9xd4hbtwz3c"),
            parsed("pclaude://192.168.1.20:8742/k7q2-m9xd-4hbt-wz3c"),
        )
        assertEquals("http://192.168.1.20:8742", parsed("pclaude://192.168.1.20:8742/k7q2-m9xd-4hbt-wz3c").url)
    }

    @Test
    fun acceptsTheFormsTheWatchAppAccepts() {
        assertEquals("http://drews-mbp.tail1234.ts.net:9000", parsed("PCLAUDE://Drews-MBP.tail1234.ts.net:9000/K7Q2-M9XD-4HBT-WZ3C").url)
        assertEquals(ClaudePairing.DEFAULT_PORT, parsed("10.0.0.5/k7q2m9xd4hbtwz3c").port)
        assertEquals("http://[fd7a:115c::1]:8742", parsed("http://[fd7a:115c::1]:8742/k7q2-m9xd-4hbt-wz3c/").url)
        assertEquals("k7q2m9xd4hbtwz3c", parsed(" pclaude://h:1/k7q2 m9xd 4hbt wz3c\n").secret)
        // Look-alikes typed in: i and l read as 1, o as 0.
        assertEquals("1100m9xd4hbtwz3c", parsed("pclaude://h:1/iloo-m9xd-4hbt-wz3c").secret)
    }

    @Test
    fun rejectsBadInputWithAReadableError() {
        assertTrue(error("").startsWith("Paste the pairing string"))
        assertTrue(error("pclaude://host:8742").startsWith("That doesn't look like a pairing string"))
        assertTrue(error("pclaude://host:70000/k7q2-m9xd-4hbt-wz3c").contains("out of range"))
        assertTrue(error("pclaude://host:8742/k7q2-m9xd").contains("16 letters and digits"))
        assertTrue(error("pclaude://host:8742/k7q2-m9xd-4hbt-wz3u").contains("16 letters and digits"))
    }

    @Test
    fun describesWithTheSecretMasked() {
        assertEquals("10.0.0.5:8742 (key k7q2…)", parsed("pclaude://10.0.0.5:8742/k7q2-m9xd-4hbt-wz3c").description)
    }
}
