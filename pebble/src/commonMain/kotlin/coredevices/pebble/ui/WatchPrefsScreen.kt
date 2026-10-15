package coredevices.pebble.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.rememberLibPebble
import coredevices.ui.ConfirmDialog
import coredevices.util.Platform
import coredevices.util.isIOS
import io.rebble.libpebblecommon.SystemAppIDs.AIRPLANE_MODE_UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.rebble.libpebblecommon.SystemAppIDs.BACKLIGHT_UUID
import io.rebble.libpebblecommon.SystemAppIDs.CLEAR_NOTIFICATION_HISTORY_UUID
import io.rebble.libpebblecommon.SystemAppIDs.MOTION_BACKLIGHT_UUID
import io.rebble.libpebblecommon.SystemAppIDs.MUSIC_NEXT_TRACK_UUID
import io.rebble.libpebblecommon.SystemAppIDs.MUSIC_PLAY_PAUSE_UUID
import io.rebble.libpebblecommon.SystemAppIDs.MUSIC_PREVIOUS_TRACK_UUID
import io.rebble.libpebblecommon.SystemAppIDs.QUICK_LAUNCH_NOTHING_UUID
import io.rebble.libpebblecommon.SystemAppIDs.QUICK_LAUNCH_WATCHFACE_UUID
import io.rebble.libpebblecommon.SystemAppIDs.QUIET_TIME_TOGGLE_UUID
import io.rebble.libpebblecommon.SystemAppIDs.TIMELINE_FUTURE_UUID
import io.rebble.libpebblecommon.SystemAppIDs.TIMELINE_MENU_ENTRY_UUID
import io.rebble.libpebblecommon.SystemAppIDs.TIMELINE_PAST_UUID
import io.rebble.libpebblecommon.SystemAppIDs.TOUCH_TOGGLE_UUID
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BacklightPresetMode
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import io.rebble.libpebblecommon.database.entity.ColorWatchPref
import io.rebble.libpebblecommon.database.entity.EnumWatchPref
import io.rebble.libpebblecommon.database.entity.NumberWatchPref
import io.rebble.libpebblecommon.database.entity.QuickLaunchSetting
import io.rebble.libpebblecommon.database.entity.QuicklaunchWatchPref
import io.rebble.libpebblecommon.database.entity.QuietTimeSchedule
import io.rebble.libpebblecommon.database.entity.RgbColorWatchPref
import io.rebble.libpebblecommon.database.entity.ScheduleWatchPref
import io.rebble.libpebblecommon.database.entity.WatchPref
import io.rebble.libpebblecommon.database.entity.WatchPrefEnum
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.timeline.TimelineColor
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid
import org.koin.compose.koinInject

// Snap the notification timeout slider to 15-second increments (40 stops across 15..600s)
// so the displayed MM:SS value is always a clean quarter minute.
private const val NOTIFICATION_TIMEOUT_STEP_COUNT = 38

// The watch overwrites these whenever a non-Advanced backlight preset is set, so showing them
// under a preset would offer settings the watch immediately reverts.
private val PRESET_MANAGED_BACKLIGHT_PREFS = setOf<WatchPref<*>>(
    BoolWatchPref.AmbientLightSensor,
    BoolWatchPref.BacklightMotion,
    EnumWatchPref.BacklightIntensity,
    EnumWatchPref.DynamicBacklightMode,
    EnumWatchPref.BacklightTouch,
    NumberWatchPref.BacklightTimeoutMs,
)

internal fun List<WatchPreference<*>>.hidePresetManagedBacklightPrefs(): List<WatchPreference<*>> {
    val preset = firstOrNull { it.pref == EnumWatchPref.BacklightPreset }?.valueOrDefault()
    return if (preset == BacklightPresetMode.Advanced) this
    else filterNot { it.pref in PRESET_MANAGED_BACKLIGHT_PREFS }
}

private val SCHEDULE_HOURS = mapOf<WatchPref<*>, WatchPref<*>>(
    BoolWatchPref.QuietTimeWeekdayScheduleEnabled to ScheduleWatchPref.QuietTimeWeekdaySchedule,
    BoolWatchPref.QuietTimeWeekendScheduleEnabled to ScheduleWatchPref.QuietTimeWeekendSchedule,
)

internal fun List<WatchPreference<*>>.hideInternalPrefs(): List<WatchPreference<*>> =
    filterNot { it.pref.isInternal }

/** Explains the Pebble Quiet Time Focus Filter, which is set up in iPhone Settings. */
private fun iosFocusQuietTimeInfo() = basicSettingsActionItem(
    title = "Quiet Time - iOS Focus",
    topLevelType = TopLevelType.Watch,
    section = Section.QuietTime,
    description = "Quiet Time turns on automatically with any Focus that has the Pebble Quiet Time filter. Add it in iPhone Settings > Focus > (a Focus) > Focus Filters. Requires DrewPebbleOS.",
    keywords = "focus do not disturb sleep",
)

/** Schedule hours sit directly under the toggle they belong to, and only while it is on. */
internal fun List<WatchPreference<*>>.groupQuietTimeScheduleHours(): List<WatchPreference<*>> {
    val hours = filter { it.pref is ScheduleWatchPref }
    return filterNot { it.pref is ScheduleWatchPref }.flatMap { item ->
        val pref = SCHEDULE_HOURS[item.pref]?.takeIf { item.valueOrDefault() == true }
        listOfNotNull(item, hours.firstOrNull { it.pref == pref })
    }
}

@Composable
fun watchPrefs(): List<SettingsItem> {
    val libPebble = rememberLibPebble()
    val platform = koinInject<Platform>()
    val allSettings by libPebble.watchPrefs.collectAsState(emptyList())
    val settings = remember(allSettings) { allSettings.hideInternalPrefs() }
    val quickLaunchOptions = quickLaunchOptions(libPebble)
    val mapped = remember(settings, quickLaunchOptions, platform) {
        settings.hidePresetManagedBacklightPrefs().groupQuietTimeScheduleHours().flatMap { item ->
            val mappedItem = when (val pref = item.pref) {
                is BoolWatchPref -> booleanPref(pref.castParent(item), libPebble)
                is EnumWatchPref -> enumPref(pref.castParent(item), libPebble)
                is QuicklaunchWatchPref -> quicklaunchPref(pref.castParent(item), libPebble, quickLaunchOptions)
                is ColorWatchPref -> colorPref(pref.castParent(item), libPebble)
                is RgbColorWatchPref -> rgbColorPref(pref.castParent(item), libPebble)
                is NumberWatchPref -> numberPref(pref.castParent(item), libPebble)
                is ScheduleWatchPref -> schedulePref(pref.castParent(item), libPebble)
            }
            // Sits after Calendar Aware, as on the watch
            val focusInfo = iosFocusQuietTimeInfo()
                .takeIf { platform.isIOS && item.pref == BoolWatchPref.CalendarAwareQuietTime }
            listOfNotNull(mappedItem, focusInfo)
        }
    }
    val showConfirmReset = remember { mutableStateOf(false) }
    ConfirmDialog(
        show = showConfirmReset,
        title = "Reset To Defaults?",
        text = "Reset all settings to defaults",
        onConfirm = {
            settings.forEach { setting ->
                if (setting.value != setting.pref.defaultValue) {
                    @Suppress("UNCHECKED_CAST")
                    val pref = setting.pref as WatchPref<Any?>
                    libPebble.setWatchPref(WatchPreference(pref, pref.defaultValue))
                }
            }
        },
        confirmText = "Reset",
    )
    val reset = basicSettingsActionItem(
        title = "Reset To Defaults",
        topLevelType = TopLevelType.Watch,
        section = Section.Defaults,
        action = {
            showConfirmReset.value = true
        },
        description = "Reset all watch settings to defaults",
    )
    return listOf(reset) + mapped
}

fun WatchPref<*>.section(): Section = when (this) {
    BoolWatchPref.TimezoneSourceIsManual -> Section.Time
    BoolWatchPref.Clock24h -> Section.Time
    BoolWatchPref.StandbyMode -> Section.Other
    BoolWatchPref.LeftHandedMode -> Section.Display
    BoolWatchPref.Backlight -> Section.Display
    BoolWatchPref.AmbientLightSensor -> Section.Display
    BoolWatchPref.BacklightMotion -> Section.Display
    EnumWatchPref.Language -> Section.Display
    EnumWatchPref.WindSpeed -> Section.Weather
//    ColorWatchPref.SettingsMenuHighlightColor -> Section.Display
//    ColorWatchPref.AppMenuHighlightColor -> Section.Display
    EnumWatchPref.TextSize -> Section.Notifications
    EnumWatchPref.MotionSensitivity -> Section.Display
    EnumWatchPref.BacklightPreset -> Section.Display
    EnumWatchPref.BacklightIntensity -> Section.Display
    EnumWatchPref.DynamicBacklightMode -> Section.Display
    EnumWatchPref.BacklightTouch -> Section.Display
    RgbColorWatchPref.BacklightColor -> Section.Display
    NumberWatchPref.BacklightTimeoutMs -> Section.Display
    NumberWatchPref.AmbientLightThreshold -> Section.Display
    is QuicklaunchWatchPref -> Section.QuickLaunch
    BoolWatchPref.TimelineQuickViewEnabled -> Section.Timeline
    NumberWatchPref.TimelineQuickViewMinsBefore -> Section.Timeline
    EnumWatchPref.NotificationFilter -> Section.Notifications
    EnumWatchPref.QuietTimeInterruptions -> Section.QuietTime
    EnumWatchPref.QuietTimeShowNotifications -> Section.QuietTime
    EnumWatchPref.LegacyVibeIntensity -> Section.Notifications
    EnumWatchPref.VibeScoreNotifications -> Section.Notifications
    EnumWatchPref.VibeScoreCalls -> Section.Notifications
    EnumWatchPref.VibeScoreAlarms -> Section.Notifications
    BoolWatchPref.QuietTimeManuallyEnabled -> Section.QuietTime
    BoolWatchPref.CalendarAwareQuietTime -> Section.QuietTime
    BoolWatchPref.QuietTimeWeekdayScheduleEnabled -> Section.QuietTime
    BoolWatchPref.QuietTimeWeekendScheduleEnabled -> Section.QuietTime
    BoolWatchPref.IosFocusActive -> Section.QuietTime
    ScheduleWatchPref.QuietTimeWeekdaySchedule -> Section.QuietTime
    ScheduleWatchPref.QuietTimeWeekendSchedule -> Section.QuietTime
    BoolWatchPref.AlternativeNotificationStyle -> Section.Notifications
    BoolWatchPref.NotificationVibeDelay -> Section.Notifications
    BoolWatchPref.NotificationBacklight -> Section.Notifications
    NumberWatchPref.NotificationTimeoutMs -> Section.Notifications
    BoolWatchPref.MenuScrollWrapAround -> Section.Display
    EnumWatchPref.MenuScrollVibe -> Section.Display
    BoolWatchPref.QuietTimeMotionBacklight -> Section.QuietTime
    BoolWatchPref.QuietTimeAutoDismiss -> Section.QuietTime
    BoolWatchPref.MusicShowVolumeControls -> Section.Music
    BoolWatchPref.MusicShowProgressBar -> Section.Music
    BoolWatchPref.MusicShowAlbumArt -> Section.Music
}

fun WatchPref<*>.topLevelType(): TopLevelType = when (this) {
    // Weather is configured on the phone, even though the unit it sets is a watch pref.
    EnumWatchPref.WindSpeed -> TopLevelType.Phone
    else -> TopLevelType.Watch
}

private fun numberPref(item: WatchPreference<Long>, libPebble: LibPebble): SettingsItem {
    val pref = item.pref as NumberWatchPref
    return when (pref) {
        NumberWatchPref.BacklightTimeoutMs -> {
            basicSettingsNumberSecondsItem(
                pref = pref,
                item = item,
                libPebble = libPebble,
                valueFormatter = { seconds -> "$seconds seconds" },
            )
        }
        NumberWatchPref.NotificationTimeoutMs -> {
            basicSettingsNumberSecondsItem(
                pref = pref,
                item = item,
                libPebble = libPebble,
                valueFormatter = { seconds ->
                    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                },
                steps = NOTIFICATION_TIMEOUT_STEP_COUNT,
            )
        }
        else -> basicSettingsNumberItem(
            id = pref.id,
            title = pref.displayName,
            description = pref.description,
            topLevelType = pref.topLevelType(),
            section = pref.section(),
            value = item.valueOrDefault(),
            min = pref.min,
            max = pref.max,
            onValueChange = {
                libPebble.setWatchPref(item.copy(value = it))
            },
            isDebugSetting = pref.isDebugSetting,
            defaultValue = pref.defaultValue,
            unit = pref.unit,
        )
    }
}

private fun basicSettingsNumberSecondsItem(
    pref: NumberWatchPref,
    item: WatchPreference<Long>,
    libPebble: LibPebble,
    valueFormatter: (Long) -> String,
    steps: Int? = null,
): SettingsItem = basicSettingsNumberItem(
    id = pref.id,
    title = pref.displayName,
    description = pref.description,
    topLevelType = pref.topLevelType(),
    section = pref.section(),
    value = item.valueOrDefault().milliseconds.inWholeSeconds,
    min = pref.min.milliseconds.inWholeSeconds.toInt(),
    max = pref.max.milliseconds.inWholeSeconds.toInt(),
    onValueChange = {
        libPebble.setWatchPref(item.copy(value = it.seconds.inWholeMilliseconds))
    },
    isDebugSetting = pref.isDebugSetting,
    defaultValue = pref.defaultValue.milliseconds.inWholeSeconds,
    unit = "",
    valueFormatter = valueFormatter,
    steps = steps,
)

private fun schedulePref(
    item: WatchPreference<QuietTimeSchedule>,
    libPebble: LibPebble,
): SettingsItem {
    val pref = item.pref as ScheduleWatchPref
    return SettingsItem(
        id = pref.id,
        title = pref.displayName,
        topLevelType = pref.topLevelType(),
        section = pref.section(),
        item = {
            ListItem(
                headlineContent = {
                    Text(pref.displayName)
                },
                supportingContent = {
                    Column {
                        pref.description?.let { description ->
                            Text(description, fontSize = 11.sp)
                        }
                        SelectTimeRange(
                            schedule = item.valueOrDefault(),
                            onChangeSchedule = { schedule ->
                                libPebble.setWatchPref(item.copy(value = schedule))
                            },
                        )
                    }
                },
                shadowElevation = 2.dp,
            )
        },
        isDebugSetting = pref.isDebugSetting,
    )
}

private enum class ScheduleEdge(val title: String) {
    From("Start time"),
    To("End time"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectTimeRange(
    schedule: QuietTimeSchedule,
    onChangeSchedule: (QuietTimeSchedule) -> Unit,
) {
    var editing by remember { mutableStateOf<ScheduleEdge?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("From", fontSize = 14.sp)
        TextButton(onClick = { editing = ScheduleEdge.From }) {
            Text(schedule.fromTime())
        }
        Text("to", fontSize = 14.sp)
        TextButton(onClick = { editing = ScheduleEdge.To }) {
            Text(schedule.toTime())
        }
    }
    val edge = editing ?: return
    val state = rememberTimePickerState(
        initialHour = if (edge == ScheduleEdge.From) schedule.fromHour else schedule.toHour,
        initialMinute = if (edge == ScheduleEdge.From) schedule.fromMinute else schedule.toMinute,
    )
    TimePickerDialog(
        onDismissRequest = { editing = null },
        title = { Text(edge.title) },
        dismissButton = {
            TextButton(onClick = { editing = null }) {
                Text("Cancel")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                editing = null
                onChangeSchedule(
                    when (edge) {
                        ScheduleEdge.From -> schedule.copy(
                            fromHour = state.hour,
                            fromMinute = state.minute,
                        )
                        ScheduleEdge.To -> schedule.copy(
                            toHour = state.hour,
                            toMinute = state.minute,
                        )
                    }
                )
            }) {
                Text("Set")
            }
        },
    ) {
        TimePicker(state = state)
    }
}

private fun colorPref(item: WatchPreference<TimelineColor>, libPebble: LibPebble): SettingsItem {
    val pref = item.pref as ColorWatchPref
    val default = item.valueOrDefault()
    return SettingsItem(
        id = pref.id,
        title = pref.displayName,
        topLevelType = pref.topLevelType(),
        section = pref.section(),
        item = {
            ListItem(
                headlineContent = {
                    Text(pref.displayName)
                },
                supportingContent = {
                    Column {
                        pref.description?.let { description ->
                            Text(description, fontSize = 11.sp)
                        }
                        SelectColorOrNone(
                            currentColorName = default.identifier,
                            onChangeColor = { color ->
                                libPebble.setWatchPref(item.copy(value = color))
                            },
                            availableColors = pref.availableColors,
                            defaultToListTab = true,
                        )
                    }
                },
                shadowElevation = 2.dp,
            )
        },
        isDebugSetting = pref.isDebugSetting,
    )
}

private fun booleanPref(item: WatchPreference<Boolean>, libPebble: LibPebble): SettingsItem {
    return basicSettingsToggleItem(
        id = item.pref.id,
        title = item.pref.displayName,
        description = item.pref.description,
        topLevelType = item.pref.topLevelType(),
        section = item.pref.section(),
        checked = item.valueOrDefault(),
        onCheckChanged = { enabled ->
            libPebble.setWatchPref(item.copy(value = enabled))
        },
        isDebugSetting = item.pref.isDebugSetting,
    )
}

private fun enumPref(item: WatchPreference<WatchPrefEnum>, libPebble: LibPebble): SettingsItem {
    val pref = item.pref as EnumWatchPref
    return basicSettingsDropdownItem(
        id = pref.id,
        title = pref.displayName,
        description = pref.description,
        topLevelType = pref.topLevelType(),
        section = pref.section(),
        selectedItem = item.valueOrDefault(),
        items = pref.options,
        onItemSelected = {
            libPebble.setWatchPref(item.copy(value = it))
        },
        itemText = { it.displayName },
        isDebugSetting = pref.isDebugSetting,
    )
}

private fun rgbColorPref(item: WatchPreference<UInt>, libPebble: LibPebble): SettingsItem {
    val pref = item.pref as RgbColorWatchPref
    return SettingsItem(
        id = pref.id,
        title = pref.displayName,
        topLevelType = pref.topLevelType(),
        section = pref.section(),
        item = {
            ListItem(
                headlineContent = { Text(pref.displayName) },
                supportingContent = {
                    Column {
                        pref.description?.let { description ->
                            Text(description, fontSize = 11.sp)
                        }
                        SelectRgbColor(
                            currentRgb = item.valueOrDefault(),
                            defaultRgb = pref.defaultValue,
                            presets = pref.presets,
                            onChangeColor = { rgb ->
                                libPebble.setWatchPref(item.copy(value = rgb))
                            },
                        )
                    }
                },
            )
        },
        isDebugSetting = pref.isDebugSetting,
    )
}

data class QuickLaunchOption(
    val uuid: Uuid?,
    val displayName: String,
)

@Composable
private fun quickLaunchOptions(libPebble: LibPebble): List<QuickLaunchOption> {
    val installedApps by libPebble.getLocker(
        type = AppType.Watchapp,
        searchQuery = null,
        limit = 100,
    ).map { apps ->
        apps.filter { app -> app.isSynced() }
    }.collectAsState(emptyList())
    // Toggle Touch only exists on watches with a touchscreen.
    val hasTouch = lastConnectedWatch()?.watchType?.watchType.let {
        it == WatchType.EMERY || it == WatchType.GABBRO
    }
    return remember(installedApps, hasTouch) {
        // The watch's Quick Launch actions, then its apps.
        listOf(QuickLaunchOption(null, "Unassigned")) +
                QuickLaunchOption(QUIET_TIME_TOGGLE_UUID, "Toggle Quiet Time") +
                QuickLaunchOption(BACKLIGHT_UUID, "Toggle Backlight") +
                QuickLaunchOption(MOTION_BACKLIGHT_UUID, "Toggle Motion Backlight") +
                QuickLaunchOption(AIRPLANE_MODE_UUID, "Toggle Airplane Mode") +
                listOfNotNull(
                    QuickLaunchOption(TOUCH_TOGGLE_UUID, "Toggle Touch").takeIf { hasTouch }
                ) +
                QuickLaunchOption(TIMELINE_PAST_UUID, "Timeline Past") +
                QuickLaunchOption(TIMELINE_FUTURE_UUID, "Timeline Future") +
                QuickLaunchOption(MUSIC_PLAY_PAUSE_UUID, "Play/Pause") +
                QuickLaunchOption(MUSIC_NEXT_TRACK_UUID, "Next Track") +
                QuickLaunchOption(MUSIC_PREVIOUS_TRACK_UUID, "Previous Track") +
                QuickLaunchOption(CLEAR_NOTIFICATION_HISTORY_UUID, "Clear Notification History") +
                QuickLaunchOption(QUICK_LAUNCH_WATCHFACE_UUID, "Send to watchface") +
                QuickLaunchOption(QUICK_LAUNCH_NOTHING_UUID, "Nothing") +
                installedApps.map { app ->
                    QuickLaunchOption(app.properties.id, app.properties.title)
                }
    }
}

private val QUICK_LAUNCH_COMBOS = setOf(
    QuicklaunchWatchPref.QlTapBackUp,
    QuicklaunchWatchPref.QlTapBackDown,
    QuicklaunchWatchPref.QlTapBackSelect,
    QuicklaunchWatchPref.QlDoubleTapBackUp,
    QuicklaunchWatchPref.QlDoubleTapBackDown,
    QuicklaunchWatchPref.QlDoubleTapBackSelect,
    QuicklaunchWatchPref.QlTripleTapBackUp,
    QuicklaunchWatchPref.QlTripleTapBackDown,
    QuicklaunchWatchPref.QlTripleTapBackSelect,
    QuicklaunchWatchPref.QlComboBackUp,
    QuicklaunchWatchPref.QlComboUpDown,
)

/** The targets the watch offers for [pref], as its Quick Launch settings filter them. */
internal fun List<QuickLaunchOption>.forQuickLaunchPref(pref: QuicklaunchWatchPref): List<QuickLaunchOption> =
    filter { option ->
        val uuid = option.uuid ?: return@filter true
        when {
            // Unassigned, Tap Back dismisses the timeline peek; the only other choice is the watchface.
            pref == QuicklaunchWatchPref.QlSingleClickBack -> uuid == QUICK_LAUNCH_WATCHFACE_UUID
            // A combo has no single button to send on to the watchface.
            pref in QUICK_LAUNCH_COMBOS -> uuid != QUICK_LAUNCH_WATCHFACE_UUID
            pref == QuicklaunchWatchPref.QlSingleClickUp ->
                uuid != TIMELINE_FUTURE_UUID && uuid != TIMELINE_MENU_ENTRY_UUID
            pref == QuicklaunchWatchPref.QlSingleClickDown ->
                uuid != TIMELINE_PAST_UUID && uuid != TIMELINE_MENU_ENTRY_UUID
            else -> true
        }
    }

private fun quicklaunchPref(item: WatchPreference<QuickLaunchSetting>, libPebble: LibPebble, allOptions: List<QuickLaunchOption>): SettingsItem {
    val options = allOptions.forQuickLaunchPref(item.pref as QuicklaunchWatchPref)
    val default = item.valueOrDefault()
    val defaultQl = options.firstOrNull { default.enabled && it.uuid == default.uuid } ?: options[0]
    return basicSettingsDropdownItem(
        id = item.pref.id,
        title = item.pref.displayName,
        description = item.pref.description,
        topLevelType = item.pref.topLevelType(),
        section = item.pref.section(),
        selectedItem = defaultQl,
        items = options,
        onItemSelected = {
            libPebble.setWatchPref(
                WatchPreference(
                    item.pref, QuickLaunchSetting(
                        enabled = it.uuid != null,
                        uuid = it.uuid,
                    )
                )
            )
        },
        itemText = { it.displayName },
        isDebugSetting = item.pref.isDebugSetting,
    )
}
