package com.inspiredandroid.kai.tunnel

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.httpClient
import com.inspiredandroid.kai.network.tunnelFailure
import com.russhwolf.settings.MapSettings
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The OkHttp side of the tunnel: requests for tunnel hosts go to the bridge's local proxy with the
 * session token, everything else goes directly. The "proxy" and the "direct" server here are plain
 * sockets that record what they receive.
 */
class TunnelProxyTest {

    /** Accepts HTTP/1.1 requests, records request line + headers, answers 200 with [body]. */
    private class RecordingServer(private val body: String) {
        val socket = ServerSocket(0)
        val requests: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
        val port get() = socket.localPort

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        client.use { c ->
                            val reader = c.getInputStream().bufferedReader()
                            while (true) {
                                val head = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                                if (head.isEmpty()) break
                                requests += head
                                val out = c.getOutputStream()
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                                out.flush()
                            }
                        }
                    }
                }
            }
        }

        fun close() = socket.close()
    }

    private val proxy = RecordingServer("via-tunnel")
    private val direct = RecordingServer("direct")

    private val bridge = object : WireGuardBridge {
        override val isSupported = true
        override fun start(uapi: String, addresses: String, dns: String, mtu: Int) = BridgeSession(proxy.port, "secret-token")
        override fun stop() {}
        override fun status() = """{"up":true}"""
        override fun resolveHost(host: String) = "203.0.113.7"
    }

    @BeforeTest
    fun setup() {
        resetTunnelManagerCache()
        val settings = AppSettings(MapSettings())
        val manager = TunnelManager(bridge, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), { bridge.resolveHost(it) })
        manager.importConfig(CLIENT_CONF) // AllowedIPs 10.8.0.0/24, 192.168.4.0/24
        startKoin { modules(module { single { manager } }) }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        resetTunnelManagerCache()
        proxy.close()
        direct.close()
    }

    @Test
    fun `tunnel hosts go through the proxy with the token, others directly`() = runBlocking {
        val client = httpClient()

        val tunneled = client.get("http://192.168.4.103:41550/v1/models").bodyAsText()
        assertEquals("via-tunnel", tunneled)
        val head = proxy.requests.single()
        assertEquals("GET http://192.168.4.103:41550/v1/models HTTP/1.1", head.first())
        assertTrue("Proxy-Authorization: Bearer secret-token" in head, head.joinToString("\n"))

        val notTunneled = client.get("http://127.0.0.1:${direct.port}/v1/models").bodyAsText()
        assertEquals("direct", notTunneled)
        val directHead = direct.requests.single()
        assertEquals("GET /v1/models HTTP/1.1", directHead.first())
        assertTrue(directHead.none { it.startsWith("Proxy-Authorization") }, "the token never leaves for other hosts")
        assertEquals(1, proxy.requests.size)
        client.close()
    }

    @Test
    fun `disabled tunnel sends nothing to the proxy`() = runBlocking {
        org.koin.java.KoinJavaComponent.getKoin().get<TunnelManager>().setEnabled(false)
        val client = httpClient()
        // 192.168.4.103 is unreachable from the test machine; only the routing decision matters.
        runCatching { client.get("http://192.168.4.103:1/") }
        assertTrue(proxy.requests.isEmpty())
        client.close()
    }

    @Test
    fun `a tunnel that can't start fails the request with its reason`() = runBlocking {
        stopKoin()
        resetTunnelManagerCache()
        val failing = object : WireGuardBridge by bridge {
            override fun start(uapi: String, addresses: String, dns: String, mtu: Int): BridgeSession = throw IllegalStateException("wireguard config: bad key")
        }
        val manager = TunnelManager(failing, AppSettings(MapSettings()), CoroutineScope(SupervisorJob() + Dispatchers.IO), { "203.0.113.7" })
        manager.importConfig(CLIENT_CONF)
        startKoin { modules(module { single { manager } }) }

        val client = httpClient()
        val error = runCatching { client.get("http://192.168.4.103:41550/v1/models") }.exceptionOrNull()
        val mapped = (error as Exception).tunnelFailure()
        assertTrue(mapped?.message?.contains("bad key") == true, "got ${error::class.simpleName}: ${error.message}")
        assertTrue(proxy.requests.isEmpty(), "no direct fallback, nothing sent")
        client.close()
    }
}
