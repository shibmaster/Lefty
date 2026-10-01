package com.inspiredandroid.kai.tunnel

import com.inspiredandroid.kai.data.AppSettings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private class FakeBridge : WireGuardBridge {
    override var isSupported = true
    var starts = mutableListOf<String>()
    var stops = 0
    var running = false
    var status = BridgeStatus()
    var failStart: String? = null

    override fun start(uapi: String, addresses: String, dns: String, mtu: Int): BridgeSession {
        failStart?.let { throw IllegalStateException(it) }
        starts += uapi
        running = true
        return BridgeSession(port = 40000 + starts.size, token = "token-${starts.size}")
    }

    override fun stop() {
        stops++
        running = false
    }

    override fun status(): String = if (!running) {
        """{"up":false}"""
    } else {
        """{"up":true,"last_handshake_ago_sec":${status.lastHandshakeAgoSec},"rx_bytes":${status.rxBytes},"tx_bytes":${status.txBytes},"active_requests":${status.activeRequests},"idle_ms":${status.idleMs}}"""
    }

    override fun resolveHost(host: String) = "203.0.113.7"
}

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelManagerTest {

    private val bridge = FakeBridge()
    private val settings = AppSettings(MapSettings())

    private fun TestScope.manager() = TunnelManager(
        bridge = bridge,
        settings = settings,
        scope = backgroundScope,
        resolveHost = { bridge.resolveHost(it) },
        monitorInterval = 5.seconds,
    )

    @Test
    fun `only enabled tunnels route their hosts`() = runTest {
        val m = manager()
        assertFalse(m.shouldTunnel("192.168.4.103"))
        assertTrue(m.importConfig(CLIENT_CONF).isSuccess)
        assertTrue(m.isEnabled, "importing turns the tunnel on")
        assertTrue(m.shouldTunnel("192.168.4.103"))
        assertFalse(m.shouldTunnel("api.openai.com"))
        m.setEnabled(false)
        assertFalse(m.shouldTunnel("192.168.4.103"))
        m.setEnabled(true)
        bridge.isSupported = false
        assertFalse(m.shouldTunnel("192.168.4.103"))
    }

    @Test
    fun `custom routes replace the config's AllowedIPs`() = runTest {
        val m = manager()
        m.importConfig(CLIENT_CONF.replace("10.8.0.0/24, 192.168.4.0/24", "0.0.0.0/0"))
        assertTrue(m.routes()!!.coversEverything)
        assertTrue(m.shouldTunnel("api.openai.com"))
        m.setCustomRoutes("192.168.4.0/24, llama.home")
        assertFalse(m.shouldTunnel("api.openai.com"))
        assertTrue(m.shouldTunnel("llama.home"))
        m.setCustomRoutes("")
        assertTrue(m.shouldTunnel("api.openai.com"), "empty means AllowedIPs again")
    }

    @Test
    fun `ensureUp starts once, resolves the endpoint and reuses the session`() = runTest {
        val m = manager()
        m.importConfig(CLIENT_CONF)
        val first = m.ensureUp()
        val second = m.ensureUp()
        assertEquals(first, second)
        assertEquals(1, bridge.starts.size)
        assertTrue("endpoint=203.0.113.7:51820" in bridge.starts.single())
        assertIs<TunnelState.Up>(m.state.value)
        assertEquals(first, m.currentSession)
    }

    @Test
    fun `start failures surface as tunnel errors`() = runTest {
        val m = manager()
        assertFailsWith<TunnelException> { m.ensureUp() } // nothing imported
        m.importConfig(CLIENT_CONF)
        bridge.failStart = "wireguard config: bad key"
        val e = assertFailsWith<TunnelException> { m.ensureUp() }
        assertTrue(e.message!!.contains("bad key"))
        assertIs<TunnelState.Error>(m.state.value)
    }

    @Test
    fun `stops after the idle time, not while a request is in flight`() = runTest {
        settings.setWireGuardIdleMinutes(1)
        val m = manager()
        m.importConfig(CLIENT_CONF)
        m.ensureUp()

        bridge.status = BridgeStatus(up = true, lastHandshakeAgoSec = 3, rxBytes = 10, txBytes = 20, activeRequests = 1, idleMs = 120_000)
        advanceTimeBy(6.seconds)
        runCurrent()
        assertTrue(bridge.running, "a long silent request keeps it up")
        assertEquals(TunnelState.Up(3, 10, 20), m.state.value)

        bridge.status = bridge.status.copy(activeRequests = 0, idleMs = 30_000)
        advanceTimeBy(5.seconds)
        runCurrent()
        assertTrue(bridge.running)

        bridge.status = bridge.status.copy(idleMs = 61_000)
        advanceTimeBy(5.seconds)
        runCurrent()
        assertFalse(bridge.running)
        assertEquals(TunnelState.Off, m.state.value)
        assertEquals(null, m.currentSession)

        // The next request brings it back with a fresh session.
        assertEquals(40002, m.ensureUp().port)
    }

    @Test
    fun `connectNow waits for the handshake`() = runTest {
        val m = manager()
        m.importConfig(CLIENT_CONF)
        bridge.status = BridgeStatus(up = true, lastHandshakeAgoSec = -1)
        assertFalse(m.connectNow(timeoutSeconds = 1))
        assertIs<TunnelState.Error>(m.state.value)

        bridge.status = BridgeStatus(up = true, lastHandshakeAgoSec = 0, rxBytes = 92, txBytes = 148)
        assertTrue(m.connectNow())
        assertEquals(TunnelState.Up(0, 92, 148), m.state.value)
    }

    @Test
    fun `importing or removing a config restarts or ends the tunnel`() = runTest {
        val m = manager()
        m.importConfig(CLIENT_CONF)
        m.ensureUp()
        assertTrue(m.importConfig("garbage").isFailure)
        assertTrue(bridge.running, "a rejected import changes nothing")
        m.importConfig(CLIENT_CONF.replace("MTU = 1380", "MTU = 1280"))
        assertFalse(bridge.running)
        m.ensureUp()
        m.removeConfig()
        assertFalse(bridge.running)
        assertEquals(null, settings.getWireGuardConf())
        assertFalse(m.isEnabled)
    }
}
