package com.inspiredandroid.kai.tunnel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

const val PRIVATE_KEY = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
const val PUBLIC_KEY = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg="
private const val PRIVATE_HEX = "c809f3e5317e9575c9b5ed78b638b7ce530dabe85ddab614220241801ddf0669"
private const val PUBLIC_HEX = "c53201039adba14be71f886da1d8dbe9eebded08cb111b75340078999aa9f038"

// What the WireGuard app or a server tool exports, wg-quick extras included.
val CLIENT_CONF = """
    [Interface]
    # phone
    PrivateKey = $PRIVATE_KEY
    Address = 10.8.0.5/32, fd42::5/128
    DNS = 10.8.0.1, home.lan
    MTU = 1380
    ListenPort = 51820
    PostUp = iptables -A FORWARD -i wg0 -j ACCEPT

    [Peer]
    PublicKey = $PUBLIC_KEY
    PresharedKey = $PUBLIC_KEY
    Endpoint = vpn.example.org:51820
    AllowedIPs = 10.8.0.0/24, 192.168.4.0/24
""".trimIndent()

class WgConfigTest {

    @Test
    fun `parses a client config and lists what it ignores`() {
        val config = WgConfig.parse(CLIENT_CONF)
        assertEquals(listOf("10.8.0.5/32", "fd42::5/128"), config.addresses)
        assertEquals(listOf("10.8.0.1"), config.dns)
        assertEquals(1380, config.mtu)
        assertEquals(1, config.peers.size)
        assertEquals("vpn.example.org:51820", config.peers[0].endpoint)
        assertEquals(listOf("10.8.0.0/24", "192.168.4.0/24"), config.allowedIps.map { it.toString() })
        assertNull(config.peers[0].persistentKeepalive)
        assertEquals(listOf("ListenPort", "PostUp", "DNS search domains"), config.ignoredKeys)
        assertEquals(listOf("vpn.example.org"), config.endpointHostnames)
    }

    @Test
    fun `uapi uses hex keys, the resolved endpoint and a default keepalive`() {
        val uapi = WgConfig.parse(CLIENT_CONF).toUapi(mapOf("vpn.example.org" to "203.0.113.7"))
        assertEquals(
            """
            private_key=$PRIVATE_HEX
            replace_peers=true
            public_key=$PUBLIC_HEX
            preshared_key=$PUBLIC_HEX
            endpoint=203.0.113.7:51820
            persistent_keepalive_interval=25
            replace_allowed_ips=true
            allowed_ip=10.8.0.0/24
            allowed_ip=192.168.4.0/24
            """.trimIndent() + "\n",
            uapi,
        )
    }

    @Test
    fun `ipv6 endpoints keep their brackets and keepalive off is honored`() {
        val conf = """
            [Interface]
            PrivateKey = $PRIVATE_KEY
            Address = 10.0.0.2
            [Peer]
            PublicKey = $PUBLIC_KEY
            Endpoint = [2001:db8::1]:51820
            AllowedIPs = 0.0.0.0/0
            PersistentKeepalive = off
        """.trimIndent()
        val config = WgConfig.parse(conf)
        assertTrue(config.endpointHostnames.isEmpty())
        val uapi = config.toUapi()
        assertTrue("endpoint=[2001:db8::1]:51820" in uapi, uapi)
        assertTrue("persistent_keepalive_interval=0" in uapi, uapi)
    }

    @Test
    fun `server configs and broken files are rejected with a reason`() {
        val serverConf = """
            [Interface]
            Address = 10.8.0.1/24
            ListenPort = 51820
            [Peer]
            PublicKey = $PUBLIC_KEY
            AllowedIPs = 10.8.0.5/32
        """.trimIndent()
        assertTrue(assertFailsWith<WgConfigException> { WgConfig.parse(serverConf) }.message!!.contains("PrivateKey"))
        assertFailsWith<WgConfigException> { WgConfig.parse(CLIENT_CONF.replace(PRIVATE_KEY, "notakey")) }
        assertFailsWith<WgConfigException> { WgConfig.parse(CLIENT_CONF.replace("vpn.example.org:51820", "vpn.example.org")) }
        assertFailsWith<WgConfigException> { WgConfig.parse(CLIENT_CONF.substringBefore("[Peer]")) }
        assertFailsWith<WgConfigException> { WgConfig.parse(CLIENT_CONF.replace("192.168.4.0/24", "192.168.4.0/99")) }
        assertFailsWith<WgConfigException> { WgConfig.parse("{\"not\": \"a wireguard file\"}") }
        // A peer without an endpoint only answers; there has to be one to dial.
        assertFailsWith<WgConfigException> { WgConfig.parse(CLIENT_CONF.lines().filterNot { it.startsWith("Endpoint") }.joinToString("\n")) }
    }

    @Test
    fun `key names and sections are case insensitive`() {
        val config = WgConfig.parse(CLIENT_CONF.replace("[Interface]", "[interface]").replace("AllowedIPs", "allowedips"))
        assertEquals(2, config.allowedIps.size)
    }

    @Test
    fun cidr() {
        val home = Cidr.parse("192.168.4.0/24")!!
        assertTrue(home.contains("192.168.4.103"))
        assertFalse(home.contains("192.168.5.1"))
        assertFalse(home.contains("llama.home"))
        assertFalse(home.contains("fd00::1"))
        assertTrue(Cidr.parse("10.0.0.0/8")!!.contains("10.250.1.1"))
        assertTrue(Cidr.parse("192.168.4.103")!!.contains("192.168.4.103"))
        assertTrue(Cidr.parse("0.0.0.0/0")!!.isDefaultRoute)
        val v6 = Cidr.parse("fd42::/64")!!
        assertTrue(v6.contains("fd42::5"))
        assertTrue(v6.contains("[fd42:0:0:0:1:2:3:4]"))
        assertFalse(v6.contains("fd43::5"))
        assertTrue(Cidr.parse("::ffff:192.168.4.0/120")!!.contains("::ffff:192.168.4.9"))
        listOf("300.1.1.1/24", "1.2.3/24", "1.2.3.4/33", "fd::1::2", "", "host/24").forEach { assertNull(Cidr.parse(it), it) }
    }

    @Test
    fun routes() {
        val routes = TunnelRoutes.parse("192.168.4.0/24, llama.home *.lan; bad_host!")
        assertTrue(routes.matches("192.168.4.103"))
        assertTrue(routes.matches("LLAMA.home"))
        assertTrue(routes.matches("zion.lan"))
        assertFalse(routes.matches("lan"))
        assertFalse(routes.matches("api.openai.com"))
        assertFalse(routes.matches("10.0.0.1"))
        assertEquals(listOf("bad_host!"), routes.invalid)
        assertFalse(routes.coversEverything)

        val full = TunnelRoutes.parse("0.0.0.0/0")
        assertTrue(full.coversEverything)
        assertTrue(full.matches("api.openai.com"))
        assertTrue(TunnelRoutes.parse("").isEmpty)
    }
}
