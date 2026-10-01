package com.inspiredandroid.kai.tunnel

import com.inspiredandroid.kai.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The userspace WireGuard engine (the Go bridge on Android). */
interface WireGuardBridge {
    val isSupported: Boolean

    /** Starts the tunnel and its local proxy; replaces a running one. */
    fun start(uapi: String, addresses: String, dns: String, mtu: Int): BridgeSession

    fun stop()

    /** JSON, see [BridgeStatus]. */
    fun status(): String

    /** Resolves a hostname to an IP address over the normal network (blocking). */
    fun resolveHost(host: String): String
}

/** Where the running tunnel's proxy listens and the token each request must carry. */
data class BridgeSession(val port: Int, val token: String)

object UnsupportedWireGuardBridge : WireGuardBridge {
    override val isSupported = false
    override fun start(uapi: String, addresses: String, dns: String, mtu: Int): BridgeSession = throw TunnelException("WireGuard is not available on this device")
    override fun stop() {}
    override fun status() = """{"up":false}"""
    override fun resolveHost(host: String): String = throw TunnelException("WireGuard is not available on this device")
}

@Serializable
data class BridgeStatus(
    val up: Boolean = false,
    @SerialName("last_handshake_ago_sec") val lastHandshakeAgoSec: Long = -1,
    @SerialName("rx_bytes") val rxBytes: Long = 0,
    @SerialName("tx_bytes") val txBytes: Long = 0,
    @SerialName("active_requests") val activeRequests: Long = 0,
    @SerialName("idle_ms") val idleMs: Long = 0,
)

class TunnelException(message: String) : Exception(message)

sealed interface TunnelState {
    data object Off : TunnelState
    data object Connecting : TunnelState

    /** [handshakeAgoSec] is null until the first handshake with the server. */
    data class Up(val handshakeAgoSec: Long?, val rxBytes: Long, val txBytes: Long) : TunnelState
    data class Error(val message: String) : TunnelState
}

/**
 * Runs the in-app WireGuard tunnel on demand: requests to tunnel hosts call [ensureUp], which starts
 * it if needed, and a monitor stops it after the configured idle time. Settings changes stop the
 * tunnel so the next request uses the new configuration.
 */
class TunnelManager(
    private val bridge: WireGuardBridge,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    /** Resolves an endpoint hostname to an IP address outside the tunnel. */
    private val resolveHost: suspend (String) -> String,
    private val monitorInterval: Duration = 5.seconds,
) {
    private val _state = MutableStateFlow<TunnelState>(TunnelState.Off)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var session: BridgeSession? = null
    private var monitor: Job? = null

    // Parsed config and routes, cached per stored text (the proxy selector asks for every request).
    private var cachedConf: String? = null
    private var cachedConfig: WgConfig? = null
    private var cachedRoutes: TunnelRoutes? = null
    private var cachedRoutesKey: Pair<String?, String?>? = null

    val isSupported: Boolean get() = bridge.isSupported

    /** The running tunnel's proxy, or null when it is down. */
    val currentSession: BridgeSession? get() = session

    /** The stored config, or null when none is stored or it no longer parses. */
    fun config(): WgConfig? {
        val conf = settings.getWireGuardConf() ?: return null
        if (conf != cachedConf) {
            cachedConf = conf
            cachedConfig = runCatching { WgConfig.parse(conf) }.getOrNull()
        }
        return cachedConfig
    }

    /** The addresses sent through the tunnel: the user's list, or else the config's AllowedIPs. */
    fun routes(): TunnelRoutes? {
        val config = config() ?: return null
        val custom = settings.getWireGuardRoutes()
        val key = settings.getWireGuardConf() to custom
        if (key != cachedRoutesKey) {
            cachedRoutesKey = key
            cachedRoutes = if (custom.isNullOrBlank()) TunnelRoutes.of(config.allowedIps) else TunnelRoutes.parse(custom)
        }
        return cachedRoutes
    }

    val isEnabled: Boolean get() = settings.isWireGuardEnabled()

    /** The user's route list as typed, empty when the config's AllowedIPs are used. */
    val customRoutes: String get() = settings.getWireGuardRoutes().orEmpty()

    val idleMinutes: Int get() = settings.getWireGuardIdleMinutes()

    /**
     * Stores a new config after checking it; the running tunnel stops so the next request uses it.
     * Turns the tunnel on, since importing a config means wanting to use it.
     */
    fun importConfig(text: String): Result<WgConfig> {
        val config = try {
            WgConfig.parse(text)
        } catch (e: WgConfigException) {
            return Result.failure(e)
        }
        stop()
        settings.setWireGuardConf(text)
        settings.setWireGuardEnabled(true)
        return Result.success(config)
    }

    fun removeConfig() {
        stop()
        settings.setWireGuardConf(null)
        settings.setWireGuardRoutes(null)
        settings.setWireGuardEnabled(false)
    }

    fun setEnabled(enabled: Boolean) {
        settings.setWireGuardEnabled(enabled)
        if (!enabled) stop()
    }

    fun setCustomRoutes(routes: String) = settings.setWireGuardRoutes(routes)

    fun setIdleMinutes(minutes: Int) = settings.setWireGuardIdleMinutes(minutes)

    /** Whether a request to [host] has to go through the tunnel. */
    fun shouldTunnel(host: String): Boolean {
        if (!bridge.isSupported || !settings.isWireGuardEnabled()) return false
        return routes()?.matches(host) == true
    }

    /** Starts the tunnel if it isn't running; returns the proxy to use. */
    suspend fun ensureUp(): BridgeSession = mutex.withLock {
        session?.let { return it }
        val config = config() ?: throw TunnelException("No valid WireGuard config imported")
        _state.value = TunnelState.Connecting
        try {
            val resolved = config.endpointHostnames.associateWith { host ->
                try {
                    resolveHost(host)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    throw TunnelException("Can't resolve the WireGuard server $host")
                }
            }
            val started = bridge.start(
                uapi = config.toUapi(resolved),
                addresses = config.addresses.joinToString(","),
                dns = config.dns.joinToString(","),
                mtu = config.mtu ?: 0,
            )
            session = started
            _state.value = TunnelState.Up(null, 0, 0)
            startMonitor()
            started
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val message = (e as? TunnelException)?.message ?: "WireGuard: ${e.message ?: e::class.simpleName}"
            _state.value = TunnelState.Error(message)
            throw TunnelException(message)
        }
    }

    /** Starts the tunnel and waits up to [timeoutSeconds] for the first handshake (the test button). */
    suspend fun connectNow(timeoutSeconds: Int = 10): Boolean {
        try {
            ensureUp()
        } catch (_: TunnelException) {
            return false
        }
        val handshake = withTimeoutOrNull(timeoutSeconds.seconds) {
            var s = readStatus()
            while (s.lastHandshakeAgoSec < 0) {
                delay(250)
                s = readStatus()
            }
            s
        }
        if (handshake == null) {
            _state.value = TunnelState.Error("No answer from the WireGuard server (no handshake within $timeoutSeconds s)")
            return false
        }
        publish(handshake)
        return true
    }

    fun stop() {
        monitor?.cancel()
        monitor = null
        session = null
        bridge.stop()
        _state.value = TunnelState.Off
    }

    private fun readStatus(): BridgeStatus = runCatching { json.decodeFromString<BridgeStatus>(bridge.status()) }.getOrDefault(BridgeStatus())

    private fun publish(s: BridgeStatus) {
        if (session != null && s.up) _state.value = TunnelState.Up(s.lastHandshakeAgoSec.takeIf { it >= 0 }, s.rxBytes, s.txBytes)
    }

    private fun startMonitor() {
        monitor?.cancel()
        monitor = scope.launch {
            while (isActive) {
                delay(monitorInterval)
                val s = readStatus()
                if (!s.up) {
                    mutex.withLock { if (session != null) stop() }
                    break
                }
                publish(s)
                val idleLimitMs = settings.getWireGuardIdleMinutes() * 60_000L
                if (s.activeRequests == 0L && s.idleMs >= idleLimitMs) {
                    mutex.withLock { stop() }
                    break
                }
            }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
