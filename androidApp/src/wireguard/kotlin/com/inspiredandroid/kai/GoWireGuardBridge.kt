package com.inspiredandroid.kai

import ai.shibmaster.lefty.wgbridge.Wgbridge
import com.inspiredandroid.kai.tunnel.BridgeSession
import com.inspiredandroid.kai.tunnel.WireGuardBridge
import java.net.InetAddress
import java.security.SecureRandom

/**
 * The gomobile-generated WireGuard bridge (wgbridge/). Created by reflection from KaiApplication, so
 * builds without the AAR still compile. The native library only exists for arm64 and x86_64; on
 * other devices loading it fails and the tunnel reports itself as unsupported.
 */
class GoWireGuardBridge : WireGuardBridge {

    override val isSupported: Boolean by lazy {
        runCatching {
            Wgbridge.touch() // loads libgojni.so
            true
        }.getOrDefault(false)
    }

    override fun start(uapi: String, addresses: String, dns: String, mtu: Int): BridgeSession {
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val port = Wgbridge.start(uapi, addresses, dns, mtu.toLong(), token)
        return BridgeSession(port.toInt(), token)
    }

    override fun stop() = Wgbridge.stop()

    override fun status(): String = Wgbridge.status()

    override fun resolveHost(host: String): String = InetAddress.getByName(host).hostAddress ?: host
}
