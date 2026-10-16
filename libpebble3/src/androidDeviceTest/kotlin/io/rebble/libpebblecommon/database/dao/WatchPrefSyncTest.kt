package io.rebble.libpebblecommon.database.dao

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.entity.WatchPrefItem
import io.rebble.libpebblecommon.database.entity.WatchPrefItemSyncEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class WatchPrefSyncTest {
    private val db = Room.inMemoryDatabaseBuilder<Database>(InstrumentationRegistry.getInstrumentation().targetContext)
        .setDriver(BundledSQLiteDriver())
        .build()
    private val dao = db.watchPrefDao()

    @After
    fun tearDown() {
        db.close()
    }

    private fun item(value: String, timestampMs: Long) = WatchPrefItem(
        id = PREF,
        value = value,
        timestamp = Instant.fromEpochMilliseconds(timestampMs).asMillisecond(),
    )

    private suspend fun markSynced(item: WatchPrefItem, transport: String = WATCH) {
        dao.markSyncedToWatch(
            WatchPrefItemSyncEntity(
                recordId = item.id,
                transport = transport,
                watchSynchHashcode = item.recordHashCode(),
            )
        )
    }

    private suspend fun isSynced(transport: String = WATCH) = dao.isSyncedToWatch(transport, PREF).first()

    @Test
    fun prefNeverSetIsSynced() = runBlocking {
        assertTrue(isSynced())
    }

    @Test
    fun prefNotYetSentIsNotSynced() = runBlocking {
        dao.insertOrReplace(item("1", 1000))
        assertFalse(isSynced())
    }

    @Test
    fun prefSentIsSynced() = runBlocking {
        val item = item("1", 1000)
        dao.insertOrReplace(item)
        markSynced(item)
        assertTrue(isSynced())
        assertFalse(isSynced(transport = OTHER_WATCH))
    }

    @Test
    fun changedPrefIsNotSyncedUntilSentAgain() = runBlocking {
        val old = item("1", 1000)
        dao.insertOrReplace(old)
        markSynced(old)
        val new = item("0", 2000)
        dao.insertOrReplace(new)
        assertFalse(isSynced())
        markSynced(new)
        assertTrue(isSynced())
    }

    companion object {
        private const val PREF = "drewDndFocusActive"
        private const val WATCH = "watch-1"
        private const val OTHER_WATCH = "watch-2"
    }
}
