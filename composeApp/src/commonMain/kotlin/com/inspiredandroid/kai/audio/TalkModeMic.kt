package com.inspiredandroid.kai.audio

/**
 * Keeps the microphone accessible for talk mode.
 *
 * Android 14+ mutes the mic for apps in the background unless a foreground service of type
 * `microphone` is running, so on Android this promotes the daemon service to that type while
 * talk mode is active. Other platforms have no such restriction.
 */
expect object TalkModeMic {
    fun setActive(active: Boolean)
}
