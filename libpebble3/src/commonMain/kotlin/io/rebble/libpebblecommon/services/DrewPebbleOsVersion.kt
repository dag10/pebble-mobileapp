package io.rebble.libpebblecommon.services

private val DREW_BUILD_REGEX = Regex("-drew\\.([0-9]+)")

/** The N in a `vX.Y.Z-drew.N` version, or null for a build that isn't DrewPebbleOS. */
fun drewBuildNumber(version: String): Int? =
    DREW_BUILD_REGEX.find(version)?.groupValues?.get(1)?.toIntOrNull()

fun FirmwareVersion.isDrewPebbleOs(): Boolean = drewBuildNumber(stringVersion) != null
