# WireGuard Tunnel

**Last verified:** 2026-10-01

Lefty can reach a home inference server from any network through a WireGuard tunnel that runs inside the app. Users import a standard WireGuard client config (`.conf`) in Settings → General; only Lefty's requests to the tunnel's addresses go through it. The WireGuard app isn't needed, Android's VPN slot stays free (another VPN can run at the same time), and no VPN permission or key icon appears. Android only (arm64 and x86_64 devices); the section is hidden elsewhere and in builds without the bridge library.

## How It Works

- WireGuard runs in userspace inside the app: wireguard-go with Google's gVisor network stack, compiled into a native library. It opens a normal UDP socket to the WireGuard server; there is no system network interface.
- The tunnel is offered to the app as an HTTP proxy on `127.0.0.1` at a random port. Because every app on the device can reach `127.0.0.1`, each tunnel session gets a random 256-bit token, and the proxy refuses requests without it (HTTP 407).
- Lefty's shared HTTP client decides per request: hosts on the tunnel list go to the proxy (with the token), all other hosts connect directly. The token is only ever sent to the proxy, never to other hosts.
- The proxy resolves host names through the tunnel's DNS servers (the config's `DNS`), so names that only exist at home work.
- Plain `http://` requests are forwarded by the proxy; `https://` requests use CONNECT, so TLS stays end-to-end between Lefty and the server.

## Which Requests Use the Tunnel

- By default: requests whose host is an IP address inside the config's `AllowedIPs`.
- "Send through the tunnel" replaces that list: networks (`192.168.4.0/24`), single addresses, and host names (`llama.home`, or `*.home` for a whole domain), separated by commas, spaces or semicolons. Entries that are neither are listed as ignored.
- A default route (`0.0.0.0/0` or `::/0`) means everything, host names included. The settings warn about this, because phone configs often use it and then cloud providers would go through home as well; entering the home network instead limits the tunnel to the home server.
- When the tunnel is turned off, nothing is routed through it.
- A request for a tunnel host never falls back to a direct connection: if the tunnel can't come up, the request fails with the reason (for example "WireGuard tunnel: Can't resolve the WireGuard server vpn.example.org").

## Connecting and Disconnecting

- On demand: the first request for a tunnel host starts the tunnel. Lefty resolves the server's host name over the normal network first, because WireGuard itself only takes addresses.
- A keepalive (the config's `PersistentKeepalive`, 25 seconds when it sets none) keeps mobile NAT mappings open and starts the handshake immediately. `PersistentKeepalive = off` is honored.
- Idle shutdown: the tunnel stops after "Disconnect after idle" minutes (default 5, range 1–120) without traffic. A request still waiting for an answer (for example a long prompt being processed) counts as busy.
- Heartbeats and scheduled tasks run through the same client, so they bring the tunnel up in the background too; the daemon's foreground service keeps the process (and the tunnel) alive.
- Importing a new config or removing it stops the running tunnel, so the next request uses the new settings.

## The Config File

- The standard client config that the WireGuard app imports and that servers and their tools (wg-easy, PiVPN, `wg-quick`) generate. Nothing Lefty-specific is needed.
- Used: `[Interface]` PrivateKey, Address, DNS (servers), MTU; every `[Peer]` PublicKey, PresharedKey, Endpoint (host name or IP, IPv6 in brackets), AllowedIPs, PersistentKeepalive. Section and key names are case-insensitive; `#` starts a comment.
- Ignored and listed under "Not used in Lefty": ListenPort and wg-quick's system-interface extras (PostUp, PostDown, PreUp, PreDown, Table, SaveConfig, FwMark), and DNS search domains.
- Rejected with a reason: no PrivateKey (typically the server's config was picked instead of the client's), malformed keys or addresses, an Endpoint without port, no `[Peer]`, no AllowedIPs, or no peer with an Endpoint.

## Settings (General tab)

- Toggle "WireGuard tunnel" (needs an imported config).
- Import .conf (any file is accepted, because `.conf` has no registered file type on Android), or Paste config; Replace and Remove once one is stored.
- Shows the tunnel address, the server and the config's AllowedIPs, never keys.
- "Send through the tunnel" and "Disconnect after idle (minutes)".
- Status: off (connects on the next request), connecting, up and waiting for the server, connected (seconds since the last handshake, bytes received and sent), or the error. "Connect now" starts the tunnel and waits up to 10 seconds for the handshake, which is a quick way to check a config; "Disconnect" stops it.

## Storage and Privacy

- The config (it contains the private key), the route list, the on/off state and the idle time are stored in the app's encrypted settings, like API keys.
- Settings export leaves the tunnel out entirely: a private key shouldn't travel in an export file.

## Building

- The bridge lives in `wgbridge/` (Go). `wgbridge/build.sh` builds it with gomobile for arm64 and x86_64 and publishes the library to the project-local Maven repo `wgbridge/repo/` (not committed). It needs Go and gomobile; the Android NDK comes from the SDK.
- Without that library the app still builds; the tunnel then reports itself as unsupported and the settings section is hidden.
- The bridge adds about 11 MB per CPU type uncompressed (about 4 MB each in the APK).
- `go test ./...` in `wgbridge/` runs an end-to-end test: two WireGuard peers in one process handshake over loopback UDP, and requests through the proxy reach an HTTP server on the far side, both forwarded and via CONNECT; requests without the token get 407.

## Key Files

| File | Purpose |
|---|---|
| `wgbridge/bridge.go` | Starts and stops the userspace WireGuard device and the local proxy; reports status |
| `wgbridge/proxy.go` | Token-protected HTTP proxy (forwarding and CONNECT) dialing through the tunnel; idle tracking |
| `wgbridge/bridge_test.go` | End-to-end two-peer test, token check, status parsing |
| `wgbridge/build.sh` | gomobile build and local Maven publish |
| `composeApp/src/commonMain/.../tunnel/WgConfig.kt` | Parses and checks the client config; converts it for wireguard-go |
| `composeApp/src/commonMain/.../tunnel/Cidr.kt` | IPv4/IPv6 network matching |
| `composeApp/src/commonMain/.../tunnel/TunnelRoutes.kt` | Which hosts go through the tunnel |
| `composeApp/src/commonMain/.../tunnel/TunnelManager.kt` | On-demand start, idle shutdown, status, settings operations; bridge interface |
| `composeApp/src/androidMain/.../tunnel/TunnelProxy.android.kt` | Per-request routing in the shared HTTP client; tunnel start before retries; token handling |
| `composeApp/src/androidMain/.../Platform.android.kt` | Installs the routing in the shared HTTP client |
| `androidApp/src/wireguard/.../GoWireGuardBridge.kt` | Connects the gomobile library; included only when the library is built |
| `androidApp/src/main/.../KaiApplication.kt` | Registers the bridge when present |
| `composeApp/src/commonMain/.../ui/settings/WireGuardSection.kt` | Settings UI |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Stored config, routes, on/off, idle time |
| `composeApp/src/commonMain/.../network/NetworkExceptions.kt` | Shows tunnel failures with their reason |
