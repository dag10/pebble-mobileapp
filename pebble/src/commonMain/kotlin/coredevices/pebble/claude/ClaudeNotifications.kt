package coredevices.pebble.claude

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import coredevices.util.AppResumed
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.drew.DrewExperiments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@Serializable
data class ClaudeNotificationsData(
    val enabled: Boolean = false,
    /** The pairing string as pasted. */
    val pairing: String = "",
    val alerts: ClaudeAlertState = ClaudeAlertState(),
    val lastCheckMs: Long? = null,
    val lastError: String? = null,
    val lastAlertMs: Long? = null,
    val alertCount: Int = 0,
)

/**
 * DrewMobileApp only: watch notifications for new Claude Code replies and permission prompts
 * while the Claude watch app is closed (its PebbleKit JS only runs while it's open). Checks
 * the Claude bridge on each watch heartbeat, app resume, and every minute while the app is
 * awake. Settings > Drew Experiments > Claude reply notifications.
 *
 * Everything lives in this package. The rest of the app touches it only at:
 * - watchModule.kt: the Koin single
 * - PebbleAppDelegate: start() after libPebble.init(), and check() in performBackgroundWork()
 *   (looked up with KoinPlatform: its constructor is at Koin's singleOf limit)
 * - DrewExperimentsScreen.kt: the settings entry; PebbleRoutes.kt: ClaudeNotificationsRoute
 * - DrewExperiments.heartbeats (libpebble3), emitted on the heartbeat ping
 * - pebble/build.gradle.kts: ktor-client-mock and coroutines-test for the tests
 */
class ClaudeNotifications(
    private val libPebble: LibPebble,
    private val settings: Settings,
    private val appResumed: AppResumed,
    private val clock: Clock,
    private val bridge: ClaudeBridgeClient = ClaudeBridgeClient(),
) {
    private val logger = Logger.withTag("ClaudeNotifications")
    private val json = Json { ignoreUnknownKeys = true }
    private val _data by lazy { MutableStateFlow(load()) }
    val data: StateFlow<ClaudeNotificationsData> get() = _data.asStateFlow()
    private val claudeInForeground = MutableStateFlow(false)
    private val checking = Mutex()

    fun start(scope: CoroutineScope) {
        scope.launch {
            var was = false
            claudeForegroundOnWatch().collect { now ->
                claudeInForeground.value = now
                if (now || was) {
                    markSeen()
                }
                was = now
            }
        }
        scope.launch {
            merge(
                DrewExperiments.heartbeats,
                appResumed.appResumed,
                everyMinute(),
                data.map { it.enabled to it.pairing }.distinctUntilChanged().map { },
            ).conflate().collect { check() }
        }
    }

    fun setEnabled(enabled: Boolean) = change {
        when {
            it.enabled == enabled -> it
            // Replies from while it was off aren't news: the first check starts from the newest.
            enabled -> it.copy(enabled = true, alerts = it.alerts.copy(cursor = null), lastError = null)
            else -> it.copy(enabled = false)
        }
    }

    /** Saves a pasted pairing string; returns why it can't be used, or null. */
    fun setPairing(text: String): String? {
        val parsed = ClaudePairing.parse(text)
        parsed.exceptionOrNull()?.let { return it.message }
        change {
            if (ClaudePairing.parse(it.pairing).getOrNull() == parsed.getOrNull()) {
                it.copy(pairing = text.trim())
            } else {
                // Another bridge: its reply ids and prompts start over.
                it.copy(
                    pairing = text.trim(),
                    alerts = it.alerts.copy(cursor = null, notifiedPermissions = emptyList()),
                    lastError = null,
                )
            }
        }
        return null
    }

    suspend fun check() {
        val start = data.value
        if (!start.enabled) return
        val pairing = ClaudePairing.parse(start.pairing).getOrNull() ?: return
        if (!checking.tryLock()) return
        try {
            val plan = try {
                fetchAndPlan(pairing)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Couldn't reach the bridge."
                logger.w { "Check failed: $message" }
                if (message != data.value.lastError) {
                    DrewExperiments.log("Claude notifications: $message")
                }
                change { it.copy(lastCheckMs = nowMs(), lastError = message) }
                return
            }
            // Re-paired or turned back on meanwhile: that starts over.
            if (data.value.pairing != start.pairing || data.value.alerts.cursor != start.alerts.cursor) return
            for (alert in plan.alerts) {
                libPebble.sendNotification(claudeWatchNotification(alert, clock.now()), null)
            }
            if (plan.alerts.isNotEmpty()) {
                DrewExperiments.log("Claude notifications: sent ${plan.alerts.size}")
            }
            change {
                it.copy(
                    // The foreground watcher may have moved it meanwhile.
                    alerts = plan.state.copy(seenUntilMs = it.alerts.seenUntilMs),
                    lastCheckMs = nowMs(),
                    lastError = null,
                    lastAlertMs = if (plan.alerts.isNotEmpty()) nowMs() else it.lastAlertMs,
                    alertCount = it.alertCount + plan.alerts.size,
                )
            }
        } finally {
            checking.unlock()
        }
    }

    private suspend fun fetchAndPlan(pairing: ClaudePairing): ClaudeAlertPlan =
        fetchClaudeAlerts(bridge, pairing, now = ::nowMs) {
            if (claudeInForeground.value) {
                markSeen()
            }
            data.value.alerts
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun claudeForegroundOnWatch(): Flow<Boolean> = libPebble.watches.flatMapLatest { all ->
        val connected = all.filterIsInstance<ConnectedPebbleDevice>()
        if (connected.isEmpty()) {
            flowOf(false)
        } else {
            combine(connected.map { it.runningApp }) { apps -> CLAUDE_APP_UUID in apps }
        }
    }.distinctUntilChanged()

    private fun everyMinute(): Flow<Unit> = flow {
        while (true) {
            delay(60.seconds)
            emit(Unit)
        }
    }

    private fun markSeen() {
        val now = nowMs()
        change { it.copy(alerts = it.alerts.copy(seenUntilMs = maxOf(it.alerts.seenUntilMs, now))) }
    }

    private fun nowMs() = clock.now().toEpochMilliseconds()

    private fun change(transform: (ClaudeNotificationsData) -> ClaudeNotificationsData) {
        _data.update(transform)
        try {
            settings.putString(SETTINGS_KEY, json.encodeToString(ClaudeNotificationsData.serializer(), _data.value))
        } catch (e: Exception) {
            logger.w(e) { "Couldn't save" }
        }
    }

    private fun load(): ClaudeNotificationsData = try {
        settings.getStringOrNull(SETTINGS_KEY)?.let { json.decodeFromString(ClaudeNotificationsData.serializer(), it) }
    } catch (e: Exception) {
        logger.w(e) { "Couldn't load" }
        null
    } ?: ClaudeNotificationsData()

    private companion object {
        const val SETTINGS_KEY = "claude_notifications"
    }
}

private const val REPLY_PAGE = 10

/**
 * Reads the bridge and plans the alerts. [state] is read after the bridge's clock, so a
 * foreground Claude app can count as seen up to that moment.
 */
internal suspend fun fetchClaudeAlerts(
    bridge: ClaudeBridgeClient,
    pairing: ClaudePairing,
    now: () -> Long,
    state: () -> ClaudeAlertState,
): ClaudeAlertPlan {
    val status = bridge.status(pairing)
    val phoneNow = now()
    val current = state()
    val cursor = current.cursor
    val replies = if (cursor != null && status.state.lastReplyId > cursor) {
        bridge.replies(pairing, after = cursor, limit = REPLY_PAGE).replies
    } else {
        emptyList()
    }
    val snapshot = ClaudeBridgeSnapshot(
        clockOffsetMs = status.now - phoneNow,
        sessionName = status.state.name,
        lastReplyId = status.state.lastReplyId,
        replies = replies,
        permissions = bridge.permissions(pairing),
    )
    return planClaudeAlerts(current, snapshot)
}
