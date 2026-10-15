package coredevices.pebble.firmware

import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.drewBuildNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

class DrewPebbleOsTest {
    private val release = GitHubRelease(
        tagName = "v4.41.1-drew.1",
        body = "notes",
        assets = listOf(
            GitHubReleaseAsset("DrewOS-obelix.pbz", "https://example.com/DrewOS-obelix.pbz"),
            GitHubReleaseAsset(
                "normal_obelix_pvt_v4.41.1-drew.1.pbz",
                "https://example.com/normal_obelix_pvt_v4.41.1-drew.1.pbz",
            ),
        ),
    )

    private fun running(tag: String, isRecovery: Boolean = false) = FirmwareVersion.from(
        tag = tag,
        isRecovery = isRecovery,
        gitHash = "",
        timestamp = Instant.fromEpochSeconds(1_700_000_000),
        isDualSlot = true,
        isSlot0 = true,
    )!!

    private fun check(runningTag: String, platform: String = "obelix_pvt", isRecovery: Boolean = false) =
        drewPebbleOsUpdate(release, platform, running(runningTag, isRecovery))

    @Test
    fun parsesDrewBuildNumber() {
        assertEquals(0, drewBuildNumber("v4.41.1-drew.0"))
        assertEquals(12, drewBuildNumber("v4.41.1-drew.12-3-gabcdef"))
        assertNull(drewBuildNumber("v4.41.1"))
    }

    @Test
    fun offersOverOfficialFirmware() {
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(check("v4.41.1"))
        assertEquals("https://example.com/normal_obelix_pvt_v4.41.1-drew.1.pbz", update.url)
        assertEquals("notes", update.notes)
        assertEquals("v4.41.1-drew.1", update.version.stringVersion)
    }

    @Test
    fun offersOverNewerOfficialFirmware() {
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(check("v4.42.0"))
        assertEquals(true, update.canDowngrade)
    }

    @Test
    fun offersOverOlderDrewBuild() {
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(check("v4.41.1-drew.0"))
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(check("v4.40.0-drew.5"))
    }

    @Test
    fun offersOverRecovery() {
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(check("v4.41.1-drew.1", isRecovery = true))
    }

    @Test
    fun doesNotOfferSameOrNewerDrewBuild() {
        assertEquals(FirmwareUpdateCheckResult.FoundNoUpdate, check("v4.41.1-drew.1"))
        assertEquals(FirmwareUpdateCheckResult.FoundNoUpdate, check("v4.41.1-drew.2"))
        assertEquals(FirmwareUpdateCheckResult.FoundNoUpdate, check("v4.42.0-drew.0"))
    }

    @Test
    fun doesNotOfferWithoutBuildForPlatform() {
        assertEquals(FirmwareUpdateCheckResult.FoundNoUpdate, check("v4.41.1", platform = "snowy_dvt"))
    }
}
