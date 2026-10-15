package io.rebble.libpebblecommon.database.entity

import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.dao.ValueParams
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.services.FirmwareVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.time.Instant

private fun params(version: String) = ValueParams(
    platform = WatchType.EMERY,
    capabilities = emptySet(),
    firmwareVersion = FirmwareVersion.from(
        tag = version,
        isRecovery = false,
        gitHash = "",
        timestamp = Instant.DISTANT_PAST,
        isDualSlot = false,
        isSlot0 = false,
    )!!,
    libPebbleConfigFlow = LibPebbleConfigFlow(MutableStateFlow(LibPebbleConfig())),
)

private fun item(pref: WatchPref<*>) = WatchPrefItem(
    id = pref.id,
    value = "1",
    timestamp = Instant.fromEpochSeconds(1757376000).asMillisecond(),
)

class DrewPebbleOsWatchPrefTest {
    @Test
    fun focusStateIsSentToDrewPebbleOs() {
        val params = params("v4.41.1-drew.2")
        assertContentEquals(ubyteArrayOf(1u), item(BoolWatchPref.IosFocusActive).value(params))
    }

    @Test
    fun focusStateIsNotSentToOtherFirmware() {
        val params = params("v4.41.1")
        assertNull(item(BoolWatchPref.IosFocusActive).value(params))
        assertContentEquals(ubyteArrayOf(1u), item(BoolWatchPref.CalendarAwareQuietTime).value(params))
    }
}
