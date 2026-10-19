package coredevices.pebble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.rememberLibPebble
import io.rebble.libpebblecommon.BleConfig
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.drew.DrewExperiments
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Temporary toggles for the connection and iOS Focus experiments, in Settings > Drew Experiments.
 * See docs/ios-focus-quiet-time.md in pebble-workspace.
 */
fun drewExperimentsSettingsItems(
    navBarNav: NavBarNav?,
    libPebble: LibPebble,
    libPebbleConfig: LibPebbleConfig,
    drewState: DrewExperiments.State,
    isIos: Boolean,
): List<SettingsItem> {
    val bleConfig = libPebbleConfig.bleConfig
    fun updateBle(transform: (BleConfig) -> BleConfig) {
        libPebble.updateConfig(libPebbleConfig.copy(bleConfig = transform(bleConfig)))
    }
    return listOfNotNull(
        navBarNav?.let { nav ->
            basicSettingsActionItem(
                title = "Activity and counters",
                description = "What the experiments below have been doing: heartbeats, App Launch reads, relaunches, connections and Focus events",
                topLevelType = TopLevelType.Phone,
                section = Section.DrewExperiments,
                action = { nav.navigateTo(PebbleNavBarRoutes.DrewExperimentsRoute) },
            )
        },
        navBarNav?.let { nav ->
            basicSettingsActionItem(
                title = "Claude reply notifications",
                description = "Notify on the watch about new Claude Code replies and permission prompts while the Claude app is closed",
                topLevelType = TopLevelType.Phone,
                section = Section.DrewExperiments,
                action = { nav.navigateTo(PebbleNavBarRoutes.ClaudeNotificationsRoute) },
            )
        },
        basicSettingsToggleItem(
            title = "Watch heartbeat",
            description = "Ask DrewPebbleOS (drew.5 or later) to ping the phone every 5 minutes, which wakes the app so the Focus catch-up runs. Takes effect on the next watch connection",
            topLevelType = TopLevelType.Phone,
            section = Section.DrewExperiments,
            checked = bleConfig.drewHeartbeat,
            onCheckChanged = { on -> updateBle { it.copy(drewHeartbeat = on) } },
        ),
        basicSettingsToggleItem(
            title = "App Launch service",
            description = "Let the watch wake or relaunch the app when it loses its Pebble Protocol session. Takes effect on the next app launch",
            topLevelType = TopLevelType.Phone,
            section = Section.DrewExperiments,
            checked = bleConfig.publishAppLaunchService,
            onCheckChanged = { on -> updateBle { it.copy(publishAppLaunchService = on) } },
            show = { isIos },
        ),
        basicSettingsToggleItem(
            title = "Focus catch-up sends to the watch",
            description = "When the Focus state iOS reports differs from what the watch was told (iOS skipped the Focus Filter), send it. Off: only log the drift",
            topLevelType = TopLevelType.Phone,
            section = Section.DrewExperiments,
            checked = drewState.focusCatchUpSends,
            onCheckChanged = { DrewExperiments.setFocusCatchUpSends(it) },
            show = { isIos },
        ),
        basicSettingsToggleItem(
            title = "Focus debug notifications",
            description = "Post a \"Focus debug\" iPhone notification for Focus Filter runs, fixes, timeouts and drift",
            topLevelType = TopLevelType.Phone,
            section = Section.DrewExperiments,
            checked = drewState.focusNotifications,
            onCheckChanged = { DrewExperiments.setFocusNotifications(it) },
            show = { isIos },
        ),
        basicSettingsToggleItem(
            title = "Bluetooth state restoration (central)",
            description = "Also let iOS relaunch the app for events on its connection to the watch. Off by default. Same as the debug setting in Connectivity. Takes effect on the next app launch",
            topLevelType = TopLevelType.Phone,
            section = Section.DrewExperiments,
            checked = bleConfig.centralStateRestoration,
            onCheckChanged = { on -> updateBle { it.copy(centralStateRestoration = on) } },
            show = { isIos },
        ),
    )
}

@Composable
fun DrewExperimentsScreen(navBarNav: NavBarNav, topBarParams: TopBarParams) {
    LaunchedEffect(Unit) {
        topBarParams.searchAvailable(null)
        topBarParams.actions {
            TextButton(onClick = { DrewExperiments.reset() }) { Text("Reset") }
        }
        topBarParams.title("Drew Experiments")
    }
    val state by DrewExperiments.state.collectAsState()
    val libPebble = rememberLibPebble()
    val config by libPebble.config.collectAsState()
    val now by produceState(Clock.System.now()) {
        while (true) {
            delay(15.seconds)
            value = Clock.System.now()
        }
    }
    val log = state.log.asReversed()
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        LazyColumn {
            item(key = "intro") {
                Text(
                    "Since ${state.sinceMs?.let { formatTime(it, now) } ?: "install"}. " +
                        "Heartbeat ${onOff(config.bleConfig.drewHeartbeat)}, " +
                        "App Launch service ${onOff(config.bleConfig.publishAppLaunchService)}, " +
                        "catch-up ${if (state.focusCatchUpSends) "sends" else "logs only"}, " +
                        "central restoration ${onOff(config.bleConfig.centralStateRestoration)}. " +
                        "Always on: the Focus Filter's LiveActivityIntent and NSSupportsLiveActivities.",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(16.dp),
                )
                HorizontalDivider()
            }
            items(DrewExperiments.COUNTERS, key = { it }) { name ->
                val counter = state.counters[name] ?: DrewExperiments.Counter()
                ListItem(
                    headlineContent = { Text("$name: ${counter.count}") },
                    supportingContent = {
                        val last = counter.lastMs?.let { "last ${formatTime(it, now)}" } ?: "never"
                        val lastMs = counter.lastMs
                        val previousMs = counter.previousMs
                        val gap = if (lastMs != null && previousMs != null) {
                            ", ${formatDuration(lastMs - previousMs)} after the one before"
                        } else {
                            ""
                        }
                        Text(last + gap, fontSize = 12.sp)
                    },
                )
            }
            item(key = "log-header") {
                HorizontalDivider()
                Text(
                    "Recent events (newest first, ${log.size} kept)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(log.size) { index ->
                val entry = log[index]
                Text(
                    "${formatTime(entry.ms, now, relative = false)}  ${entry.message}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
                )
            }
        }
    }
}

private fun onOff(on: Boolean) = if (on) "on" else "off"

private fun formatTime(ms: Long, now: Instant, relative: Boolean = true): String {
    val instant = Instant.fromEpochMilliseconds(ms)
    val zone = TimeZone.currentSystemDefault()
    val time = instant.toLocalDateTime(zone)
    val clock = "${time.hour.pad()}:${time.minute.pad()}:${time.second.pad()}"
    val stamp = if (time.date == now.toLocalDateTime(zone).date) {
        clock
    } else {
        "${time.month.ordinal + 1}/${time.day} $clock"
    }
    return if (relative) "$stamp (${formatDuration((now - instant).inWholeMilliseconds)} ago)" else stamp
}

private fun formatDuration(ms: Long): String {
    val duration = ms.milliseconds
    return when {
        duration < 1.seconds -> "${ms}ms"
        duration.inWholeMinutes < 1 -> "${duration.inWholeSeconds}s"
        duration.inWholeHours < 1 -> "${duration.inWholeMinutes}m ${duration.inWholeSeconds % 60}s"
        duration.inWholeDays < 1 -> "${duration.inWholeHours}h ${duration.inWholeMinutes % 60}m"
        else -> "${duration.inWholeDays}d ${duration.inWholeHours % 24}h"
    }
}

private fun Int.pad() = toString().padStart(2, '0')
