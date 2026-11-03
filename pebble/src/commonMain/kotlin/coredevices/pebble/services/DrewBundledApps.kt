package coredevices.pebble.services

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import coredevices.pebble.account.FirestoreLocker
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.uuid.Uuid

private val logger = Logger.withTag("DrewBundledApps")

/**
 * Adds the [DREW_APPS] pbws a build bundled (under `bundled-apps/<assetName>`) to the locker, once
 * per app and only when the locker doesn't have it, so a removed app stays removed.
 */
class DrewBundledApps(
    private val libPebble: LibPebble,
    private val settings: Settings,
    /** Waits for the signed-in user's store locker; empty when signed out. */
    private val storeLockerUuids: suspend () -> Set<Uuid>,
    private val readBundled: (fileName: String) -> ByteArray?,
    private val tempPath: (fileName: String) -> Path,
) {
    fun start(scope: CoroutineScope) {
        scope.launch { seed() }
    }

    suspend fun seed() {
        val bundled = DREW_APPS.filter { !settings.getBoolean(seededKey(it), false) }
            .mapNotNull { app -> readBundled(app.assetName)?.let { app to it } }
        if (bundled.isEmpty()) {
            return
        }
        // A sideload would shadow a store copy with the same uuid that hasn't synced down yet.
        val store = storeLockerUuids()
        bundled.forEach { (app, pbw) ->
            val inLocker = app.uuid in store || libPebble.getLockerApp(app.uuid).first() != null
            if (!inLocker && !sideload(app, pbw)) {
                return@forEach
            }
            logger.i { if (inLocker) "${app.assetName} is already in the locker" else "Added ${app.assetName}" }
            settings.putBoolean(seededKey(app), true)
        }
    }

    private suspend fun sideload(app: DrewApp, pbw: ByteArray): Boolean {
        val path = tempPath(app.assetName)
        return try {
            SystemFileSystem.sink(path).buffered().use { it.write(pbw) }
            libPebble.sideloadApp(path, loadOnWatch = false)
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
        }
    }

    private fun seededKey(app: DrewApp) = "drew_bundled_app_seeded_${app.uuid}"
}

suspend fun FirestoreLocker.confirmedUuids(): Set<Uuid> {
    if (Firebase.auth.currentUser == null) {
        return emptySet()
    }
    return locker.filterNotNull().first().map { it.uuid }.toSet()
}
