package coredevices.coreapp

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.drew.DrewExperiments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import platform.posix.getpid
import kotlin.time.Clock

/**
 * A temporary trail of everything the iOS Focus Quiet Time code sees, for debugging Focus changes
 * that never reach the watch. Each line goes to Documents/focus-debug.log (kept apart from the
 * main log, which rolls over in hours) and the main log, and some also post an iPhone
 * notification (Settings > Drew Experiments can turn those off). Lines also go to
 * [DrewExperiments], which that screen shows.
 *
 * Pull the file from a Mac with:
 * xcrun devicectl device copy from --device <id> --domain-type appDataContainer
 *   --domain-identifier coredevices.coreapp<TEAM> --source Documents/focus-debug.log --destination .
 */
object IosFocusDebugLog {
    private val logger = Logger.withTag("FocusDebug")
    private val mutex = Mutex()
    private const val MAX_SIZE = 512 * 1024

    private val dir: String by lazy {
        val url = NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
        requireNotNull(url?.path)
    }

    suspend fun record(message: String, notify: Boolean = false) {
        val context = withContext(Dispatchers.Main) {
            val app = UIApplication.sharedApplication
            val state = when (app.applicationState) {
                UIApplicationState.UIApplicationStateActive -> "active"
                UIApplicationState.UIApplicationStateInactive -> "inactive"
                else -> "background"
            }
            val data = if (app.isProtectedDataAvailable()) "unlocked" else "locked"
            "pid=${getpid()} app=$state data=$data"
        }
        val line = "${Clock.System.now()} [$context] $message"
        logger.i { line }
        try {
            mutex.withLock {
                val path = Path("$dir/focus-debug.log")
                if ((SystemFileSystem.metadataOrNull(path)?.size ?: 0) > MAX_SIZE) {
                    SystemFileSystem.atomicMove(path, Path("$dir/focus-debug.old.log"))
                }
                SystemFileSystem.sink(path, append = true).buffered().use {
                    it.writeString(line + "\n")
                }
            }
        } catch (e: Exception) {
            logger.e(e) { "Couldn't write the Focus debug log" }
        }
        DrewExperiments.log(message)
        if (notify && DrewExperiments.state.value.focusNotifications) postNotification(message)
    }

    private fun postNotification(message: String) {
        val content = UNMutableNotificationContent()
        content.setTitle("Focus debug")
        content.setBody(message)
        content.setThreadIdentifier("focus-debug")
        val request = UNNotificationRequest.requestWithIdentifier(
            "focus-debug-${Clock.System.now().toEpochMilliseconds()}",
            content,
            null,
        )
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) { error ->
            if (error != null) logger.w { "Couldn't post the Focus debug notification: $error" }
        }
    }
}
