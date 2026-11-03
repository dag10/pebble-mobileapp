package io.rebble.libpebblecommon.drew

import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/** The Xcode project references `iosApp/bundled-apps` as a folder, so its files land in the bundle as-is. */
@OptIn(ExperimentalForeignApi::class)
actual fun readDrewBundledApp(appContext: AppContext, fileName: String): ByteArray? {
    val resources = NSBundle.mainBundle.resourcePath ?: return null
    val data = NSData.dataWithContentsOfFile("$resources/bundled-apps/$fileName") ?: return null
    if (data.length.toInt() == 0) return null
    return ByteArray(data.length.toInt()).apply {
        usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    }
}
