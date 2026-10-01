# Lefty

<img src="branding/lefty-icon-preview.png" height="96" alt="Lefty icon" align="right">

An Android AI assistant for **self-hosted models**: tunable per-model request settings, a voice you can talk to hands-free, a built-in WireGuard tunnel to reach your home server from anywhere, and a background daemon that keeps heartbeats running. Lefty is a fork of [Kai 9000](https://github.com/SimonSchubert/Kai), extended for running against your own llama.cpp / llama-swap / LiteLLM servers.

## What's new in Lefty

### Model settings for self-hosted servers
Every service has an **Advanced** section (Settings → Services → *service* → Advanced). Blank fields keep the defaults.
- **Timeouts:** request, idle-socket and connect timeouts, for slow local models (the default is 180 s).
- **Retries & fallback:** retry count and delay, "fail over immediately on timeout", and whether the service may be used as a fallback.
- **Context size:** set it manually, or **Detect from server** reads llama.cpp's `/props` (also through llama-swap). This fixes auto-compaction, which assumed 100K tokens for every custom model.
- **Auto-compaction:** on/off, the trigger percentage of the context, and how many recent exchanges to keep verbatim.
- **Sampling:** temperature, top-p, top-k, min-p, max tokens, repeat/presence/frequency penalty, seed and stop sequences. The llama.cpp extras are only sent to OpenAI-compatible servers.

### Voice
- **Mic button:** tap to record a voice message, tap ✓ to send.
- **Talk mode:** hold the mic for a hands-free conversation. It listens, ends your turn when you pause, sends, reads the reply aloud, and listens again. It waits until the whole reply has been spoken before listening, never records itself, and gives you 30 s to answer.
- **Speech-to-text model:** any OpenAI-style `/audio/transcriptions` endpoint (Whisper, Qwen3-ASR, …) transcribes your voice, and the text goes to your chat model, which doesn't need audio input. An optional language hint and a **Test** button are included.
- **Transcribe first:** review and edit the transcript before sending.
- **Audio attachments:** wav/mp3/… for chat models that accept audio input directly.

### Natural voice output
- **Text-to-speech model:** replies, the play button and talk mode can use an OpenAI-style `/audio/speech` endpoint instead of the phone's voice, with speed and a **Preview** button.
- **Voice picker:** the ▾ next to the voice lists the voices the server offers (`/audio/voices`), with a short description each. Pick one from the list: voice-cloning servers such as KoboldCpp give an unknown name a different voice in every sentence.
- **Voice description:** for voice-design models (Qwen3-TTS VoiceDesign), describe the voice, e.g. "deep, gravelly older male voice, New York accent".
- Long replies are split into sentences and synthesized ahead, so speech starts after the first sentence.
- If the server is unreachable, the phone's voice takes over.
- **Read thinking aloud:** long-press the 🔊 icon at the top to also hear the model's reasoning before the answer. Off by default; when off, reasoning is never read out.

### Rename chats
- Tap the ✏️ next to a chat in the history to give it your own name. Leave the name empty to go back to the first message.

### Background heartbeats that keep running
- **Daemon Mode** (Settings → General) no longer stops after about 6 hours on Android 15+.
- It restarts after a reboot or an app update.
- It asks for a battery-optimization exemption, so Doze doesn't pause heartbeats and scheduled tasks.

### Built-in WireGuard tunnel
- **Reach your home server from any network** without the WireGuard app: import a standard WireGuard client config (`.conf`) in Settings → General → **WireGuard tunnel**.
- **Only Lefty's requests to your home addresses** go through it (the config's AllowedIPs, or your own list of networks and host names). Everything else connects directly.
- **No VPN slot:** WireGuard runs inside the app, so there's no VPN permission or key icon, and another VPN can stay on.
- **Connects when needed:** it comes up on the first request (heartbeats in the background too) and disconnects when idle. A status line shows the handshake and traffic, and **Connect now** checks a config in seconds.
- **Mobile-safe packet size:** MTU 1280 unless the config sets one, like the WireGuard app, so longer replies don't stall on mobile networks.
- **Private:** the config (with its private key) is stored encrypted and never included in settings export. Other apps on the phone can't use the tunnel.
- 64-bit phones (arm64) only.

### Smaller fixes
- When every service fails for the same reason, you see that reason instead of "All services failed". A server that can't take audio fails fast, with no retry and model-swap cascade.
- Invalid API keys and unknown models are no longer retried.
- Lefty has its own app ID (`ai.shibmaster.lefty`), name and icon, so it installs next to Kai.

## Install

Download the APK from [Releases](https://github.com/shibmaster/Lefty/releases) and install it. To move your setup over from Kai, export it in Kai (Settings → General → Export) and import it in Lefty.

## Example: llama-swap with speech-to-text and text-to-speech

| Setting (OpenAI-Compatible service) | Example |
|---|---|
| Base URL | `http://<llama-swap-host>:<port>/v1` |
| API key | your llama-swap key |
| Advanced → Model accepts audio input | off, unless the chat model has an audio mmproj |
| Advanced → Speech-to-text model / Language | `voice-stt` / `de` |
| Advanced → Text-to-speech model / Voice | `voice-tts` / pick from the list (e.g. `kobo`) |

## Example: WireGuard tunnel to a home server

1. On your WireGuard server, create a new peer for the phone and export its client config (a `.conf`, as you would for the WireGuard app).
2. In Lefty: Settings → General → **WireGuard tunnel** → **Import .conf** (or **Paste config**).
3. If the config says `AllowedIPs = 0.0.0.0/0`, enter just your home network under **Send through the tunnel** (e.g. `192.168.4.0/24`); otherwise cloud providers would go through home too.
4. Tap **Connect now**: it should report a handshake within a few seconds. Services whose base URL points at a home address (e.g. `http://192.168.4.103:41550/v1`) now work from any network.
5. If short replies arrive but longer ones hang, add `MTU = 1200` to the `[Interface]` section and import it again.

## Build from source

Requirements: JDK 21 and the Android SDK (platform 37, NDK 29.0.14206865).

```bash
./gradlew :androidApp:assembleFossDebug            # debug APK → androidApp/build/outputs/apk/foss/debug/
./gradlew :composeApp:testAndroidHostTest          # unit tests
```

The in-app WireGuard tunnel needs its Go library (Go and gomobile installed; see [docs/features/wireguard.md](docs/features/wireguard.md)). Without it the app builds with the tunnel hidden:

```bash
./wgbridge/build.sh                                # → wgbridge/repo (local Maven repo)
(cd wgbridge && go test ./...)                     # end-to-end tunnel test
```

A signed release build reads `KEYSTORE_FILE`, `KEY_ALIAS` and `KEYSTORE_PASSWORD` from the environment and runs `./gradlew :androidApp:assembleFossRelease`.

## Credits

Lefty is a modified version of **[Kai 9000](https://github.com/SimonSchubert/Kai) by Simon Schubert**. All of Kai's original features (multi-provider chat, memories, tasks, MCP, skills, the Linux sandbox, …) come from that project; see its [README](https://github.com/SimonSchubert/Kai#readme) and [documentation](https://kai9000.com/docs/). Licensed under the Apache License 2.0 (see [LICENSE.txt](LICENSE.txt)), with third-party components listed in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
