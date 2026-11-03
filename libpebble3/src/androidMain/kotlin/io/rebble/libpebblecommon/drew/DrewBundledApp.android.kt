package io.rebble.libpebblecommon.drew

import io.rebble.libpebblecommon.connection.AppContext
import java.io.IOException

actual fun readDrewBundledApp(appContext: AppContext, fileName: String): ByteArray? = try {
    appContext.context.assets.open("bundled-apps/$fileName").use { it.readBytes() }
} catch (e: IOException) {
    null
}
