package coredevices.pebble.claude

/**
 * Where the Claude bridge on the Mac listens, from the pairing string its `bridge pair` prints
 * (the same one the Claude watch app's settings take): `pclaude://host:port/xxxx-xxxx-xxxx-xxxx`.
 */
data class ClaudePairing(val host: String, val port: Int, val secret: String) {
    val url: String get() = "http://$host:$port"

    /** For display, with the secret masked. */
    val description: String get() = "$host:$port (key ${secret.take(4)}…)"

    companion object {
        const val DEFAULT_PORT = 8742

        private val PAIRING = Regex(
            """^(?:(?:pclaude|https?)://)?(\[[0-9a-fA-F:.]+]|[^/:?#@\[\]]+)(?::(\d{1,5}))?/+([^/?#]+)/*$""",
            RegexOption.IGNORE_CASE,
        )
        private val SECRET = Regex("^[0-9a-hjkmnp-tv-z]{16}$")

        /**
         * Parses a pasted pairing string the way the watch app's PebbleKit JS does
         * (apps/claude/src/pkjs/pairing.js): case, dashes and spaces don't matter, and i, l
         * and o read as 1, 1 and 0.
         */
        fun parse(input: String): Result<ClaudePairing> {
            val text = input.filterNot { it.isWhitespace() }
            if (text.isEmpty()) {
                return failure("Paste the pairing string from `bridge pair` on your Mac.")
            }
            val match = PAIRING.matchEntire(text)
                ?: return failure("That doesn't look like a pairing string. It should look like pclaude://host:port/xxxx-xxxx-xxxx-xxxx.")
            val (host, portText, rawSecret) = match.destructured
            val port = if (portText.isEmpty()) DEFAULT_PORT else portText.toInt()
            if (port !in 1..65535) {
                return failure("The port in the pairing string is out of range.")
            }
            val secret = rawSecret.lowercase()
                .filterNot { it == '-' || it == '_' || it == '.' }
                .replace('i', '1').replace('l', '1').replace('o', '0')
            if (!SECRET.matches(secret)) {
                return failure("The secret at the end should be 16 letters and digits, like k7q2-m9xd-4hbt-wz3c.")
            }
            return Result.success(ClaudePairing(host.lowercase(), port, secret))
        }

        private fun failure(message: String) = Result.failure<ClaudePairing>(IllegalArgumentException(message))
    }
}
