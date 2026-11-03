package coredevices.pebble.services

import com.russhwolf.settings.MapSettings
import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.fakeLockerEntries
import io.rebble.libpebblecommon.locker.LockerWrapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class DrewBundledAppsTest {
    private val quartz = DREW_APPS.first { it.assetName == "QuartzPlus.pbw" }
    private val timer = DREW_APPS.first { it.assetName == "Timer.pbw" }
    private val settings = MapSettings()
    private val locker = mutableMapOf<Uuid, LockerWrapper>()
    private val sideloaded = mutableListOf<ByteArray>()
    private var sideloadSucceeds = true
    private val libPebble = object : LibPebble by FakeLibPebble() {
        override fun getLockerApp(id: Uuid): Flow<LockerWrapper?> = flowOf(locker[id])

        override suspend fun sideloadApp(pbwPath: Path, loadOnWatch: Boolean, granted: Map<String, Boolean?>): Boolean {
            assertEquals(false, loadOnWatch)
            sideloaded += SystemFileSystem.source(pbwPath).buffered().use { it.readByteArray() }
            return sideloadSucceeds
        }
    }

    private fun bundledApps(
        bundled: Map<DrewApp, ByteArray> = mapOf(quartz to byteArrayOf(1), timer to byteArrayOf(2)),
        store: Set<Uuid> = emptySet(),
    ) = DrewBundledApps(
        libPebble = libPebble,
        settings = settings,
        storeLockerUuids = { store },
        readBundled = { name -> bundled.entries.find { it.key.assetName == name }?.value },
        tempPath = { Path(SystemTemporaryDirectory, "DrewBundledAppsTest-$it") },
    )

    @Test
    fun addsBundledAppsMissingFromTheLocker() = runTest {
        bundledApps().seed()
        assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(2)), sideloaded.map { it.toList() })
    }

    @Test
    fun skipsAppsAlreadyInTheLocker() = runTest {
        locker[quartz.uuid] = fakeLockerEntries.first()
        bundledApps().seed()
        assertEquals(listOf(listOf<Byte>(2)), sideloaded.map { it.toList() })
    }

    @Test
    fun skipsAppsInTheStoreLockerNotSyncedYet() = runTest {
        bundledApps(store = setOf(timer.uuid)).seed()
        assertEquals(listOf(listOf<Byte>(1)), sideloaded.map { it.toList() })
    }

    @Test
    fun neverAddsAnAppAgainAfterItWasRemoved() = runTest {
        bundledApps().seed()
        sideloaded.clear()
        bundledApps().seed()
        assertEquals(emptyList(), sideloaded)
    }

    @Test
    fun neverAddsAnAppThatWasInTheLockerOnceItIsRemoved() = runTest {
        locker[quartz.uuid] = fakeLockerEntries.first()
        bundledApps(bundled = mapOf(quartz to byteArrayOf(1))).seed()
        locker.clear()
        bundledApps(bundled = mapOf(quartz to byteArrayOf(1))).seed()
        assertEquals(emptyList(), sideloaded)
    }

    @Test
    fun doesNothingWithoutABundledPbw() = runTest {
        var askedStore = false
        DrewBundledApps(
            libPebble = libPebble,
            settings = settings,
            storeLockerUuids = { askedStore = true; emptySet() },
            readBundled = { null },
            tempPath = { Path(SystemTemporaryDirectory, "DrewBundledAppsTest-$it") },
        ).seed()
        assertEquals(false, askedStore)
        assertEquals(emptyList(), sideloaded)
        // A later build that bundles it still adds it.
        bundledApps(bundled = mapOf(quartz to byteArrayOf(1))).seed()
        assertEquals(1, sideloaded.size)
    }

    @Test
    fun retriesAFailedSideload() = runTest {
        sideloadSucceeds = false
        bundledApps(bundled = mapOf(quartz to byteArrayOf(1))).seed()
        sideloadSucceeds = true
        bundledApps(bundled = mapOf(quartz to byteArrayOf(1))).seed()
        assertEquals(2, sideloaded.size)
    }
}
