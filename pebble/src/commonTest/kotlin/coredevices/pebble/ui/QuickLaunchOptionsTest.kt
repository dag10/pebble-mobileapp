package coredevices.pebble.ui

import io.rebble.libpebblecommon.SystemAppIDs.HEALTH_APP_UUID
import io.rebble.libpebblecommon.SystemAppIDs.QUICK_LAUNCH_NOTHING_UUID
import io.rebble.libpebblecommon.SystemAppIDs.QUICK_LAUNCH_WATCHFACE_UUID
import io.rebble.libpebblecommon.SystemAppIDs.TIMELINE_FUTURE_UUID
import io.rebble.libpebblecommon.SystemAppIDs.TIMELINE_MENU_ENTRY_UUID
import io.rebble.libpebblecommon.SystemAppIDs.TIMELINE_PAST_UUID
import io.rebble.libpebblecommon.database.entity.QuicklaunchWatchPref
import kotlin.test.Test
import kotlin.test.assertEquals

class QuickLaunchOptionsTest {
    private val unassigned = QuickLaunchOption(null, "Unassigned")
    private val past = QuickLaunchOption(TIMELINE_PAST_UUID, "Timeline Past")
    private val future = QuickLaunchOption(TIMELINE_FUTURE_UUID, "Timeline Future")
    private val watchface = QuickLaunchOption(QUICK_LAUNCH_WATCHFACE_UUID, "Send to watchface")
    private val nothing = QuickLaunchOption(QUICK_LAUNCH_NOTHING_UUID, "Nothing")
    private val health = QuickLaunchOption(HEALTH_APP_UUID, "Health")
    private val timeline = QuickLaunchOption(TIMELINE_MENU_ENTRY_UUID, "Timeline")
    private val all = listOf(unassigned, past, future, watchface, nothing, health, timeline)

    @Test
    fun holdsOfferEverything() {
        assertEquals(all, all.forQuickLaunchPref(QuicklaunchWatchPref.QlBack))
    }

    @Test
    fun tapUpAndDownOfferTheirOwnTimelineDirection() {
        assertEquals(
            listOf(unassigned, past, watchface, nothing, health),
            all.forQuickLaunchPref(QuicklaunchWatchPref.QlSingleClickUp),
        )
        assertEquals(
            listOf(unassigned, future, watchface, nothing, health),
            all.forQuickLaunchPref(QuicklaunchWatchPref.QlSingleClickDown),
        )
    }

    @Test
    fun tapBackOffersOnlyTheWatchface() {
        assertEquals(
            listOf(unassigned, watchface),
            all.forQuickLaunchPref(QuicklaunchWatchPref.QlSingleClickBack),
        )
    }

    @Test
    fun combosCannotSendToTheWatchface() {
        val combos = listOf(
            QuicklaunchWatchPref.QlTapBackUp,
            QuicklaunchWatchPref.QlDoubleTapBackSelect,
            QuicklaunchWatchPref.QlTripleTapBackDown,
            QuicklaunchWatchPref.QlComboUpDown,
        )
        combos.forEach { pref ->
            assertEquals(all - watchface, all.forQuickLaunchPref(pref), pref.name)
        }
    }
}
