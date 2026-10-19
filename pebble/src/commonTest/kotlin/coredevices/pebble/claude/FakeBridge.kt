package coredevices.pebble.claude

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/** A bridge's phone API on a MockEngine, serving whatever the test puts in it. */
class FakeBridge(var now: Long = 1_000_000) {
    var name: String? = "Pebble Watch"
    val replies = mutableListOf<BridgeReply>()
    val permissions = mutableListOf<BridgePermission>()
    var down = false
    val requests = mutableListOf<HttpRequestData>()

    val pairing = ClaudePairing("10.0.0.5", 8742, "k7q2m9xd4hbtwz3c")

    val client = ClaudeBridgeClient(
        HttpClient(MockEngine { request ->
            requests += request
            if (down) {
                throw IllegalStateException("Could not connect to 10.0.0.5")
            }
            if (request.headers[HttpHeaders.Authorization] != "Bearer ${pairing.secret}") {
                return@MockEngine respond("""{"ok":false,"error":"unauthorized"}""", HttpStatusCode.Unauthorized, jsonHeaders)
            }
            val lastId = replies.maxOfOrNull { it.id } ?: 0
            val body = when (request.url.encodedPath) {
                "/v1/status" -> """{"ok":true,"now":$now,"cursor":"b.1","state":{"session":"main",""" +
                    (name?.let { "\"name\":\"$it\"," } ?: "") +
                    """"connected":true,"ready":true,"status":"idle","last_reply_id":$lastId,"reply":null}}"""
                "/v1/replies" -> {
                    val after = request.url.parameters["after"]!!.toLong()
                    val limit = request.url.parameters["limit"]!!.toInt()
                    val page = replies.filter { it.id > after }.takeLast(limit).joinToString(",") {
                        """{"id":${it.id},"prev":0,"text":"${it.text}","at":${it.at},"final":${it.final},"reply_to":null}"""
                    }
                    """{"ok":true,"now":$now,"session":"main","replies":[$page],"truncated":false,"last_reply_id":$lastId}"""
                }
                "/v1/permissions" -> """{"ok":true,"permissions":[""" + permissions.joinToString(",") {
                    """{"request_id":"${it.requestId}","session":"main","tool":"${it.tool}","description":"${it.description}","preview":"","at":${it.at}}"""
                } + "]}"
                else -> return@MockEngine respond("""{"ok":false}""", HttpStatusCode.NotFound, jsonHeaders)
            }
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }),
    )

    fun addReply(text: String, final: Boolean = true): BridgeReply {
        val reply = BridgeReply(id = (replies.maxOfOrNull { it.id } ?: 1000) + 1, text = text, at = now, final = final)
        replies += reply
        return reply
    }

    fun paths() = requests.map { it.url.encodedPath }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
}
