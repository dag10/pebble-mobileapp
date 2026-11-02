package io.rebble.libpebblecommon.plugin

import kotlinx.coroutines.flow.Flow

expect class PhoneSoundPlayer {
    val isRinging: Flow<Boolean>
    /**
     * @param raiseVolume Also raise the media volume while ringing, so a phone left quiet or muted
     * is still heard, and put it back afterwards.
     */
    fun startRinging(raiseVolume: Boolean = false)
    fun stopRinging()
}
