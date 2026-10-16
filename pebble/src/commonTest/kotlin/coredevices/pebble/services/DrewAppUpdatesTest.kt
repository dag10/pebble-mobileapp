package coredevices.pebble.services

import coredevices.pebble.firmware.GitHubRelease
import coredevices.pebble.firmware.GitHubReleaseAsset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DrewAppUpdatesTest {
    private val quartz = DREW_APPS.first { it.assetName == "QuartzPlus.pbw" }
    private val releasedSha = "cd0cbadb95828a84cbd1907669b3adb1e8160003fcad28f9e3d9a5f395b4a642"

    private fun asset(name: String, digest: String? = "sha256:$releasedSha") = GitHubReleaseAsset(
        name = name,
        browserDownloadUrl = "https://github.com/dag10/Quartz-Private/releases/download/b/$name",
        url = "https://api.github.com/repos/dag10/Quartz-Private/releases/assets/$name",
        digest = digest,
    )

    private fun release(vararg assets: GitHubReleaseAsset, name: String? = "QuartzPlus 1.81.0 (92d4e9e)") =
        GitHubRelease(
            tagName = "build-20261016-0039-92d4e9e",
            body = "notes",
            assets = assets.toList(),
            name = name,
        )

    @Test
    fun offersReleasedBuildWhenInstalledDiffers() {
        val update = assertNotNull(
            drewAppUpdate(quartz, release(asset("QuartzPlus-92d4e9e.pbw"), asset("QuartzPlus.pbw")), "0".repeat(64))
        )
        assertEquals("QuartzPlus 1.81.0 (92d4e9e)", update.name)
        assertEquals("notes", update.notes)
        assertEquals("https://api.github.com/repos/dag10/Quartz-Private/releases/assets/QuartzPlus.pbw", update.assetUrl)
        assertEquals(releasedSha, update.sha256)
    }

    @Test
    fun noUpdateWhenInstalledMatches() {
        assertNull(drewAppUpdate(quartz, release(asset("QuartzPlus.pbw")), releasedSha))
    }

    @Test
    fun noUpdateWithoutTheFixedNameAsset() {
        assertNull(drewAppUpdate(quartz, release(asset("QuartzPlus-92d4e9e.pbw")), "0".repeat(64)))
    }

    @Test
    fun noUpdateWithoutSha256Digest() {
        assertNull(drewAppUpdate(quartz, release(asset("QuartzPlus.pbw", digest = null)), "0".repeat(64)))
        assertNull(drewAppUpdate(quartz, release(asset("QuartzPlus.pbw", digest = "md5:abc")), "0".repeat(64)))
    }

    @Test
    fun fallsBackToTagWithoutReleaseName() {
        val update = assertNotNull(drewAppUpdate(quartz, release(asset("QuartzPlus.pbw"), name = null), "0".repeat(64)))
        assertEquals("build-20261016-0039-92d4e9e", update.name)
    }
}
