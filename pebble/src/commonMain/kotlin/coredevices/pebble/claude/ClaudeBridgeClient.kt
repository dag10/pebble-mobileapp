package coredevices.pebble.claude

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BridgeSessionState(
    val session: String? = null,
    val name: String? = null,
    @SerialName("last_reply_id") val lastReplyId: Long = 0,
)

@Serializable
data class BridgeStatus(val now: Long, val state: BridgeSessionState)

@Serializable
data class BridgeReply(
    val id: Long,
    val text: String,
    /** Bridge clock, ms. */
    val at: Long,
    val final: Boolean = true,
)

@Serializable
data class BridgeReplies(
    val replies: List<BridgeReply> = emptyList(),
    @SerialName("last_reply_id") val lastReplyId: Long = 0,
)

@Serializable
data class BridgePermission(
    @SerialName("request_id") val requestId: String,
    val session: String? = null,
    val tool: String = "",
    val description: String = "",
    val preview: String = "",
    /** Bridge clock, ms. */
    val at: Long = 0,
)

@Serializable
private data class BridgePermissions(val permissions: List<BridgePermission> = emptyList())

class ClaudeBridgeException(message: String) : Exception(message)

/** The Claude bridge's phone API (apps/claude/bridge in pebble-workspace), for the default session. */
class ClaudeBridgeClient(
    private val httpClient: HttpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 8_000
            connectTimeoutMillis = 5_000
        }
    },
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun status(pairing: ClaudePairing): BridgeStatus = get(pairing, "/v1/status")

    suspend fun replies(pairing: ClaudePairing, after: Long, limit: Int): BridgeReplies =
        get(pairing, "/v1/replies?after=$after&limit=$limit")

    suspend fun permissions(pairing: ClaudePairing): List<BridgePermission> =
        get<BridgePermissions>(pairing, "/v1/permissions").permissions

    private suspend inline fun <reified T> get(pairing: ClaudePairing, path: String): T {
        val response = httpClient.get(pairing.url + path) {
            header(HttpHeaders.Authorization, "Bearer ${pairing.secret}")
            header(COMPANION_HEADER, COMPANION_NAME)
        }
        when {
            response.status == HttpStatusCode.Unauthorized ->
                throw ClaudeBridgeException("The bridge rejected the key. Pair again.")
            !response.status.isSuccess() ->
                throw ClaudeBridgeException("The bridge answered ${response.status.value} to $path.")
        }
        return json.decodeFromString(response.bodyAsText())
    }

    companion object {
        /** Keeps these requests out of the bridge's list of watches (bridge/src/companion.js). */
        const val COMPANION_HEADER = "X-Pebble-Companion"
        const val COMPANION_NAME = "drewmobileapp"
    }
}
