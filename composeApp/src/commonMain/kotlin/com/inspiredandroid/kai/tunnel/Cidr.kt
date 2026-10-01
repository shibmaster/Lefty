package com.inspiredandroid.kai.tunnel

/** An IPv4 or IPv6 network such as `192.168.4.0/24` or `fd00::/64`. */
class Cidr private constructor(private val network: ByteArray, val prefixLength: Int, private val text: String) {

    /** Whether [ip] (an address literal) lies inside this network. Hostnames never match. */
    fun contains(ip: String): Boolean {
        val bytes = parseIp(ip) ?: return false
        if (bytes.size != network.size) return false
        return matchesPrefix(bytes)
    }

    /** `0.0.0.0/0` or `::/0`: the whole address family. */
    val isDefaultRoute: Boolean get() = prefixLength == 0

    private fun matchesPrefix(bytes: ByteArray): Boolean {
        var remaining = prefixLength
        for (i in bytes.indices) {
            if (remaining <= 0) return true
            val bits = minOf(8, remaining)
            val mask = (0xFF shl (8 - bits)) and 0xFF
            if ((bytes[i].toInt() and mask) != (network[i].toInt() and mask)) return false
            remaining -= bits
        }
        return true
    }

    override fun toString(): String = text

    companion object {
        /** Parses `address/prefix`; a bare address is a single host (/32 or /128). Null when invalid. */
        fun parse(value: String): Cidr? {
            val trimmed = value.trim()
            val address = trimmed.substringBefore('/')
            val bytes = parseIp(address) ?: return null
            val maxPrefix = bytes.size * 8
            val prefix = if ('/' in trimmed) trimmed.substringAfter('/').toIntOrNull() ?: return null else maxPrefix
            if (prefix !in 0..maxPrefix) return null
            return Cidr(bytes, prefix, trimmed)
        }

        /** Address literal to bytes (4 for IPv4, 16 for IPv6), or null for anything else. */
        fun parseIp(value: String): ByteArray? {
            val ip = value.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
            return if (':' in ip) parseIpv6(ip) else parseIpv4(ip)
        }

        private fun parseIpv4(ip: String): ByteArray? {
            val parts = ip.split('.')
            if (parts.size != 4) return null
            val bytes = ByteArray(4)
            for ((i, part) in parts.withIndex()) {
                if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
                val n = part.toInt()
                if (n > 255) return null
                bytes[i] = n.toByte()
            }
            return bytes
        }

        private fun parseIpv6(ip: String): ByteArray? {
            if (ip.count { it == ':' } < 2) return null
            val halves = ip.split("::")
            if (halves.size > 2) return null
            fun groups(part: String): List<String>? = if (part.isEmpty()) emptyList() else part.split(':')
            val head = groups(halves[0]) ?: return null
            val tail = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
            // An embedded IPv4 tail (::ffff:1.2.3.4) counts as two groups.
            val words = mutableListOf<Int>()
            fun addGroups(list: List<String>): Boolean {
                for ((i, g) in list.withIndex()) {
                    if ('.' in g && i == list.lastIndex) {
                        val v4 = parseIpv4(g) ?: return false
                        words += ((v4[0].toInt() and 0xFF) shl 8) or (v4[1].toInt() and 0xFF)
                        words += ((v4[2].toInt() and 0xFF) shl 8) or (v4[3].toInt() and 0xFF)
                    } else {
                        if (g.isEmpty() || g.length > 4) return false
                        words += g.toIntOrNull(16) ?: return false
                    }
                }
                return true
            }
            if (!addGroups(head)) return null
            val headCount = words.size
            if (!addGroups(tail)) return null
            val tailWords = words.subList(headCount, words.size).toList()
            val missing = 8 - words.size
            if (halves.size == 1 && missing != 0) return null
            if (halves.size == 2 && missing < 1) return null
            val all = words.subList(0, headCount) + List(missing) { 0 } + tailWords
            val bytes = ByteArray(16)
            for ((i, w) in all.withIndex()) {
                bytes[2 * i] = (w shr 8).toByte()
                bytes[2 * i + 1] = w.toByte()
            }
            return bytes
        }
    }
}
