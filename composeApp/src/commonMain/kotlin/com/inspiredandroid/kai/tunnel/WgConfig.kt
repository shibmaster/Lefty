package com.inspiredandroid.kai.tunnel

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class WgConfigException(message: String) : Exception(message)

data class WgPeer(
    val publicKey: String,
    val presharedKey: String?,
    /** `host:port` as written; the host may be a name that has to be resolved first. */
    val endpoint: String?,
    val allowedIps: List<Cidr>,
    val persistentKeepalive: Int?,
)

/**
 * A standard WireGuard client configuration (the `.conf` the WireGuard app imports and wg-quick
 * reads). Settings that only make sense for a system interface (ListenPort, Table, PostUp, …) are
 * ignored and reported in [ignoredKeys].
 */
data class WgConfig(
    val privateKey: String,
    val addresses: List<String>,
    val dns: List<String>,
    val mtu: Int?,
    val peers: List<WgPeer>,
    val ignoredKeys: List<String>,
) {
    val allowedIps: List<Cidr> get() = peers.flatMap { it.allowedIps }

    /**
     * The wireguard-go configuration (UAPI `set` format, keys in hex). [resolvedEndpoints] maps each
     * peer's endpoint host to an IP address, because wireguard-go doesn't resolve names.
     */
    fun toUapi(resolvedEndpoints: Map<String, String> = emptyMap()): String = buildString {
        appendLine("private_key=${base64ToHex(privateKey)}")
        appendLine("replace_peers=true")
        for (peer in peers) {
            appendLine("public_key=${base64ToHex(peer.publicKey)}")
            peer.presharedKey?.let { appendLine("preshared_key=${base64ToHex(it)}") }
            peer.endpoint?.let { endpoint ->
                val (host, port) = splitEndpoint(endpoint)
                val ip = resolvedEndpoints[host] ?: host
                appendLine("endpoint=${if (':' in ip) "[$ip]" else ip}:$port")
            }
            // Keepalives keep NAT mappings open on mobile networks and start the handshake right
            // away; the tunnel shuts down when idle, so the cost is small.
            appendLine("persistent_keepalive_interval=${peer.persistentKeepalive ?: DEFAULT_KEEPALIVE}")
            appendLine("replace_allowed_ips=true")
            peer.allowedIps.forEach { appendLine("allowed_ip=$it") }
        }
    }

    /** Endpoint hosts that are names, not IP literals. */
    val endpointHostnames: List<String>
        get() = peers.mapNotNull { it.endpoint?.let { e -> splitEndpoint(e).first } }.filter { Cidr.parseIp(it) == null }

    companion object {
        const val DEFAULT_KEEPALIVE = 25

        /**
         * Used when the config sets no MTU; the official WireGuard Android app does the same. wg-quick's
         * 1420 is too large for many mobile networks once WireGuard's overhead is added: full-size
         * packets get dropped silently, so short replies arrive and longer ones hang or stop midway.
         */
        const val DEFAULT_MTU = 1280

        private val INTERFACE_KEYS = setOf("privatekey", "address", "dns", "mtu")
        private val PEER_KEYS = setOf("publickey", "presharedkey", "endpoint", "allowedips", "persistentkeepalive")

        @OptIn(ExperimentalEncodingApi::class)
        internal fun base64ToHex(key: String): String = Base64.decode(key.trim()).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        @OptIn(ExperimentalEncodingApi::class)
        private fun isKey(value: String): Boolean = try {
            Base64.decode(value.trim()).size == 32
        } catch (_: IllegalArgumentException) {
            false
        }

        /** Splits `host:port`, `[v6]:port`. */
        internal fun splitEndpoint(endpoint: String): Pair<String, Int> {
            val e = endpoint.trim()
            val host: String
            val portText: String
            if (e.startsWith("[")) {
                host = e.substringAfter('[').substringBefore(']')
                portText = e.substringAfter("]:", "")
            } else {
                host = e.substringBeforeLast(':')
                portText = e.substringAfterLast(':', "")
            }
            val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw WgConfigException("Endpoint \"$endpoint\" needs a port, like vpn.example.com:51820")
            if (host.isBlank()) throw WgConfigException("Endpoint \"$endpoint\" has no host")
            return host to port
        }

        fun parse(text: String): WgConfig {
            var section: String? = null
            val iface = mutableMapOf<String, MutableList<String>>()
            val peers = mutableListOf<MutableMap<String, MutableList<String>>>()
            val ignored = mutableListOf<String>()
            for (raw in text.lines()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.removeSurrounding("[", "]").trim().lowercase()
                    if (section == "peer") peers += mutableMapOf()
                    continue
                }
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=', "").trim()
                if ('=' !in line) throw WgConfigException("Unreadable line: \"$raw\"")
                val lower = key.lowercase()
                when (section) {
                    "interface" -> if (lower in INTERFACE_KEYS) iface.getOrPut(lower) { mutableListOf() } += value else ignored += key
                    "peer" -> if (lower in PEER_KEYS) peers.last().getOrPut(lower) { mutableListOf() } += value else ignored += key
                    else -> throw WgConfigException("\"$key\" is outside an [Interface] or [Peer] section. Is this a WireGuard .conf file?")
                }
            }
            fun Map<String, List<String>>.list(key: String) = this[key].orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }

            val privateKey = iface["privatekey"]?.firstOrNull()
                ?: throw WgConfigException("No PrivateKey in [Interface]. Use the client config for this device, not the server's.")
            if (!isKey(privateKey)) throw WgConfigException("The PrivateKey is not a valid WireGuard key")
            val addresses = iface.list("address")
            if (addresses.isEmpty()) throw WgConfigException("No Address in [Interface]")
            addresses.forEach { if (Cidr.parse(it) == null) throw WgConfigException("Address \"$it\" is not an IP address") }
            // wg-quick allows search domains in DNS; only servers matter here.
            val dnsEntries = iface.list("dns")
            val dns = dnsEntries.filter { Cidr.parseIp(it) != null }
            if (dns.size < dnsEntries.size) ignored += "DNS search domains"
            val mtu = iface["mtu"]?.firstOrNull()?.let { it.toIntOrNull() ?: throw WgConfigException("MTU \"$it\" is not a number") }
            if (peers.isEmpty()) throw WgConfigException("No [Peer] section")

            val parsedPeers = peers.mapIndexed { index, p ->
                val n = index + 1
                val publicKey = p["publickey"]?.firstOrNull() ?: throw WgConfigException("Peer $n has no PublicKey")
                if (!isKey(publicKey)) throw WgConfigException("Peer $n: the PublicKey is not a valid WireGuard key")
                val psk = p["presharedkey"]?.firstOrNull()
                if (psk != null && !isKey(psk)) throw WgConfigException("Peer $n: the PresharedKey is not a valid WireGuard key")
                val endpoint = p["endpoint"]?.firstOrNull()?.also { splitEndpoint(it) }
                val allowed = p.list("allowedips").map { Cidr.parse(it) ?: throw WgConfigException("Peer $n: AllowedIPs entry \"$it\" is not a network") }
                if (allowed.isEmpty()) throw WgConfigException("Peer $n has no AllowedIPs")
                val keepalive = p["persistentkeepalive"]?.firstOrNull()?.let { v ->
                    if (v.equals("off", ignoreCase = true)) 0 else v.toIntOrNull() ?: throw WgConfigException("Peer $n: PersistentKeepalive \"$v\" is not a number")
                }
                WgPeer(publicKey.trim(), psk?.trim(), endpoint, allowed, keepalive)
            }
            if (parsedPeers.none { it.endpoint != null }) throw WgConfigException("No peer has an Endpoint, so there is nothing to connect to")
            return WgConfig(privateKey.trim(), addresses, dns, mtu, parsedPeers, ignored.distinct())
        }
    }
}
