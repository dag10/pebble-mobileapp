package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.database.entity.LockerEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class KeepingPlaceTest {
    private val id = Uuid.random()

    private fun entry(version: String, orderIndex: Int, active: Boolean) = LockerEntry(
        id = id,
        version = version,
        title = "Quartz",
        type = "watchface",
        developerName = "drew",
        configurable = true,
        pbwVersionCode = "1",
        sideloaded = true,
        platforms = emptyList(),
        orderIndex = orderIndex,
        active = active,
    )

    @Test
    fun keepsOrderAndActiveOfExistingEntry() {
        val updated = entry("1.82", orderIndex = -1, active = false)
            .keepingPlaceOf(entry("1.81", orderIndex = 4, active = true))
        assertEquals(entry("1.82", orderIndex = 4, active = true), updated)
    }

    @Test
    fun leavesNewEntryAloneWithoutExisting() {
        val new = entry("1.82", orderIndex = -1, active = false)
        assertEquals(new, new.keepingPlaceOf(null))
    }
}
