package wgbridge

import (
	"bufio"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func keypair(t *testing.T) (priv, pub string) {
	t.Helper()
	k := make([]byte, 32)
	if _, err := rand.Read(k); err != nil {
		t.Fatal(err)
	}
	k[0] &= 248
	k[31] = (k[31] & 127) | 64
	p, err := curve25519.X25519(k, curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return hex.EncodeToString(k), hex.EncodeToString(p)
}

func freeUDPPort(t *testing.T) int {
	t.Helper()
	c, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).Port
}

// startServerPeer runs the "home" side: a WireGuard peer at 10.99.0.2 serving HTTP on port 80.
func startServerPeer(t *testing.T, serverPriv, clientPub string, port int) {
	t.Helper()
	tunDev, tnet, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.99.0.2")}, nil, 1420)
	if err != nil {
		t.Fatal(err)
	}
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "server: "))
	uapi := fmt.Sprintf("private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.99.0.1/32\n", serverPriv, port, clientPub)
	if err := dev.IpcSet(uapi); err != nil {
		t.Fatal(err)
	}
	if err := dev.Up(); err != nil {
		t.Fatal(err)
	}
	ln, err := tnet.ListenTCP(&net.TCPAddr{Port: 80})
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/models", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, `{"host":%q}`, r.Host)
	})
	go http.Serve(ln, mux)
	t.Cleanup(func() {
		ln.Close()
		dev.Close()
	})
}

func setup(t *testing.T) (proxyAddr, token string) {
	t.Helper()
	serverPriv, serverPub := keypair(t)
	clientPriv, clientPub := keypair(t)
	port := freeUDPPort(t)
	startServerPeer(t, serverPriv, clientPub, port)

	token = "test-token"
	uapi := fmt.Sprintf("private_key=%s\npublic_key=%s\nendpoint=127.0.0.1:%d\nallowed_ip=10.99.0.0/24\npersistent_keepalive_interval=25\n", clientPriv, serverPub, port)
	proxyPort, err := Start(uapi, "10.99.0.1/32", "", 1420, token)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(Stop)
	return fmt.Sprintf("127.0.0.1:%d", proxyPort), token
}

func TestForwardAndConnectThroughTunnel(t *testing.T) {
	proxyAddr, token := setup(t)

	// Plain HTTP: absolute-URI request to the proxy.
	c, err := net.DialTimeout("tcp", proxyAddr, 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(20 * time.Second))
	fmt.Fprintf(c, "GET http://10.99.0.2/v1/models HTTP/1.1\r\nHost: 10.99.0.2\r\nProxy-Authorization: Bearer %s\r\nConnection: close\r\n\r\n", token)
	resp, err := http.ReadResponse(bufio.NewReader(c), nil)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 || !strings.Contains(string(body), "10.99.0.2") {
		t.Fatalf("forward: %d %s", resp.StatusCode, body)
	}

	// CONNECT, then HTTP inside the tunnel (what OkHttp does for https:// URLs).
	c2, err := net.DialTimeout("tcp", proxyAddr, 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer c2.Close()
	c2.SetDeadline(time.Now().Add(20 * time.Second))
	fmt.Fprintf(c2, "CONNECT 10.99.0.2:80 HTTP/1.1\r\nHost: 10.99.0.2:80\r\nProxy-Authorization: Bearer %s\r\n\r\n", token)
	br := bufio.NewReader(c2)
	connResp, err := http.ReadResponse(br, &http.Request{Method: http.MethodConnect})
	if err != nil || connResp.StatusCode != 200 {
		t.Fatalf("connect: %v %v", connResp, err)
	}
	fmt.Fprintf(c2, "GET /v1/models HTTP/1.1\r\nHost: home\r\nConnection: close\r\n\r\n")
	inner, err := http.ReadResponse(br, nil)
	if err != nil {
		t.Fatal(err)
	}
	innerBody, _ := io.ReadAll(inner.Body)
	if inner.StatusCode != 200 || !strings.Contains(string(innerBody), `"home"`) {
		t.Fatalf("inner: %d %s", inner.StatusCode, innerBody)
	}

	var s status
	if err := json.Unmarshal([]byte(Status()), &s); err != nil {
		t.Fatal(err)
	}
	if !s.Up || s.LastHandshakeAgoSec < 0 || s.RxBytes == 0 {
		t.Fatalf("status after traffic: %+v", s)
	}
}

func TestProxyRequiresToken(t *testing.T) {
	proxyAddr, _ := setup(t)
	for _, header := range []string{"", "Proxy-Authorization: Bearer wrong\r\n"} {
		c, err := net.DialTimeout("tcp", proxyAddr, 5*time.Second)
		if err != nil {
			t.Fatal(err)
		}
		c.SetDeadline(time.Now().Add(10 * time.Second))
		fmt.Fprintf(c, "CONNECT 10.99.0.2:80 HTTP/1.1\r\nHost: 10.99.0.2:80\r\n%s\r\n", header)
		resp, err := http.ReadResponse(bufio.NewReader(c), &http.Request{Method: http.MethodConnect})
		c.Close()
		if err != nil {
			t.Fatal(err)
		}
		if resp.StatusCode != http.StatusProxyAuthRequired {
			t.Fatalf("header %q: got %d, want 407", header, resp.StatusCode)
		}
	}
}

func TestStopAndStatus(t *testing.T) {
	setup(t)
	Stop()
	var s status
	json.Unmarshal([]byte(Status()), &s)
	if s.Up {
		t.Fatal("still up after Stop")
	}
	Stop() // idempotent
}

func TestStartRejectsBadInput(t *testing.T) {
	if _, err := Start("private_key=00\n", "10.0.0.2", "", 0, "t"); err == nil {
		t.Fatal("bad key accepted")
	}
	if _, err := Start("", "not-an-ip", "", 0, "t"); err == nil {
		t.Fatal("bad address accepted")
	}
	if _, err := Start("", "10.0.0.2", "", 0, ""); err == nil {
		t.Fatal("empty token accepted")
	}
}

func TestParseIpcStats(t *testing.T) {
	now := time.Unix(1000, 0)
	ago, rx, tx := parseIpcStats("public_key=ab\nlast_handshake_time_sec=990\nrx_bytes=5\ntx_bytes=7\npublic_key=cd\nlast_handshake_time_sec=0\nrx_bytes=1\ntx_bytes=1\n", now)
	if ago != 10 || rx != 6 || tx != 8 {
		t.Fatalf("got %d %d %d", ago, rx, tx)
	}
	if ago, _, _ := parseIpcStats("last_handshake_time_sec=0\n", now); ago != -1 {
		t.Fatalf("no handshake: %d", ago)
	}
}
