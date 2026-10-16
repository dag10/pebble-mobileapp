package io.rebble.libpebblecommon.drew

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * Temporary, DrewMobileApp-only record of the connection and iOS Focus experiments: their
 * toggles, plus counters and a rolling event log that survive relaunches, shown in
 * Settings > Drew Experiments. See docs/ios-focus-quiet-time.md in pebble-workspace.
 */
object DrewExperiments {
    const val APP_LAUNCH_READ = "App Launch reads from the watch"
    const val HEARTBEAT_PING = "Heartbeat pings from the watch"
    const val BLE_RESTORE = "Relaunches by Bluetooth state restoration"
    const val APP_LAUNCH = "App process launches"
    const val WATCH_CONNECTED = "Watch connections"
    const val WATCH_DISCONNECTED = "Watch disconnections"
    const val FOCUS_FILTER_RUN = "Focus Filter runs"
    const val FOCUS_CATCH_UP = "Focus catch-up corrections"
    const val FOCUS_RESYNC_FIX = "Launch/foreground Focus fixes"
    const val FOCUS_TIMEOUT = "Focus sends not confirmed in 20s"

    val COUNTERS = listOf(
        HEARTBEAT_PING, APP_LAUNCH_READ, BLE_RESTORE, APP_LAUNCH, WATCH_CONNECTED,
        WATCH_DISCONNECTED, FOCUS_FILTER_RUN, FOCUS_CATCH_UP, FOCUS_RESYNC_FIX, FOCUS_TIMEOUT,
    )

    @Serializable
    data class Counter(
        val count: Int = 0,
        val lastMs: Long? = null,
        val previousMs: Long? = null,
    )

    @Serializable
    data class Entry(val ms: Long, val message: String)

    @Serializable
    data class State(
        val counters: Map<String, Counter> = emptyMap(),
        val log: List<Entry> = emptyList(),
        val sinceMs: Long? = null,
        /** The Focus catch-up check sends the state iOS reports, rather than only logging drift. */
        val focusCatchUpSends: Boolean = true,
        /** Post a "Focus debug" iPhone notification for Focus events. */
        val focusNotifications: Boolean = true,
    )

    private const val SETTINGS_KEY = "drew_experiments"
    private const val MAX_LOG_ENTRIES = 300
    private val logger = Logger.withTag("DrewExperiments")
    private val json = Json { ignoreUnknownKeys = true }
    private val settings: Settings by lazy { Settings() }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Counts [counter], and logs [message] if there is one. */
    fun count(counter: String, message: String? = null) {
        val now = Clock.System.now().toEpochMilliseconds()
        change { state ->
            val previous = state.counters[counter] ?: Counter()
            state.copy(
                counters = state.counters + (counter to Counter(previous.count + 1, now, previous.lastMs)),
                log = if (message != null) state.log.withEntry(Entry(now, message)) else state.log,
            )
        }
    }

    fun log(message: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        change { it.copy(log = it.log.withEntry(Entry(now, message))) }
    }

    fun setFocusCatchUpSends(sends: Boolean) = change { it.copy(focusCatchUpSends = sends) }

    fun setFocusNotifications(notify: Boolean) = change { it.copy(focusNotifications = notify) }

    /** Clears the counters and the log, keeping the toggles. */
    fun reset() = change {
        it.copy(counters = emptyMap(), log = emptyList(), sinceMs = Clock.System.now().toEpochMilliseconds())
    }

    private fun List<Entry>.withEntry(entry: Entry) = (this + entry).takeLast(MAX_LOG_ENTRIES)

    private fun change(transform: (State) -> State) {
        _state.update(transform)
        try {
            settings.putString(SETTINGS_KEY, json.encodeToString(State.serializer(), _state.value))
        } catch (e: Exception) {
            logger.w(e) { "Couldn't save" }
        }
    }

    private fun load(): State = try {
        settings.getStringOrNull(SETTINGS_KEY)?.let { json.decodeFromString(State.serializer(), it) }
    } catch (e: Exception) {
        logger.w(e) { "Couldn't load" }
        null
    } ?: State(sinceMs = Clock.System.now().toEpochMilliseconds())
}
