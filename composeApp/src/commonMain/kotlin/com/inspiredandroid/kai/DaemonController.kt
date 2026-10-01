package com.inspiredandroid.kai

interface DaemonController {
    fun start()
    fun stop()

    /** True when the OS won't throttle the daemon for battery reasons (always true off Android). */
    fun isBatteryOptimizationExempt(): Boolean = true

    /** Opens the system prompt to exempt the app from battery optimization. */
    fun requestBatteryOptimizationExemption() {}
}

/** Background daemon mode is Android-only; every other target gets this. */
class NoOpDaemonController : DaemonController {
    override fun start() {}
    override fun stop() {}
}

expect fun createDaemonController(): DaemonController
