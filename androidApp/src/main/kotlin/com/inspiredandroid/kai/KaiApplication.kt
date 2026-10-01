package com.inspiredandroid.kai

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.inspiredandroid.kai.data.TaskScheduler
import com.inspiredandroid.kai.sandbox.sandboxModule
import com.inspiredandroid.kai.tunnel.WireGuardBridge
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * Registers the Go WireGuard bridge when this build bundles it (see androidApp/build.gradle.kts);
 * otherwise the tunnel reports itself as unsupported.
 */
private fun wireGuardModule() = module {
    val bridge = runCatching {
        Class.forName("com.inspiredandroid.kai.GoWireGuardBridge").getDeclaredConstructor().newInstance() as WireGuardBridge
    }.getOrNull()
    if (bridge != null) single<WireGuardBridge> { bridge }
}

class KaiApplication : Application() {

    private val taskScheduler: TaskScheduler by inject()

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@KaiApplication)
            modules(appModule, sandboxModule, wireGuardModule())
        }
        // Track app foreground state so the scheduler only pushes a heartbeat notification
        // when the in-app banner isn't visible. ViewModel lifecycle is the wrong signal —
        // it survives backgrounding and only clears on Activity destruction.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                taskScheduler.appInForeground = true
            }
            override fun onStop(owner: LifecycleOwner) {
                taskScheduler.appInForeground = false
            }
        })
    }
}
