# Lefty

<img src="branding/lefty-icon-preview.png" height="96" alt="Lefty icon" align="right">

An Android AI assistant for **self-hosted models**: tunable per-model request settings, a voice you can talk to hands-free, and a background daemon that keeps heartbeats running. Lefty is a fork of [Kai 9000](https://github.com/SimonSchubert/Kai), extended for running against your own llama.cpp / llama-swap / LiteLLM servers.

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
- **Talk mode:** hold the mic for a hands-free conversation. It listens, ends your turn when you pause, sends, reads the reply aloud, and listens again. It never records while it's speaking.
- **Speech-to-text model:** any OpenAI-style `/audio/transcriptions` endpoint (Whisper, Qwen3-ASR, …) transcribes your voice, and the text goes to your chat model, which doesn't need audio input. An optional language hint and a **Test** button are included.
- **Transcribe first:** review and edit the transcript before sending.
- **Audio attachments:** wav/mp3/… for chat models that accept audio input directly.

### Natural voice output
- **Text-to-speech model:** replies, the play button and talk mode can use an OpenAI-style `/audio/speech` endpoint instead of the phone's voice. A voice name, speed and **Preview** button are included.
- Long replies are split into sentences and synthesized ahead, so speech starts after the first sentence.
- If the server is unreachable, the phone's voice takes over.
- **Read thinking aloud:** long-press the 🔊 icon at the top to also hear the model's reasoning before the answer.

### Background heartbeats that keep running
- **Daemon Mode** (Settings → General) no longer stops after about 6 hours on Android 15+.
- It restarts after a reboot or an app update.
- It asks for a battery-optimization exemption, so Doze doesn't pause heartbeats and scheduled tasks.

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
| Advanced → Text-to-speech model / Voice | `voice-tts` / `alloy` |

## Build from source

Requirements: JDK 21 and the Android SDK (platform 37, NDK 29.0.14206865).

```bash
./gradlew :androidApp:assembleFossDebug            # debug APK → androidApp/build/outputs/apk/foss/debug/
./gradlew :composeApp:testAndroidHostTest          # unit tests
```

A signed release build reads `KEYSTORE_FILE`, `KEY_ALIAS` and `KEYSTORE_PASSWORD` from the environment and runs `./gradlew :androidApp:assembleFossRelease`.

## Credits

Lefty is a modified version of **[Kai 9000](https://github.com/SimonSchubert/Kai) by Simon Schubert**. All of Kai's original features (multi-provider chat, memories, tasks, MCP, skills, the Linux sandbox, …) come from that project; see its [README](https://github.com/SimonSchubert/Kai#readme) and [documentation](https://kai9000.com/docs/). Licensed under the Apache License 2.0 (see [LICENSE.txt](LICENSE.txt)), with third-party components listed in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
