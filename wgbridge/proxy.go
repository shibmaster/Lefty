package wgbridge

import (
	"context"
	"crypto/subtle"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"sync"
	"sync/atomic"
	"time"
)

type dialFunc func(ctx context.Context, network, address string) (net.Conn, error)

// proxy is an HTTP proxy that dials through the tunnel: CONNECT for HTTPS (and anything else that
// tunnels), absolute-URI forwarding for plain HTTP. 127.0.0.1 is reachable by every app on the
// device, so each request must present the session token.
type proxy struct {
	dial    dialFunc
	token   string
	forward *httputil.ReverseProxy

	// Requests in flight. CONNECT tunnels don't count: OkHttp keeps them pooled while idle, so
	// their liveness comes from the bytes they carry (lastActivity).
	active       atomic.Int64
	lastActivity atomic.Int64 // unix nanos
}

func newProxy(dial dialFunc, token string) *proxy {
	p := &proxy{dial: dial, token: token}
	p.touch()
	transport := &http.Transport{
		DialContext:         dial,
		MaxIdleConns:        8,
		IdleConnTimeout:     90 * time.Second,
		TLSHandshakeTimeout: 30 * time.Second,
	}
	p.forward = &httputil.ReverseProxy{
		Rewrite: func(r *httputil.ProxyRequest) {
			r.Out.URL = r.In.URL
			r.Out.Host = r.In.Host
			r.Out.Header.Del("Proxy-Authorization")
			r.Out.Header.Del("Proxy-Connection")
		},
		Transport:     transport,
		FlushInterval: -1, // stream server-sent events (chat completions) as they arrive
	}
	return p
}

func (p *proxy) touch() { p.lastActivity.Store(time.Now().UnixNano()) }

// activity reports open connections and how long the proxy has been idle.
func (p *proxy) activity() (active, idleMs int64) {
	return p.active.Load(), (time.Now().UnixNano() - p.lastActivity.Load()) / int64(time.Millisecond)
}

func (p *proxy) authorized(r *http.Request) bool {
	want := "Bearer " + p.token
	got := r.Header.Get("Proxy-Authorization")
	return subtle.ConstantTimeCompare([]byte(got), []byte(want)) == 1
}

func (p *proxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if !p.authorized(r) {
		w.Header().Set("Proxy-Authenticate", `Bearer realm="lefty-wireguard"`)
		http.Error(w, "proxy authentication required", http.StatusProxyAuthRequired)
		return
	}
	p.touch()
	if r.Method == http.MethodConnect {
		p.serveConnect(w, r)
		return
	}
	p.active.Add(1)
	defer func() {
		p.touch()
		p.active.Add(-1)
	}()
	if !r.URL.IsAbs() {
		http.Error(w, "this is a proxy: absolute URL or CONNECT expected", http.StatusBadRequest)
		return
	}
	p.forward.ServeHTTP(w, r)
}

func (p *proxy) serveConnect(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	upstream, err := p.dial(ctx, "tcp", r.Host)
	cancel()
	if err != nil {
		http.Error(w, "tunnel dial: "+err.Error(), http.StatusBadGateway)
		return
	}
	hijacker, ok := w.(http.Hijacker)
	if !ok {
		upstream.Close()
		http.Error(w, "hijacking not supported", http.StatusInternalServerError)
		return
	}
	client, buffered, err := hijacker.Hijack()
	if err != nil {
		upstream.Close()
		return
	}
	if _, err := client.Write([]byte("HTTP/1.1 200 Connection established\r\n\r\n")); err != nil {
		client.Close()
		upstream.Close()
		return
	}
	// Bytes the client sent after the CONNECT header (e.g. a TLS ClientHello) are already buffered.
	if n := buffered.Reader.Buffered(); n > 0 {
		head, _ := buffered.Reader.Peek(n)
		if _, err := upstream.Write(head); err != nil {
			client.Close()
			upstream.Close()
			return
		}
	}
	p.splice(client, upstream)
}

// splice copies both directions until either side closes, counting traffic as activity.
func (p *proxy) splice(a, b net.Conn) {
	var once sync.Once
	closeBoth := func() {
		a.Close()
		b.Close()
	}
	var wg sync.WaitGroup
	wg.Add(2)
	copyDir := func(dst, src net.Conn) {
		defer wg.Done()
		io.Copy(activityWriter{dst, p}, src)
		once.Do(closeBoth)
	}
	go copyDir(a, b)
	go copyDir(b, a)
	wg.Wait()
}

type activityWriter struct {
	w io.Writer
	p *proxy
}

func (a activityWriter) Write(b []byte) (int, error) {
	a.p.touch()
	return a.w.Write(b)
}
