// Package wgbridge runs a userspace WireGuard tunnel inside the app (wireguard-go on a gVisor
// netstack, so no VPN service or root is needed) and exposes it as an HTTP proxy on 127.0.0.1.
// The Android app routes only its requests to tunnel addresses through that proxy.
//
// The API is shaped for gomobile: package-level functions with string/int/error types only.
package wgbridge

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

type tunnel struct {
	dev      *device.Device
	listener net.Listener
	server   *http.Server
	proxy    *proxy
}

var (
	mu      sync.Mutex
	current *tunnel
)

// Start brings the tunnel up and returns the local proxy port.
//
// uapi is the wireguard-go configuration (private_key=…, public_key=…, endpoint=…, allowed_ip=…,
// one key=value per line, keys in hex). addresses and dns are comma-separated IP addresses
// (interface addresses without prefix length; DNS servers reached through the tunnel). Every proxy
// request must carry "Proxy-Authorization: Bearer <token>". A running tunnel is replaced.
func Start(uapi, addresses, dns string, mtu int, token string) (int, error) {
	if token == "" {
		return 0, errors.New("token must not be empty")
	}
	addrs, err := parseAddrs(addresses)
	if err != nil {
		return 0, fmt.Errorf("address: %w", err)
	}
	if len(addrs) == 0 {
		return 0, errors.New("no interface address")
	}
	dnsAddrs, err := parseAddrs(dns)
	if err != nil {
		return 0, fmt.Errorf("dns: %w", err)
	}
	if mtu <= 0 {
		// Same default as the WireGuard Android app; 1420 gets dropped on many mobile networks.
		mtu = 1280
	}

	mu.Lock()
	defer mu.Unlock()
	stopLocked()

	tunDev, tnet, err := netstack.CreateNetTUN(addrs, dnsAddrs, mtu)
	if err != nil {
		return 0, fmt.Errorf("create tun: %w", err)
	}
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "wgbridge: "))
	if err := dev.IpcSet(uapi); err != nil {
		dev.Close()
		return 0, fmt.Errorf("wireguard config: %w", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return 0, fmt.Errorf("wireguard up: %w", err)
	}

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		dev.Close()
		return 0, fmt.Errorf("proxy listen: %w", err)
	}
	p := newProxy(tnet.DialContext, token)
	server := &http.Server{Handler: p, ReadHeaderTimeout: 30 * time.Second}
	go server.Serve(listener)

	current = &tunnel{dev: dev, listener: listener, server: server, proxy: p}
	return listener.Addr().(*net.TCPAddr).Port, nil
}

// Stop tears the tunnel down. Safe to call when nothing runs.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	stopLocked()
}

func stopLocked() {
	if current == nil {
		return
	}
	current.server.Close()
	current.listener.Close()
	current.dev.Close()
	current = nil
}

type status struct {
	Up bool `json:"up"`
	// Seconds since the last handshake with any peer; -1 before the first one.
	LastHandshakeAgoSec int64 `json:"last_handshake_ago_sec"`
	RxBytes             int64 `json:"rx_bytes"`
	TxBytes             int64 `json:"tx_bytes"`
	// Plain-HTTP requests in flight (a long, silent prefill still counts as busy).
	ActiveRequests int64 `json:"active_requests"`
	// Milliseconds since the proxy last carried traffic.
	IdleMs int64 `json:"idle_ms"`
}

// Status returns the tunnel state as JSON (see the status struct).
func Status() string {
	mu.Lock()
	defer mu.Unlock()
	s := status{LastHandshakeAgoSec: -1}
	if current != nil {
		s.Up = true
		s.ActiveRequests, s.IdleMs = current.proxy.activity()
		if cfg, err := current.dev.IpcGet(); err == nil {
			s.LastHandshakeAgoSec, s.RxBytes, s.TxBytes = parseIpcStats(cfg, time.Now())
		}
	}
	b, _ := json.Marshal(s)
	return string(b)
}

func parseAddrs(list string) ([]netip.Addr, error) {
	var out []netip.Addr
	for _, part := range strings.Split(list, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		// Accept "10.0.0.2/32" too: the prefix length doesn't matter for a netstack interface.
		if i := strings.IndexByte(part, '/'); i >= 0 {
			part = part[:i]
		}
		a, err := netip.ParseAddr(part)
		if err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, nil
}

// parseIpcStats sums transfer counters over all peers and finds the most recent handshake.
func parseIpcStats(ipc string, now time.Time) (handshakeAgo, rx, tx int64) {
	var newest int64
	for _, line := range strings.Split(ipc, "\n") {
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		n, err := strconv.ParseInt(value, 10, 64)
		if err != nil {
			continue
		}
		switch key {
		case "rx_bytes":
			rx += n
		case "tx_bytes":
			tx += n
		case "last_handshake_time_sec":
			if n > newest {
				newest = n
			}
		}
	}
	if newest == 0 {
		return -1, rx, tx
	}
	return now.Unix() - newest, rx, tx
}
