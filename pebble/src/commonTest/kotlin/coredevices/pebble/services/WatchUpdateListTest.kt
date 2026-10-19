package coredevices.pebble.services

import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.packets.DrewUpdatesMessage
import io.rebble.libpebblecommon.packets.DrewUpdatesMessage.UpdateKind
import io.rebble.libpebblecommon.services.FirmwareVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class WatchUpdateListTest {
    private val now = Instant.fromEpochSeconds(1_800_000_000)
    private val quartz = DREW_APPS.first { it.assetName == "QuartzPlus.pbw" }
    private val timer = DREW_APPS.first { it.assetName == "Timer.pbw" }

    private fun version(tag: String, gitHash: String = "") = FirmwareVersion.from(
        tag = tag,
        isRecovery = false,
        gitHash = gitHash,
        timestamp = Instant.fromEpochSeconds(1_700_000_000),
        isDualSlot = true,
        isSlot0 = true,
    )!!

    private val running = version("v4.41.2-drew.0", gitHash = "a323bdf7")

    private val drewOsNotes = """
        DrewPebbleOS v4.41.2-drew.1 for Pebble Time 2 (obelix@pvt). Sideload `normal_obelix_pvt_v4.41.2-drew.1.pbz` (slots 0 and 1).

        Changes since v4.41.2:

        - fw/apps/settings: add Ping Phone to Settings > Bluetooth (fdaca4928)
        - fw/services/ping: add a heartbeat ping for DrewMobileApp (a323bdf78)
        - fw/comm/ble: handle the iOS app launch read response (2c8dceaac)
        - fw/apps/settings: add an Updates page (1234567ab)
    """.trimIndent()

    private val drewOsUpdate = FirmwareUpdateCheckResult.FoundUpdate(
        version = version("v4.41.2-drew.1"),
        url = "https://example.com/normal_obelix_pvt_v4.41.2-drew.1.pbz",
        notes = drewOsNotes,
        canDowngrade = true,
    )

    private fun appUpdate(app: DrewApp, title: String, type: AppType, notes: String = "notes") = InstalledAppUpdate(
        update = DrewAppUpdate(
            app = app,
            name = "$title 1.81.0 (e13610b)",
            notes = notes,
            assetUrl = "https://api.github.com/x",
            sha256 = "0".repeat(64),
            size = 446_858,
        ),
        title = title,
        type = type,
        installedVersion = "1.81",
    )

    @Test
    fun drewOsNotesKeepWhatIsNewerThanTheRunningBuildNewestFirst() {
        assertEquals(
            """
            New since your build:
            - fw/apps/settings: add an Updates page
            - fw/comm/ble: handle the iOS app launch read response
            """.trimIndent(),
            drewOsNotesSince(drewOsNotes, "a323bdf7"),
        )
    }

    @Test
    fun drewOsNotesListEverythingNewestFirstWhenTheRunningBuildIsUnknown() {
        val notes = drewOsNotesSince(drewOsNotes, "deadbeef")
        assertEquals("Changes, newest first:", notes.lines().first())
        assertEquals("- fw/apps/settings: add an Updates page", notes.lines()[1])
        assertEquals("- fw/apps/settings: add Ping Phone to Settings > Bluetooth", notes.lines().last())
        assertEquals("plain notes", drewOsNotesSince("plain notes", "a323bdf7"))
    }

    @Test
    fun changelogDropsBoilerplateMarkdownAndTrailers() {
        val notes = """
            QuartzPlus 1.81.0, built from e13610b on `main` (Pebble SDK 4.33.1).

            ## update Quartz text display one-liners to the **bottom** line

            A message now shows on the [bottom line](https://example.com).

            Changes:
            - Draw "No media playing" on the bottom line
            * Update `README` (abc1234)

            Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
            Claude-Session: https://claude.ai/code/session_1
        """.trimIndent()
        assertEquals(
            """
            update Quartz text display one-liners to the bottom line

            A message now shows on the bottom line.

            Changes:
            • Draw "No media playing" on the bottom line
            • Update README
            """.trimIndent(),
            watchChangelog(notes),
        )
    }

    @Test
    fun changelogIsCutToFitWithAnEllipsis() {
        val changelog = watchChangelog("é".repeat(400), maxBytes = 100)
        assertTrue(changelog.endsWith("…"))
        assertTrue(changelog.encodeToByteArray().size <= 100)
        assertEquals("é".repeat(48) + "…", changelog)
        assertEquals("short", watchChangelog("short", maxBytes = 100))
    }

    @Test
    fun releaseVersionLabelTakesTheVersionAndCommit() {
        assertEquals("1.81.0 (e13610b)", releaseVersionLabel("QuartzPlus 1.81.0 (e13610b)"))
        assertEquals("0.1.0", releaseVersionLabel("Claude 0.1.0"))
        assertEquals("build-abc", releaseVersionLabel("build-abc"))
    }

    @Test
    fun listsFirmwareFirstThenAppsByName() {
        val list = buildWatchUpdateList(
            previous = WatchUpdateList(),
            drewOs = drewOsUpdate,
            drewOsSizeBytes = 3_459_374,
            official = null,
            apps = listOf(
                appUpdate(timer, "Timer", AppType.Watchapp),
                appUpdate(quartz, "Quartz Plus", AppType.Watchface),
            ),
            running = running,
            now = now,
        )
        assertEquals(listOf(DREW_OS_UPDATE_ID, appUpdateId(quartz.uuid), appUpdateId(timer.uuid)), list.entries.map { it.id })
        assertEquals(now.epochSeconds, list.lastCheckedUtc)
        assertNull(list.error)

        val firmware = list.entries[0]
        assertEquals(WatchUpdateKind.Firmware, firmware.kind)
        assertEquals("DrewPebbleOS", firmware.name)
        assertEquals("v4.41.2-drew.0", firmware.currentVersion)
        assertEquals("v4.41.2-drew.1", firmware.newVersion)
        assertEquals(3_459_374, firmware.sizeBytes)
        assertTrue(firmware.changelog.startsWith("New since your build:\n• fw/apps/settings: add an Updates page"))

        val face = list.entries[1]
        assertEquals(WatchUpdateKind.Watchface, face.kind)
        assertEquals("Quartz Plus", face.name)
        assertEquals("1.81", face.currentVersion)
        assertEquals("1.81.0 (e13610b)", face.newVersion)
        assertEquals(446_858, face.sizeBytes)
        assertEquals(WatchUpdateKind.Watchapp, list.entries[2].kind)
    }

    @Test
    fun noUpdatesIsAnEmptyCheckedList() {
        val list = buildWatchUpdateList(
            previous = WatchUpdateList(entries = listOf(drewOsUpdateEntry(drewOsUpdate, running, 0)), lastCheckedUtc = 5),
            drewOs = FirmwareUpdateCheckResult.FoundNoUpdate,
            drewOsSizeBytes = 0,
            official = null,
            apps = emptyList(),
            running = running,
            now = now,
        )
        assertEquals(WatchUpdateList(lastCheckedUtc = now.epochSeconds), list)
    }

    @Test
    fun failedFirmwareCheckKeepsTheKnownEntryAndLastCheckTime() {
        val previous = WatchUpdateList(entries = listOf(drewOsUpdateEntry(drewOsUpdate, running, 0)), lastCheckedUtc = 5)
        val list = buildWatchUpdateList(
            previous = previous,
            drewOs = FirmwareUpdateCheckResult.UpdateCheckFailed("offline"),
            drewOsSizeBytes = 0,
            official = null,
            apps = listOf(appUpdate(quartz, "Quartz Plus", AppType.Watchface)),
            running = running,
            now = now,
        )
        assertEquals(listOf(DREW_OS_UPDATE_ID, appUpdateId(quartz.uuid)), list.entries.map { it.id })
        assertEquals(5, list.lastCheckedUtc)
        assertEquals("Couldn't check DrewPebbleOS", list.error)
    }

    @Test
    fun officialFirmwareIsFlaggedAsNotDrew() {
        val official = FirmwareUpdateCheckResult.FoundUpdate(version("v4.42.0"), "https://example.com/fw.pbz", "## Fixes")
        val list = buildWatchUpdateList(
            previous = WatchUpdateList(),
            drewOs = FirmwareUpdateCheckResult.FoundNoUpdate,
            drewOsSizeBytes = 0,
            official = official,
            apps = emptyList(),
            running = running,
            now = now,
        )
        val entry = list.entries.single()
        assertEquals(OFFICIAL_OS_UPDATE_ID, entry.id)
        assertTrue(entry.notDrew)
        assertEquals("Fixes", entry.changelog)
        val packet = entry.toPacket(listId = 9u, index = 0, installing = false)
        assertEquals(
            DrewUpdatesMessage.ListEntry.FLAG_REBOOTS.toInt() or DrewUpdatesMessage.ListEntry.FLAG_NOT_DREW.toInt(),
            packet.flags.get().toInt(),
        )
    }

    @Test
    fun entryEncodesIntoAListEntry() {
        val entry = appUpdateEntry(appUpdate(quartz, "Quartz Plus", AppType.Watchface))
        val packet = entry.toPacket(listId = 4u, index = 2, installing = true)
        assertEquals(4u.toUShort(), packet.listId.get())
        assertEquals(2u.toUByte(), packet.index.get())
        assertEquals(UpdateKind.Watchface.value, packet.kind.get())
        assertEquals(DrewUpdatesMessage.ListEntry.FLAG_INSTALLING, packet.flags.get())
        assertEquals(446_858u, packet.sizeBytes.get())
        assertEquals("app:${quartz.uuid}", packet.id.get())
        assertEquals("Quartz Plus", packet.name.get())
        assertEquals("notes", packet.changelog.get())
    }

    @Test
    fun appIdsRoundTrip() {
        assertEquals(quartz.uuid, appUuidFromUpdateId(appUpdateId(quartz.uuid)))
        assertNull(appUuidFromUpdateId(DREW_OS_UPDATE_ID))
        assertNull(appUuidFromUpdateId("app:nope"))
    }
}
