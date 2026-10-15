package coredevices.pebble.actions.watch

import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.AlertMask
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import io.rebble.libpebblecommon.database.entity.EnumWatchPref
import io.rebble.libpebblecommon.database.entity.QuietTimeShowNotificationsMode
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Enables or disables Quiet Time (Do Not Disturb) on the watch. */
fun setQuietTimeEnabled(libPebble: LibPebble, enabled: Boolean) {
    libPebble.setWatchPref(WatchPreference(BoolWatchPref.QuietTimeManuallyEnabled, enabled))
}

/** Tells the watch whether an iOS Focus with the Pebble Quiet Time filter is on. */
fun setIosFocusActive(libPebble: LibPebble, active: Boolean) {
    GlobalScope.launch {
        val current = libPebble.watchPrefs.first()
            .firstOrNull { it.pref == BoolWatchPref.IosFocusActive }?.valueOrDefault()
        if (current != active) {
            libPebble.setWatchPref(WatchPreference(BoolWatchPref.IosFocusActive, active))
        }
    }
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
