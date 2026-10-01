package com.inspiredandroid.kai

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.inspiredandroid.kai.data.AppSettings
import org.koin.java.KoinJavaComponent.inject

actual fun createDaemonController(): DaemonController = AndroidDaemonController()

class AndroidDaemonController : DaemonController {

    private val context: Context by inject(Context::class.java)
    private val appSettings: AppSettings by inject(AppSettings::class.java)

    fun shouldAutoStart(): Boolean = appSettings.isDaemonEnabled()

    override fun start() {
        try {
            val intent = Intent(context, DaemonService::class.java)
            context.startForegroundService(intent)
        } catch (_: ForegroundServiceStartNotAllowedException) {
            // App is not in a foreground state — cannot start foreground service (Android 12+)
        }
    }

    override fun stop() {
        val intent = Intent(context, DaemonService::class.java)
        context.stopService(intent)
    }

    override fun isBatteryOptimizationExempt(): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    @SuppressLint("BatteryLife") // Personal foss build: the daemon must keep running for heartbeats.
    override fun requestBatteryOptimizationExemption() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        for (intent in listOf(direct, fallback)) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
                // Not declared (Play flavor) or no handler on this OEM build — try the next one.
            }
        }
    }
}
