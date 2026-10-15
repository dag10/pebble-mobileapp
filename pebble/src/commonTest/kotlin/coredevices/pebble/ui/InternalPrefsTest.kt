package coredevices.pebble.ui

import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import kotlin.test.Test
import kotlin.test.assertEquals

class InternalPrefsTest {
    @Test
    fun phoneOwnedStateIsNotShownAsASetting() {
        val prefs = listOf(
            WatchPreference(BoolWatchPref.CalendarAwareQuietTime, null),
            WatchPreference(BoolWatchPref.IosFocusActive, true),
        )
        assertEquals(
            listOf(BoolWatchPref.CalendarAwareQuietTime),
            prefs.hideInternalPrefs().map { it.pref },
        )
    }
}
