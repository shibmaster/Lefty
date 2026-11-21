package com.inspiredandroid.kai.audio

import android.content.Context
import android.content.Intent
import com.inspiredandroid.kai.TalkModeService
import org.koin.java.KoinJavaComponent

actual object TalkModeMic {

    actual fun setActive(active: Boolean) {
        val context: Context = KoinJavaComponent.getKoin().get()
        val intent = Intent(context, TalkModeService::class.java)
        try {
            if (active) {
                context.startForegroundService(intent)
            } else {
                context.stopService(intent)
            }
        } catch (_: Exception) {
            // Foreground-service start limits (Android 12+): the mic stays muted, same as before.
        }
    }
}
