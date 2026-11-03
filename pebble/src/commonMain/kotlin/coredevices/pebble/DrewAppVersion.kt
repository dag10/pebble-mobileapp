package coredevices.pebble

import CoreAppVersion
import coredevices.util.CommonBuildKonfig

/** The drew tag this build was made from (e.g. `1.16.0.4-drew.3`), or the app version for other builds. */
fun CoreAppVersion.drewBuildVersion(): String = CommonBuildKonfig.GIT_HASH.takeIf { "-drew." in it } ?: version
