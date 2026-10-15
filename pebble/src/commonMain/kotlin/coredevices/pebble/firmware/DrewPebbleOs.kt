package coredevices.pebble.firmware

import co.touchlab.kermit.Logger
import coredevices.pebble.services.HttpClientAuthType
import coredevices.pebble.services.PebbleHttpClient
import coredevices.pebble.services.PebbleHttpClient.Companion.get
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.drewBuildNumber
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Checks the latest public DrewPebbleOS GitHub release for a build matching the watch. */
class DrewPebbleOsUpdateCheck(
    private val httpClient: PebbleHttpClient,
    private val clock: Clock = Clock.System,
) {
    private val logger = Logger.withTag("DrewPebbleOsUpdateCheck")
    private val mutex = Mutex()
    private var cached: Pair<GitHubRelease, Instant>? = null

    suspend fun checkForUpdate(watch: WatchInfo, force: Boolean): FirmwareUpdateCheckResult {
        val release = latestRelease(force)
            ?: return FirmwareUpdateCheckResult.UpdateCheckFailed("Failed to check for DrewPebbleOS update")
        return drewPebbleOsUpdate(release, watch.platform.revision, watch.runningFwVersion)
    }

    private suspend fun latestRelease(force: Boolean): GitHubRelease? = mutex.withLock {
        val now = clock.now()
        cached?.takeIf { !force && it.second > now }?.let { return it.first }
        val release: GitHubRelease? = httpClient.get(LATEST_RELEASE_URL, auth = HttpClientAuthType.None)
        if (release == null) {
            logger.i { "Couldn't fetch latest DrewPebbleOS release" }
        } else {
            cached = release to now + CACHE_TTL
        }
        release
    }

    companion object {
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/dag10/PebbleOS/releases/latest"
        private val CACHE_TTL: Duration = 15.minutes
    }
}

fun drewPebbleOsAssetName(platformRevision: String, tag: String): String =
    "normal_${platformRevision}_$tag.pbz"

/**
 * Offers [release] when the watch runs anything other than DrewPebbleOS, or an older DrewPebbleOS
 * build. Installing it may go backwards from official firmware, so it's always allowed to downgrade.
 */
fun drewPebbleOsUpdate(
    release: GitHubRelease,
    platformRevision: String,
    running: FirmwareVersion,
): FirmwareUpdateCheckResult {
    val asset = release.assets.find { it.name == drewPebbleOsAssetName(platformRevision, release.tagName) }
        ?: return FirmwareUpdateCheckResult.FoundNoUpdate
    val releaseDrewBuild = drewBuildNumber(release.tagName)
        ?: return FirmwareUpdateCheckResult.FoundNoUpdate
    val version = FirmwareVersion.from(
        tag = release.tagName,
        isRecovery = false,
        gitHash = "",
        timestamp = Instant.DISTANT_PAST,
        isDualSlot = false, // not used from here
        isSlot0 = false, // not used from here
    ) ?: return FirmwareUpdateCheckResult.FoundNoUpdate
    val runningDrewBuild = drewBuildNumber(running.stringVersion)
    val versionComparison = compareValuesBy(version, running, { it.major }, { it.minor }, { it.patch })
    val isNewer = running.isRecovery || runningDrewBuild == null || versionComparison > 0 ||
            (versionComparison == 0 && releaseDrewBuild > runningDrewBuild)
    if (!isNewer) {
        return FirmwareUpdateCheckResult.FoundNoUpdate
    }
    return FirmwareUpdateCheckResult.FoundUpdate(
        version = version,
        url = asset.browserDownloadUrl,
        notes = release.body.orEmpty(),
        canDowngrade = true,
    )
}

@Serializable
data class GitHubRelease(
    @SerialName("tag_name")
    val tagName: String,
    val body: String? = null,
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

@Serializable
data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
)
