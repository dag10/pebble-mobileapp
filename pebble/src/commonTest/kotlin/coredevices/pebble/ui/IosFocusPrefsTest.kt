package coredevices.pebble.ui

import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import kotlin.test.Test
import kotlin.test.assertEquals

class IosFocusPrefsTest {
    private val prefs = listOf(
        WatchPreference(BoolWatchPref.CalendarAwareQuietTime, null),
        WatchPreference(BoolWatchPref.QuietTimeIosFocus, true),
        WatchPreference(BoolWatchPref.IosFocusActive, true),
    )

    @Test
    fun iosShowsTheFocusSettingButNotItsState() {
        assertEquals(
            listOf(BoolWatchPref.CalendarAwareQuietTime, BoolWatchPref.QuietTimeIosFocus),
            prefs.hideUnavailablePrefs(isIOS = true).map { it.pref },
        )
    }

    @Test
    fun otherPlatformsHideBoth() {
        assertEquals(
            listOf(BoolWatchPref.CalendarAwareQuietTime),
            prefs.hideUnavailablePrefs(isIOS = false).map { it.pref },
        )
    }
}
