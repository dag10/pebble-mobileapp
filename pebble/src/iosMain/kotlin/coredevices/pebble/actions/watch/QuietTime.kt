package coredevices.pebble.actions.watch

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.AlertMask
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import io.rebble.libpebblecommon.database.entity.EnumWatchPref
import io.rebble.libpebblecommon.database.entity.QuietTimeShowNotificationsMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val logger = Logger.withTag("QuietTime")
private val IOS_FOCUS_SYNC_TIMEOUT = 20.seconds

/** Enables or disables Quiet Time (Do Not Disturb) on the watch. */
fun setQuietTimeEnabled(libPebble: LibPebble, enabled: Boolean) {
    libPebble.setWatchPref(WatchPreference(BoolWatchPref.QuietTimeManuallyEnabled, enabled))
}

private fun List<WatchPreference<*>>.iosFocusActive() =
    firstOrNull { it.pref == BoolWatchPref.IosFocusActive }?.valueOrDefault() as? Boolean

/** What the phone and watch know about the iOS Focus, for the Focus debug log. */
data class IosFocusState(
    /** The value saved on the phone for the watch. */
    val phoneValue: Boolean,
    /** Every connected watch has [phoneValue]. */
    val watchHasIt: Boolean,
    val connectedWatches: Int,
)

suspend fun iosFocusState(libPebble: LibPebble) = IosFocusState(
    phoneValue = libPebble.watchPrefs.first().iosFocusActive() ?: false,
    watchHasIt = libPebble.watchPrefSynced(BoolWatchPref.IosFocusActive).first(),
    connectedWatches = libPebble.watches.value.count { it is ConnectedPebbleDevice },
)

data class IosFocusSyncResult(
    val before: IosFocusState,
    val after: IosFocusState,
    val timedOut: Boolean,
    val duration: Duration,
)

/**
 * Tells the watch whether an iOS Focus with the Pebble Quiet Time filter is on, and waits until
 * the connected watch has it. iOS can suspend the app as soon as the Focus Filter returns.
 */
suspend fun setIosFocusActive(libPebble: LibPebble, active: Boolean): IosFocusSyncResult {
    val mark = TimeSource.Monotonic.markNow()
    val before = iosFocusState(libPebble)
    val synced = withTimeoutOrNull(IOS_FOCUS_SYNC_TIMEOUT) {
        if (before.phoneValue != active) {
            libPebble.setWatchPref(WatchPreference(BoolWatchPref.IosFocusActive, active))
            libPebble.watchPrefs.first { it.iosFocusActive() == active }
        }
        libPebble.watchPrefSynced(BoolWatchPref.IosFocusActive).first { it }
    }
    if (synced == null) {
        logger.w { "iOS Focus active=$active not sent to the watch within $IOS_FOCUS_SYNC_TIMEOUT" }
    }
    return IosFocusSyncResult(
        before = before,
        after = iosFocusState(libPebble),
        timedOut = synced == null,
        duration = mark.elapsedNow(),
    )
}

/**
 * Sets whether notifications are shown during Quiet Time on the watch.
 * @param show true = Show, false = Hide
 */
fun setQuietTimeShowNotifications(libPebble: LibPebble, show: Boolean) {
    val mode = if (show) QuietTimeShowNotificationsMode.Show else QuietTimeShowNotificationsMode.Hide
    libPebble.setWatchPref(WatchPreference(EnumWatchPref.QuietTimeShowNotifications, mode))
}

/**
 * Sets which interruptions are allowed during Quiet Time on the watch.
 * @param alertMaskName one of: AllOff, PhoneCalls
 */
fun setQuietTimeInterruptions(libPebble: LibPebble, alertMaskName: String) {
    val mask = EnumWatchPref.QuietTimeInterruptions.options
        .filterIsInstance<AlertMask>()
        .firstOrNull { it.name == alertMaskName }
        ?: AlertMask.AllOff
    libPebble.setWatchPref(WatchPreference(EnumWatchPref.QuietTimeInterruptions, mask))
}
