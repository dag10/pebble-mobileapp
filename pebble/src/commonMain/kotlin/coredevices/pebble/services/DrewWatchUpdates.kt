package coredevices.pebble.services

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import coredevices.pebble.firmware.DrewPebbleOsUpdateCheck
import coredevices.pebble.ui.message
import coredevices.util.Platform
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import io.rebble.libpebblecommon.packets.DrewUpdatesMessage
import io.rebble.libpebblecommon.packets.DrewUpdatesMessage.InstallState
import io.rebble.libpebblecommon.packets.DrewUpdatesMessage.ListStatus
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * DrewMobileApp only: answers DrewPebbleOS's Settings > Updates page ([DrewUpdatesMessage]) with
 * the DrewPebbleOS, official PebbleOS and [DREW_APPS] updates, and installs one when asked. Talks
 * only to watches that announce [ProtocolCapsFlag.SupportsDrewUpdates], and only in answer to them.
 * The protocol is specified in pebble-workspace's docs/drew-os-extensions.md.
 */
class DrewWatchUpdates(
    private val libPebble: LibPebble,
    private val settings: Settings,
    private val clock: Clock,
    private val drewAppUpdates: DrewAppUpdates,
    private val drewPebbleOsUpdateCheck: DrewPebbleOsUpdateCheck,
    private val platform: Platform,
) {
    private val logger = Logger.withTag("DrewWatchUpdates")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val sendMutex = Mutex()
    private val checks = mutableMapOf<String, Job>()
    private val installing = mutableMapOf<String, String>()
    private var nextListId: UShort = 0u

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            libPebble.watches
                .map { watches -> watches.filterIsInstance<ConnectedPebbleDevice>().filter { it.speaksDrewUpdates() } }
                // A connection's inbound flow is the same object for as long as it lasts.
                .distinctUntilChangedBy { watches -> watches.map { it.inboundMessages } }
                .flatMapLatest { watches ->
                    watches.map { watch ->
                        watch.inboundMessages.filterIsInstance<DrewUpdatesMessage>().map { watch.identifier to it }
                    }.merge()
                }
                .collect { (identifier, message) ->
                    when (message) {
                        is DrewUpdatesMessage.ListRequest ->
                            scope.launch { onListRequest(scope, identifier, message.recheck) }
                        is DrewUpdatesMessage.InstallRequest ->
                            scope.launch { onInstallRequest(identifier, message.id.get()) }
                        else -> Unit
                    }
                }
        }
    }

    private suspend fun onListRequest(scope: CoroutineScope, identifier: PebbleIdentifier, recheck: Boolean) {
        val serial = connected(identifier)?.watchInfo?.serial ?: return
        logger.d { "List requested (recheck=$recheck)" }
        val (newCheck, checking) = mutex.withLock {
            val running = checks[serial]?.isCompleted == false
            val job = if (recheck && !running) {
                scope.launch(start = CoroutineStart.LAZY) { recheck(identifier, serial) }.also { checks[serial] = it }
            } else {
                null
            }
            job to (job != null || running)
        }
        sendList(identifier, load(serial), checking)
        newCheck?.start()
    }

    private suspend fun recheck(identifier: PebbleIdentifier, serial: String) {
        platform.runWithBgTask("drew-watch-updates-check") {
            val list = withTimeoutOrNull(CHECK_TIMEOUT) { check(identifier, force = true) } ?: run {
                if (connected(identifier) == null) {
                    return@runWithBgTask
                }
                load(serial).copy(error = "Check timed out").also { save(serial, it) }
            }
            sendList(identifier, list, checking = false)
        }
    }

    /** Null if the watch disconnected. */
    private suspend fun check(identifier: PebbleIdentifier, force: Boolean): WatchUpdateList? {
        val watch = connected(identifier) ?: return null
        val info = watch.watchInfo
        if (force) {
            // Store apps update themselves when the locker sync sees a new version.
            libPebble.requestLockerSync()
        }
        return coroutineScope {
            val drewOs = async { drewPebbleOsUpdateCheck.checkForUpdate(info, force) }
            val apps = async { installedAppUpdates(force) }
            val official = (watch.firmwareUpdateAvailable.result as? FirmwareUpdateCheckResult.FoundUpdate)
                ?.takeIf { drewPebbleOsUpdateCheck.shouldNotifyVanillaUpdate(it.version, info.runningFwVersion) }
            val drewOsResult = drewOs.await()
            buildWatchUpdateList(
                previous = load(info.serial),
                drewOs = drewOsResult,
                drewOsSizeBytes = (drewOsResult as? FirmwareUpdateCheckResult.FoundUpdate)
                    ?.let { drewPebbleOsUpdateCheck.downloadSize(it) } ?: 0,
                official = official,
                apps = apps.await(),
                running = info.runningFwVersion,
                now = clock.now(),
            ).also { save(info.serial, it) }
        }
    }

    private suspend fun installedAppUpdates(force: Boolean): List<InstalledAppUpdate> = coroutineScope {
        DREW_APPS.map { app ->
            async {
                val update = drewAppUpdates.checkForUpdate(app.uuid, force) ?: return@async null
                val properties = libPebble.getLockerApp(app.uuid).first()?.properties ?: return@async null
                InstalledAppUpdate(update, properties.title, properties.type, properties.version)
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun onInstallRequest(identifier: PebbleIdentifier, id: String) {
        val serial = connected(identifier)?.watchInfo?.serial ?: return
        logger.i { "Install requested: $id" }
        val accepted = mutex.withLock {
            (serial !in installing).also { if (it) installing[serial] = id }
        }
        if (!accepted) {
            sendStatus(identifier, id, InstallState.Failed, message = "Another update is installing")
            return
        }
        var installedApp = false
        try {
            platform.runWithBgTask("drew-watch-updates-install") {
                val appUuid = appUuidFromUpdateId(id)
                when {
                    id == DREW_OS_UPDATE_ID -> installFirmware(identifier, id) { watch ->
                        drewPebbleOsUpdateCheck.checkForUpdate(watch.watchInfo, force = false)
                    }
                    id == OFFICIAL_OS_UPDATE_ID -> installFirmware(identifier, id) { watch ->
                        watch.firmwareUpdateAvailable.result
                    }
                    appUuid != null -> {
                        installedApp = installApp(identifier, id, appUuid)
                    }
                    else -> sendStatus(identifier, id, InstallState.Failed, message = "Unknown update")
                }
            }
        } finally {
            mutex.withLock { installing.remove(serial) }
        }
        if (installedApp) {
            check(identifier, force = false)?.let { sendList(identifier, it, checking = false) }
        }
    }

    private suspend fun installFirmware(
        identifier: PebbleIdentifier,
        id: String,
        find: suspend (ConnectedPebbleDevice) -> FirmwareUpdateCheckResult?,
    ) {
        val watch = connected(identifier) ?: return
        val update = find(watch) as? FirmwareUpdateCheckResult.FoundUpdate
        if (update == null) {
            sendStatus(identifier, id, InstallState.Failed, message = "No longer available")
            return
        }
        val initial = watch.firmwareUpdateState
        if (initial !is FirmwareUpdateStatus.NotInProgress) {
            sendStatus(identifier, id, InstallState.Failed, message = "A firmware update is already running")
            return
        }
        sendStatus(identifier, id, InstallState.Starting)
        watch.updateFirmware(update)
        reportFirmwareProgress(identifier, id, initial)
    }

    private suspend fun reportFirmwareProgress(
        identifier: PebbleIdentifier,
        id: String,
        initial: FirmwareUpdateStatus,
    ) {
        val started = clock.now()
        var active = false
        var lastPercent = -1
        while (clock.now() - started < FIRMWARE_TIMEOUT) {
            // Gone while active: the watch is rebooting into it.
            val watch = connected(identifier) ?: return
            when (val state = watch.firmwareUpdateState) {
                is FirmwareUpdateStatus.WaitingToStart -> active = true
                is FirmwareUpdateStatus.InProgress -> {
                    active = true
                    val percent = (state.progress.value * 100).toInt().coerceIn(0, 100)
                    if (percent >= lastPercent + PROGRESS_STEP || (percent == 100 && lastPercent != 100)) {
                        sendStatus(identifier, id, InstallState.Transferring, percent.toUByte())
                        lastPercent = percent
                    }
                }
                is FirmwareUpdateStatus.WaitingForReboot -> {
                    sendStatus(identifier, id, InstallState.Rebooting, 100u)
                    return
                }
                is FirmwareUpdateStatus.NotInProgress.ErrorStarting -> if (state !== initial) {
                    sendStatus(identifier, id, InstallState.Failed, message = state.error.message())
                    return
                }
                is FirmwareUpdateStatus.NotInProgress.Idle -> if (active || clock.now() - started > START_TIMEOUT) {
                    val reason = state.lastFailure?.message ?: if (active) "Update stopped" else "Update didn't start"
                    sendStatus(identifier, id, InstallState.Failed, message = reason)
                    return
                }
            }
            delay(1.seconds)
        }
    }

    /** True if the app was installed. */
    private suspend fun installApp(identifier: PebbleIdentifier, id: String, uuid: Uuid): Boolean {
        val update = drewAppUpdates.checkForUpdate(uuid, force = false)
        if (update == null) {
            sendStatus(identifier, id, InstallState.Failed, message = "Already up to date")
            return false
        }
        sendStatus(identifier, id, InstallState.Starting)
        sendStatus(identifier, id, InstallState.Downloading)
        val installed = drewAppUpdates.install(update) {
            sendStatus(identifier, id, InstallState.Transferring)
        } && libPebble.waitUntilAppSyncedToWatch(uuid, identifier, APP_SYNC_TIMEOUT)
        if (installed) {
            sendStatus(identifier, id, InstallState.Done, 100u)
        } else {
            sendStatus(identifier, id, InstallState.Failed, message = "Couldn't install")
        }
        return installed
    }

    private suspend fun sendList(identifier: PebbleIdentifier, list: WatchUpdateList, checking: Boolean) {
        val serial = connected(identifier)?.watchInfo?.serial ?: return
        val (listId, installingId) = mutex.withLock { nextListId++ to installing[serial] }
        val status = when {
            checking -> ListStatus.Checking
            list.error != null -> ListStatus.CheckFailed
            else -> ListStatus.Ready
        }
        val entries = list.entries.take(MAX_ENTRIES)
        logger.d { "Sending list $listId ($status): ${entries.map { it.id }}" }
        sendMutex.withLock {
            send(
                identifier,
                DrewUpdatesMessage.ListHeader(
                    listId = listId,
                    status = status,
                    entryCount = entries.size.toUByte(),
                    lastCheckedUtc = list.lastCheckedUtc.coerceIn(0, UInt.MAX_VALUE.toLong()).toUInt(),
                    message = list.error.orEmpty(),
                ),
            )
            entries.forEachIndexed { index, entry ->
                send(identifier, entry.toPacket(listId, index, installing = entry.id == installingId))
            }
        }
    }

    private suspend fun sendStatus(
        identifier: PebbleIdentifier,
        id: String,
        state: InstallState,
        percent: UByte = DrewUpdatesMessage.InstallStatus.PERCENT_UNKNOWN,
        message: String = "",
    ) {
        logger.d { "Install $id: $state $percent $message" }
        sendMutex.withLock { send(identifier, DrewUpdatesMessage.InstallStatus(id, state, percent, message)) }
    }

    private suspend fun send(identifier: PebbleIdentifier, packet: PebblePacket) {
        try {
            connected(identifier)?.sendPPMessage(packet)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "Couldn't send to the watch" }
        }
    }

    private fun connected(identifier: PebbleIdentifier): ConnectedPebbleDevice? =
        libPebble.watches.value.filterIsInstance<ConnectedPebbleDevice>()
            .find { it.identifier == identifier && it.speaksDrewUpdates() }

    private fun ConnectedPebbleDevice.speaksDrewUpdates() =
        ProtocolCapsFlag.SupportsDrewUpdates in watchInfo.capabilities

    private fun load(serial: String): WatchUpdateList = try {
        settings.getStringOrNull(settingsKey(serial))?.let { json.decodeFromString(WatchUpdateList.serializer(), it) }
    } catch (e: Exception) {
        logger.w(e) { "Couldn't load the saved list" }
        null
    } ?: WatchUpdateList()

    private fun save(serial: String, list: WatchUpdateList) {
        settings.putString(settingsKey(serial), json.encodeToString(WatchUpdateList.serializer(), list))
    }

    private fun settingsKey(serial: String) = "drew_watch_updates_$serial"

    companion object {
        private val CHECK_TIMEOUT = 45.seconds
        private val START_TIMEOUT = 20.seconds
        private val FIRMWARE_TIMEOUT = 20.minutes
        private val APP_SYNC_TIMEOUT = 60.seconds
        private const val PROGRESS_STEP = 5
        private const val MAX_ENTRIES = 32
    }
}
