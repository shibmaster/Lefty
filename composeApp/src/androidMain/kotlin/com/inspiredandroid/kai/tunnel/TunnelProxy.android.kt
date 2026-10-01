package com.inspiredandroid.kai.tunnel

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.koin.java.KoinJavaComponent
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** A request that had to use the WireGuard tunnel but couldn't. Never falls back to a direct connection. */
class TunnelIOException(message: String) : IOException("WireGuard tunnel: $message")

// Koin may not be running yet (previews, unit tests, clients built before startKoin); then nothing is
// tunneled. Only a found manager is cached, so a client created early still picks it up later.
@Volatile
private var cachedManager: TunnelManager? = null

private val tunnelManager: TunnelManager?
    get() = cachedManager ?: runCatching { KoinJavaComponent.getKoin().getOrNull<TunnelManager>() }.getOrNull()?.also { cachedManager = it }

/** Forgets the cached manager (tests that restart Koin). */
internal fun resetTunnelManagerCache() {
    cachedManager = null
}

private const val PROXY_AUTHORIZATION = "Proxy-Authorization"

private fun isTunnelProxy(proxy: Proxy?, session: BridgeSession): Boolean {
    val address = proxy?.address() as? InetSocketAddress ?: return false
    return proxy.type() == Proxy.Type.HTTP && address.port == session.port && address.hostString == "127.0.0.1"
}

/**
 * Brings the tunnel up before a request for a tunnel host. Runs as an application interceptor,
 * before OkHttp's retry logic: a tunnel that can't start fails the call once, with its reason.
 * (Throwing from the proxy selector instead makes OkHttp retry forever.)
 */
private object TunnelUpInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val manager = tunnelManager
        if (manager != null && manager.shouldTunnel(chain.request().url.host)) {
            try {
                runBlocking { manager.ensureUp() }
            } catch (e: TunnelException) {
                throw TunnelIOException(e.message ?: "not available")
            }
        }
        return chain.proceed(chain.request())
    }
}

// Nothing listens on the discard port: a tunnel request whose tunnel vanished fails here instead of
// silently going out directly.
private val DEAD_PROXY = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9))

/** Sends requests for tunnel hosts to the tunnel's local proxy. */
private object TunnelProxySelector : ProxySelector() {
    override fun select(uri: URI): List<Proxy> {
        val manager = tunnelManager ?: return listOf(Proxy.NO_PROXY)
        val host = uri.host ?: return listOf(Proxy.NO_PROXY)
        if (!manager.shouldTunnel(host)) return listOf(Proxy.NO_PROXY)
        // Normally up already (TunnelUpInterceptor); it may have gone idle in between.
        val session = manager.currentSession
            ?: runCatching { runBlocking { manager.ensureUp() } }.getOrNull()
            ?: return listOf(DEAD_PROXY)
        return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", session.port)))
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
}

/** HTTPS through the proxy (CONNECT): OkHttp asks preemptively and on a 407. */
private object TunnelProxyAuthenticator : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val session = tunnelManager?.currentSession ?: return null
        if (!isTunnelProxy(route?.proxy, session)) return null
        val header = "Bearer ${session.token}"
        // Already sent this token and still refused: give up instead of looping.
        if (response.request.header(PROXY_AUTHORIZATION) == header) return null
        return response.request.newBuilder().header(PROXY_AUTHORIZATION, header).build()
    }
}

/** Plain HTTP through the proxy: the header travels with the request itself (only to the proxy). */
private object TunnelProxyAuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val session = tunnelManager?.currentSession
        val proxy = chain.connection()?.route()?.proxy
        if (session == null || request.isHttps || !isTunnelProxy(proxy, session)) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(PROXY_AUTHORIZATION, "Bearer ${session.token}").build())
    }
}

/** Routes this client's requests for tunnel hosts through the in-app WireGuard tunnel. */
fun OkHttpClient.Builder.routeThroughTunnel(): OkHttpClient.Builder = addInterceptor(TunnelUpInterceptor)
    .proxySelector(TunnelProxySelector)
    .proxyAuthenticator(TunnelProxyAuthenticator)
    .addNetworkInterceptor(TunnelProxyAuthInterceptor)
