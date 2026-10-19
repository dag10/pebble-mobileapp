package coredevices.pebble.services

import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.packets.DrewUpdatesMessage
import io.rebble.libpebblecommon.packets.DrewUpdatesMessage.UpdateKind
import io.rebble.libpebblecommon.packets.utf8Prefix
import io.rebble.libpebblecommon.services.FirmwareVersion
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** One row of DrewPebbleOS's Settings > Updates list. */
@Serializable
data class WatchUpdateEntry(
    val id: String,
    val kind: WatchUpdateKind,
    val name: String,
    val currentVersion: String,
    val newVersion: String,
    val changelog: String,
    val sizeBytes: Long = 0,
    val notDrew: Boolean = false,
)

@Serializable
enum class WatchUpdateKind(val wire: UpdateKind) {
    Firmware(UpdateKind.Firmware),
    Watchface(UpdateKind.Watchface),
    Watchapp(UpdateKind.Watchapp),
}

@Serializable
data class WatchUpdateList(
    val entries: List<WatchUpdateEntry> = emptyList(),
    /** UTC seconds of the last check that succeeded; 0 if none has. */
    val lastCheckedUtc: Long = 0,
    /** Why the last check failed; null if it succeeded. */
    val error: String? = null,
)

/** A sideloaded [DREW_APPS] app with a different build released. */
data class InstalledAppUpdate(
    val update: DrewAppUpdate,
    val title: String,
    val type: AppType,
    val installedVersion: String?,
)

const val DREW_OS_UPDATE_ID = "fw:drew"
const val OFFICIAL_OS_UPDATE_ID = "fw:official"
private const val APP_UPDATE_ID_PREFIX = "app:"

fun appUpdateId(uuid: Uuid): String = "$APP_UPDATE_ID_PREFIX$uuid"

fun appUuidFromUpdateId(id: String): Uuid? =
    id.takeIf { it.startsWith(APP_UPDATE_ID_PREFIX) }?.removePrefix(APP_UPDATE_ID_PREFIX)
        ?.let { Uuid.parseOrNull(it) }

/**
 * The list after a check. [official] is only passed when it's newer than DrewPebbleOS. If the
 * DrewPebbleOS check failed, its entry from [previous] is kept and the list reports the failure.
 */
fun buildWatchUpdateList(
    previous: WatchUpdateList,
    drewOs: FirmwareUpdateCheckResult?,
    drewOsSizeBytes: Long,
    official: FirmwareUpdateCheckResult.FoundUpdate?,
    apps: List<InstalledAppUpdate>,
    running: FirmwareVersion,
    now: Instant,
): WatchUpdateList {
    val failed = drewOs is FirmwareUpdateCheckResult.UpdateCheckFailed
    val drewOsEntry = when (drewOs) {
        is FirmwareUpdateCheckResult.FoundUpdate -> drewOsUpdateEntry(drewOs, running, drewOsSizeBytes)
        is FirmwareUpdateCheckResult.UpdateCheckFailed -> previous.entries.find { it.id == DREW_OS_UPDATE_ID }
        else -> null
    }
    return WatchUpdateList(
        entries = listOfNotNull(drewOsEntry, official?.let { officialOsUpdateEntry(it, running) }) +
            apps.map { appUpdateEntry(it) }.sortedBy { it.name.lowercase() },
        lastCheckedUtc = if (failed) previous.lastCheckedUtc else now.epochSeconds,
        error = if (failed) "Couldn't check DrewPebbleOS" else null,
    )
}

fun drewOsUpdateEntry(
    update: FirmwareUpdateCheckResult.FoundUpdate,
    running: FirmwareVersion,
    sizeBytes: Long,
) = WatchUpdateEntry(
    id = DREW_OS_UPDATE_ID,
    kind = WatchUpdateKind.Firmware,
    name = "DrewPebbleOS",
    currentVersion = running.stringVersion,
    newVersion = update.version.stringVersion,
    changelog = watchChangelog(drewOsNotesSince(update.notes, running.gitHash)),
    sizeBytes = sizeBytes,
)

fun officialOsUpdateEntry(update: FirmwareUpdateCheckResult.FoundUpdate, running: FirmwareVersion) =
    WatchUpdateEntry(
        id = OFFICIAL_OS_UPDATE_ID,
        kind = WatchUpdateKind.Firmware,
        name = "PebbleOS (official)",
        currentVersion = running.stringVersion,
        newVersion = update.version.stringVersion,
        changelog = watchChangelog(update.notes),
        notDrew = true,
    )

fun appUpdateEntry(app: InstalledAppUpdate) = WatchUpdateEntry(
    id = appUpdateId(app.update.app.uuid),
    kind = if (app.type == AppType.Watchface) WatchUpdateKind.Watchface else WatchUpdateKind.Watchapp,
    name = app.title,
    currentVersion = app.installedVersion.orEmpty(),
    newVersion = releaseVersionLabel(app.update.name),
    changelog = watchChangelog(app.update.notes),
    sizeBytes = app.update.size,
)

fun WatchUpdateEntry.toPacket(listId: UShort, index: Int, installing: Boolean): DrewUpdatesMessage.ListEntry {
    var flags = 0
    if (installing) flags = flags or DrewUpdatesMessage.ListEntry.FLAG_INSTALLING.toInt()
    if (kind == WatchUpdateKind.Firmware) flags = flags or DrewUpdatesMessage.ListEntry.FLAG_REBOOTS.toInt()
    if (notDrew) flags = flags or DrewUpdatesMessage.ListEntry.FLAG_NOT_DREW.toInt()
    return DrewUpdatesMessage.ListEntry(
        listId = listId,
        index = index.toUByte(),
        kind = kind.wire,
        flags = flags.toUByte(),
        sizeBytes = sizeBytes.coerceIn(0, UInt.MAX_VALUE.toLong()).toUInt(),
        id = id,
        name = name,
        currentVersion = currentVersion,
        newVersion = newVersion,
        changelog = changelog,
    )
}

private val RELEASE_VERSION = Regex("""\d+(\.\d+)+(\s*\([0-9a-f]{7,40}\))?""")

/** "1.81.0 (e13610b)" from a release name like "QuartzPlus 1.81.0 (e13610b)". */
fun releaseVersionLabel(releaseName: String): String =
    RELEASE_VERSION.find(releaseName)?.value ?: releaseName

private val COMMIT_BULLET = Regex("""^\s*[-*+]\s+(.*?)\s*\(([0-9a-f]{7,40})\)\s*$""")

/**
 * A DrewPebbleOS release lists every drew commit, oldest first, each ending in its hash. Keeps the
 * ones after the running build's commit when it's among them, newest first.
 */
fun drewOsNotesSince(notes: String, runningGitHash: String): String {
    val bullets = notes.lines().mapNotNull { COMMIT_BULLET.find(it) }
        .map { it.groupValues[1] to it.groupValues[2] }
    if (bullets.isEmpty()) {
        return notes
    }
    val running = runningGitHash.lowercase()
    val runningIndex = if (running.length >= 7) {
        bullets.indexOfLast { (_, hash) -> hash.startsWith(running) || running.startsWith(hash) }
    } else {
        -1
    }
    val (header, shown) = if (runningIndex >= 0) {
        "New since your build:" to bullets.drop(runningIndex + 1)
    } else {
        "Changes, newest first:" to bullets
    }
    if (shown.isEmpty()) {
        return notes
    }
    return (listOf(header) + shown.asReversed().map { "- ${it.first}" }).joinToString("\n")
}

private val TRAILER = Regex("""^(Co-Authored-By|Claude-Session|Signed-off-by):""", RegexOption.IGNORE_CASE)
private val HEADING = Regex("""^#{1,6}\s+""")
private val BULLET = Regex("""^\s*[-*+]\s+""")
private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
private val TRAILING_COMMIT = Regex("""\s*\([0-9a-f]{7,40}\)$""")
private const val ELLIPSIS = "…"

/**
 * Release notes as plain text that fits a watch entry: markdown and commit trailers removed,
 * the release's build boilerplate dropped, cut to [maxBytes] of UTF-8 with an ellipsis.
 */
fun watchChangelog(notes: String, maxBytes: Int = DrewUpdatesMessage.MAX_CHANGELOG_BYTES): String {
    val paragraphs = notes.replace("\r\n", "\n").replace('\r', '\n')
        .split(Regex("""\n\s*\n"""))
        .map { paragraph ->
            paragraph.lines().filterNot { TRAILER.containsMatchIn(it.trim()) }.map { it.toPlainLine() }
                .filter { it.isNotBlank() }
        }
        .filter { it.isNotEmpty() }
    val kept = if (paragraphs.firstOrNull()?.any { "built from " in it || it.startsWith("Sideload ") || " Sideload " in it } == true) {
        paragraphs.drop(1)
    } else {
        paragraphs
    }
    val text = kept.joinToString("\n\n") { it.joinToString("\n") }
    if (text.encodeToByteArray().size <= maxBytes) {
        return text
    }
    return text.utf8Prefix(maxBytes - ELLIPSIS.encodeToByteArray().size).trimEnd() + ELLIPSIS
}

private fun String.toPlainLine(): String {
    var line = trimEnd()
    line = HEADING.replace(line, "")
    line = BULLET.replaceFirst(line, "• ")
    line = LINK.replace(line, "$1")
    line = line.replace("**", "").replace("`", "")
    line = TRAILING_COMMIT.replace(line, "")
    return line
}
