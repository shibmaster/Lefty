package com.inspiredandroid.kai.tunnel

/**
 * Which request hosts go through the tunnel: networks (`192.168.4.0/24`, single addresses) and
 * hostnames (`llama.home`, or `*.home` for a whole domain). Written as one comma/space separated
 * list; empty entries and unreadable ones are dropped and reported in [invalid].
 */
class TunnelRoutes private constructor(
    val networks: List<Cidr>,
    val hostnames: List<String>,
    val invalid: List<String>,
) {
    val isEmpty: Boolean get() = networks.isEmpty() && hostnames.isEmpty()

    /** True when a default route (`0.0.0.0/0`, `::/0`) sends every address through the tunnel. */
    val coversEverything: Boolean get() = networks.any { it.isDefaultRoute }

    fun matches(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (Cidr.parseIp(h) != null) return networks.any { it.contains(h) }
        // A default route covers names too: everything goes through the tunnel.
        if (coversEverything) return true
        return hostnames.any { pattern ->
            if (pattern.startsWith("*.")) h.endsWith(pattern.substring(1)) else h == pattern
        }
    }

    override fun toString(): String = (networks.map { it.toString() } + hostnames).joinToString(", ")

    companion object {
        private val HOSTNAME = Regex("^(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$")

        fun parse(text: String): TunnelRoutes {
            val networks = mutableListOf<Cidr>()
            val hostnames = mutableListOf<String>()
            val invalid = mutableListOf<String>()
            for (entry in text.split(',', ' ', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
                val cidr = Cidr.parse(entry)
                when {
                    cidr != null -> networks += cidr
                    HOSTNAME.matches(entry.lowercase()) -> hostnames += entry.lowercase()
                    else -> invalid += entry
                }
            }
            return TunnelRoutes(networks, hostnames, invalid)
        }

        fun of(networks: List<Cidr>): TunnelRoutes = TunnelRoutes(networks, emptyList(), emptyList())
    }
}
