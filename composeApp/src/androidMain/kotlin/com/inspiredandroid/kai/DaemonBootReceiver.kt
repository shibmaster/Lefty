package com.inspiredandroid.kai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the daemon after a reboot or an app update (installing a new APK kills the running
 * service), so heartbeats and scheduled tasks resume without the app being opened.
 */
class DaemonBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Koin is started in KaiApplication.onCreate, which runs before any receiver.
                val controller = AndroidDaemonController()
                if (controller.shouldAutoStart()) controller.start()
            }
        }
    }
}
