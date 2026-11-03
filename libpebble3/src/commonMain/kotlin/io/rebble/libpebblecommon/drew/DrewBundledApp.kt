package io.rebble.libpebblecommon.drew

import io.rebble.libpebblecommon.connection.AppContext

/**
 * Reads a pbw shipped inside the app under `bundled-apps/<fileName>`, or null when it isn't there.
 * The workspace's `scripts/mobileapp_bundle_apps.py` puts them there before a build.
 */
expect fun readDrewBundledApp(appContext: AppContext, fileName: String): ByteArray?
