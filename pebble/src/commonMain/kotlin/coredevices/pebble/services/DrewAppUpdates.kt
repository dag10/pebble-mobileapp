package coredevices.pebble.services

import co.touchlab.kermit.Logger
import coredevices.pebble.firmware.GitHubRelease
import coredevices.util.CommonBuildKonfig
import coredevices.util.sha256
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.util.getTempFilePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val logger = Logger.withTag("DrewAppUpdates")

/** One of the owner's private apps, attached under a fixed name to its repo's latest release. */
data class DrewApp(val uuid: Uuid, val repo: String, val assetName: String)

val DREW_APPS = listOf(
    DrewApp(Uuid.parse("bdf7a544-1545-4eba-8edc-029ca30ef8d3"), "dag10/Quartz-Private", "QuartzPlus.pbw"),
    DrewApp(Uuid.parse("c64aae42-b822-4d6d-995b-bc7c38e843f4"), "dag10/pebble-timer-private", "Timer.pbw"),
    DrewApp(Uuid.parse("14bd9cd3-14dc-4b81-bd58-2f1fb9b57978"), "dag10/pebble-stopwatch-private", "Stopwatch.pbw"),
    DrewApp(Uuid.parse("13910369-efcc-45de-a371-7ca92bcc6162"), "dag10/pebble-claude", "Claude.pbw"),
)

data class DrewAppUpdate(
    val app: DrewApp,
    /** The release's name, e.g. "QuartzPlus 1.81.0 (92d4e9e)". */
    val name: String,
    val notes: String,
    val assetUrl: String,
    val sha256: String,
    val size: Long = 0,
)

private const val SHA256_PREFIX = "sha256:"

/**
 * Offers [release]'s build of [app] when it isn't the installed pbw. Builds often share a
 * version, so they're told apart by content.
 */
fun drewAppUpdate(app: DrewApp, release: GitHubRelease, installedSha256: String): DrewAppUpdate? {
    val asset = release.assets.find { it.name == app.assetName } ?: return null
    val sha256 = asset.digest?.takeIf { it.startsWith(SHA256_PREFIX) }
        ?.removePrefix(SHA256_PREFIX)?.lowercase()
        ?: return null
    if (sha256 == installedSha256 || asset.url.isEmpty()) {
        return null
    }
    return DrewAppUpdate(
        app = app,
        name = release.name ?: release.tagName,
        notes = release.body.orEmpty(),
        assetUrl = asset.url,
        sha256 = sha256,
        size = asset.size,
    )
}

/** Updates sideloaded [DREW_APPS] from their private GitHub releases. */
class DrewAppUpdates(
    private val json: Json,
    private val libPebble: LibPebble,
    private val appContext: AppContext,
    private val clock: Clock,
    private val token: String? = CommonBuildKonfig.DREW_GITHUB_RELEASES_TOKEN,
) {
    private val client = HttpClient {
        // The asset url redirects to signed storage, which mustn't get the token.
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
        }
    }
    private val releaseMutex = Mutex()
    private val installMutex = Mutex()
    private val releases = mutableMapOf<String, Pair<GitHubRelease, Instant>>()

    /** Null unless [uuid] is a sideloaded [DREW_APPS] app with a different build released. */
    suspend fun checkForUpdate(uuid: Uuid, force: Boolean): DrewAppUpdate? {
        val app = DREW_APPS.find { it.uuid == uuid } ?: return null
        if (token == null) {
            return null
        }
        val installed = installedSha256(app) ?: return null
        val release = latestRelease(app.repo, force) ?: return null
        return drewAppUpdate(app, release, installed)
    }

    /** [onDownloaded] runs once the pbw is downloaded, before it goes to the watch. */
    suspend fun install(update: DrewAppUpdate, onDownloaded: suspend () -> Unit = {}): Boolean = installMutex.withLock {
        if (installedSha256(update.app) == update.sha256) {
            return true
        }
        val path = getTempFilePath(appContext, update.app.assetName)
        try {
            if (!download(update, path)) {
                return false
            }
            onDownloaded()
            val running = libPebble.watches.value.filterIsInstance<ConnectedPebbleDevice>()
                .any { it.runningApp.value == update.app.uuid }
            logger.i { "Installing ${update.name} (running=$running)" }
            libPebble.updateSideloadedApp(path, loadOnWatch = running)
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
        }
    }

    suspend fun installAvailableUpdates() {
        DREW_APPS.forEach { app ->
            val update = checkForUpdate(app.uuid, force = true) ?: return@forEach
            install(update)
        }
    }

    private suspend fun installedSha256(app: DrewApp): String? {
        val path = libPebble.sideloadedPbwPath(app.uuid) ?: return null
        return withContext(Dispatchers.IO) {
            sha256(SystemFileSystem.source(path).buffered().use { it.readByteArray() }).toHexString()
        }
    }

    private suspend fun latestRelease(repo: String, force: Boolean): GitHubRelease? = releaseMutex.withLock {
        val now = clock.now()
        releases[repo]?.takeIf { !force && it.second > now }?.let { return it.first }
        val release = fetch(
            what = "latest release of $repo",
            request = {
                client.get("https://api.github.com/repos/$repo/releases/latest") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.Accept, "application/vnd.github+json")
                }
            },
            read = { json.decodeFromString<GitHubRelease>(it.bodyAsText()) },
        )
        if (release != null) {
            releases[repo] = release to now + RELEASE_CACHE_TTL
        }
        release
    }

    private suspend fun download(update: DrewAppUpdate, to: Path): Boolean {
        val bytes = fetch(
            what = update.name,
            request = {
                val asset = client.get(update.assetUrl) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.Accept, ContentType.Application.OctetStream.toString())
                }
                asset.headers[HttpHeaders.Location]?.let { client.get(it) } ?: asset
            },
            read = { it.bodyAsBytes() },
        ) ?: return false
        if (sha256(bytes).toHexString() != update.sha256) {
            logger.w { "Downloaded ${update.name} doesn't match its digest" }
            return false
        }
        SystemFileSystem.sink(to).buffered().use { it.write(bytes) }
        return true
    }

    private suspend fun <T> fetch(
        what: String,
        request: suspend () -> HttpResponse,
        read: suspend (HttpResponse) -> T,
    ): T? = try {
        val response = request()
        if (response.status.isSuccess()) {
            read(response)
        } else {
            logger.w { "Failed to fetch $what: ${response.status}" }
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(e) { "Failed to fetch $what" }
        null
    }

    companion object {
        private val RELEASE_CACHE_TTL: Duration = 15.minutes
    }
}
