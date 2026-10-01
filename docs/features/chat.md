# Chat & Conversations

**Last verified:** 2026-09-30

Kai's chat system manages the message history, conversation persistence, file attachments, and speech output. Conversations are service-independent — switching providers does not affect which conversation is loaded or restored. Multiple conversations are persisted and browsable via a history sheet.

## Concepts

### Conversation

A persisted chat session containing an id (UUID), message list, timestamps (`createdAt`, `updatedAt`), a title, and a type (`chat`, `heartbeat`, or `interactive`). Conversations are stored in a local database (browser builds use the settings store) and restored across app launches.

### History

The in-memory message list that drives the UI. Each entry has a role: USER, ASSISTANT, TOOL, or TOOL_EXECUTING. History is the source of truth during a session; it is written to a Conversation on save.

### Conversation Title

Auto-derived from the first user message when a conversation is saved for the first time. Truncated to ~50 characters at a word boundary. Once set, titles are not updated.

## Conversation Lifecycle

- The "current conversation" pointer is persisted across launches: opening the app restores whichever conversation was last active, including an empty new chat the user explicitly started
- If the persisted pointer references a conversation that no longer exists (or is null because the user started a new chat), the app opens to an empty new chat
- "New Chat" clears history, unsets the current conversation pointer, and persists the empty state — so an unused new chat survives an app restart
- A new conversation ID (UUID) is generated on first successful save (after the first assistant response) and immediately becomes the persisted current pointer
- Conversations are saved after each assistant response
- Only the most recent 20 exchanges are persisted per chat conversation; heartbeat conversations have a separate, larger cap of 50 messages so longer automation runs are not truncated as aggressively
- Multiple conversations are persisted — starting a new chat preserves previous conversations
- Conversations are service-independent — switching services does not affect which conversation is loaded
- Interactive vs normal chat mode is persisted alongside the current pointer, so an empty interactive chat also survives a restart
- On the first launch after upgrading from a build that did not persist the current-conversation pointer, a one-time migration pins the most recently updated conversation as the current pointer so the user resumes where they left off
- On Android, sharing plain text into Kai from another app (the system share sheet) starts a new conversation and puts the shared text in the input field, unsent. The previous conversation stays in history. The user can edit the text or add an instruction, then send as usual. Opening Kai from the launcher or the assist gesture is unchanged; assist still starts an empty new chat

## Chat History

- A history icon appears in the top bar when saved conversations other than the current one exist
- Tapping it opens a bottom sheet listing all chat conversations sorted by last updated (newest first)
- Each item shows the title and formatted date
- Non-interactive conversations are outlined with a primary-colored border; interactive-mode conversations get an animated gradient border. The active conversation's title is rendered in the primary color (inactive titles use onBackground)
- Tapping an item loads that conversation and dismisses the sheet
- Each item has a delete button that defers deletion with a snackbar "Undo" option (~4 seconds) before the conversation is permanently removed. The snackbar appears inside the history sheet so it remains visible while the sheet is open, and the sheet stays open so multiple conversations can be deleted in sequence
- Deleting the active conversation clears the chat
- Heartbeat conversations are included in the history list with a "Heartbeat" label badge, and can also be accessed via the heartbeat banner

## Message Sending

- User message is added to history, then an API call is made via the fallback chain
- Tool calls are executed inline (TOOL_EXECUTING shown during execution, TOOL result stored after)
- On success, the conversation is saved
- On failure, an error is displayed with a retry button
- **Free rate-limit upsell:** when the user has **no configured services** (only Free FAST/EXPERT) and the request fails with a rate-limit, quota-exhausted, or Free proxy capacity error (e.g. “All free providers failed”), the chat shows a special panel instead of the generic error. It explains that Free is rate-limited and shows compact provider chips (icon + name) for free-usage providers (Groq, Cerebras, Gemini, OpenRouter, Ollama Cloud) in a flow layout; tapping a chip opens that provider’s API-key page in the browser. A retry control remains available. The panel is not shown if any non-Free service is already configured.

## Cancel

- While a request is in progress, a stop button replaces the send button in the input field
- Clicking stop cancels the ongoing API request and any in-flight tool executions
- After cancellation, the loading state clears and the send button reappears when typing

## Retry & Regenerate

- **Retry** resends the current prompt
- **Regenerate** removes all messages after the last user message, then resends

## File Attachments

Multiple files can be attached to a single prompt. Each file is added one at a time via the file picker or drag-and-drop, and appears as a chip below the input. Clicking a chip removes that specific file from the queue. All queued files are cleared after the prompt is sent. Four categories of files are supported:

### Images
- Attach via file picker or drag-and-drop
- Compressed to JPEG and Base64-encoded
- Maximum raw input size: 50 MB; maximum size after compression: 15 MB — rejected with a size error if exceeded
- Sent as `image_url` (OpenAI-compatible), `image` block (Anthropic), or `inline_data` (Gemini)
- Not offered on the built-in Free service — its proxy fans out to text-only fallback models that reject multimodal content
- Shown as a preview thumbnail (max 200dp wide) inside the user message bubble
- Clicking the thumbnail opens a full-screen viewer with pinch-to-zoom, double-tap to toggle zoom, pan when zoomed, and a close button in the top-right (also dismissable via the Android back button or by tapping the backdrop; desktop has no keyboard shortcut for dismissal)

### Text files
- Supports `.txt`, `.md`, `.json`, `.csv`, `.xml`, `.yaml`, `.html`, `.css`, `.js`, `.ts`, `.kt`, `.py`, `.rs`, `.go`, `.c`, `.cpp`, `.swift`, `.sh`, `.sql`, `.toml`, `.ini`, `.log`, `.gradle`, and more
- Maximum size: 200 KB per file
- Content is decoded at send time and concatenated into the user message with a filename header per file
- Works with all providers (content is inlined as text)
- Shown as a filename chip in the user message bubble

### PDFs
- Base64-encoded without compression
- Maximum size: 20 MB — rejected with a size error if exceeded
- PDF attachments are advertised only by services with native document support: Anthropic, Gemini, OpenAI, and OpenRouter. The file picker offers PDF on those services only
- Sent as a `document` block (Anthropic) or `inline_data` (Gemini). On the OpenAI-compatible wire path (OpenAI, OpenRouter, and other OpenAI-compatible services), PDF binaries are currently dropped from the request body — only image parts are encoded as `image_url` — so a PDF attach on OpenAI/OpenRouter is accepted by the UI but not transmitted to the model
- Shown as a filename chip in the user message bubble

### Audio
- Supports `.wav`, `.mp3`, `.m4a`, `.ogg`, `.opus`, `.flac`, `.aac`; maximum size 20 MB
- Offered only when the service a new message goes to accepts audio: the per-instance "Model accepts audio input" advanced setting, or, when that is unset, a model-name heuristic (omni, Voxtral, Gemma 3n, Ultravox, gpt-audio, … and every Gemini model). Never on-device, Free or Anthropic
- Sent as an `input_audio` part (OpenAI-compatible — wav and mp3 only, the formats llama.cpp accepts) or `inline_data` (Gemini). Dropped for Anthropic, the OpenAI Responses API and any model without audio input
- A message carrying audio skips fallback entries that can't hear it (the thinking indicator names the skipped service) instead of silently losing the audio
- Shown as a play/stop chip in the user message bubble; recordings are labelled "Voice message"
- Audio is dropped from the saved copy first when a message would exceed the storage cap, replaced by a "[voice message — audio not saved]" marker so the history still reads correctly (about 23 s of recorded 16 kHz audio fits)

### General behavior
- The attachment button is shown whenever the active service supports file attachments (text files work with all remote models); it is hidden when the active service runs on-device, since on-device services do not support attachments
- Unsupported file types (e.g., `.zip`) show an error message
- Files exceeding the per-category size limit show a size error; size is checked by stat before the file is read, so multi-gigabyte attachments are rejected without allocating memory for the full contents
- Long filenames in chips are truncated with an ellipsis while preserving the extension
- File attachments persist across conversation save/restore via an `attachments` list on each message; older conversations saved with a single-file schema are migrated on load. An attachment large enough to push its message past the storage cap is dropped from the saved copy (see [Conversation Storage](#conversation-storage)) — the message text stays

## Voice Input

Speech is recognised by a model, not by the platform's speech recognizer — either a dedicated **speech-to-text model** or a chat model that accepts audio. Voice input is Android-only and needs the microphone permission, which is requested on first use; a denial shows a snackbar.

- **Speech-to-text model** (preferred): set "Speech-to-text model" (and optionally a language such as `de`) in a service's Advanced settings. Recordings are sent to that endpoint's OpenAI-style `/audio/transcriptions` (Whisper, Qwen3-ASR, … — e.g. behind llama-swap or LiteLLM), and the transcript goes to the chat model as ordinary text, so the chat model does not need audio input. The first configured service with such a model is used, independent of the fallback order; "Test speech-to-text" sends a short silent clip to check the endpoint
- Without a speech-to-text model, recordings go to a chat model that accepts audio (see [Audio](#audio))

- **Mic button** — appears in place of the send button whenever the input is empty and nothing is loading. If the target model doesn't accept audio (see [Audio](#audio)), tapping it shows how to enable audio input instead of recording
- **Tap** records a voice message (16 kHz mono WAV). The button pulses and turns into a send button, with a ✕ next to it to discard. Recording stops after 2 minutes at the latest
- **Send mode** (Settings > General > "Transcribe voice messages first"): on puts the transcript into the message box for editing. Off sends the speech-to-text transcript right away — or, without a speech-to-text model, sends the recording itself as an audio attachment
- **Long-press** starts **talk mode**, a hands-free loop: listen → the take ends after a pause (Settings > General > "Talk mode pause", default 1.2 s) → send (audio, or the transcript when transcribe-first is on) → the reply is read aloud → listen again. The button shows the talk-mode icon and pulses while listening; the input placeholder shows listening / thinking / speaking
- Talk mode turns speech output on, never records while a reply is being spoken (it waits until no read-out has run for 1.5 s, since one reply can be read as several back-to-back parts), and ends when the user taps the button, nobody speaks for 30 seconds, recording fails, or a request fails. Leaving talk mode stops any speech and cancels a request still in flight
- Silence detection is an energy gate relative to the background noise measured in the first ~300 ms of each take

## Speech Output (TTS)

- **Remote voice (optional):** set "Text-to-speech model" (e.g. `voice-tts`), "Voice" (default `alloy`) and optionally "Speech speed" in a service's Advanced settings. Replies, the per-message play button and talk mode then use that endpoint's OpenAI-style `/audio/speech` (mp3) instead of the phone's voice. The first configured service with such a model is used. "Preview voice" plays a short sample with the current settings. The Voice field's ▾ button lists the voices the server offers (`GET /audio/voices?model=<tts model>`, which accepts `{"voices":[…]}`, `{"data":[…]}` or a bare array of names or objects with `id` / `voice_id` / `name`). Loading the list fills in a blank voice with the server's first one. Pick a listed voice: servers that clone voices from reference clips (KoboldCpp with Qwen3-TTS) give an unknown name a new random voice on every request, so it changes from sentence to sentence
- Remote speech is split into sentence chunks (a short first chunk, then up to ~360 characters) and pipelined, so the next chunk is synthesized while the current one plays and long replies start speaking after their first sentence
- If the remote endpoint fails mid-message, the rest of that message is read with the phone's voice. Stopping (top-bar toggle, play button, mic, leaving talk mode) cancels synthesis and playback

- Toggle in the top bar enables auto-play of new assistant messages
- Per-message play button on assistant messages
- Markdown is stripped before speaking
- Speech uses the engine the user selected in the platform's system speech settings, so third-party engines are honoured; on Android, Google's engine is only used as a fallback when the system default cannot start
- The engine's own default voice is kept as-is. Kai only picks a different voice when the default one does not speak the system language, in which case the first voice matching that language is selected

## Conversation Storage

- On Android, iOS, and desktop, conversations live in a local SQLite database in app-private storage: one row per conversation plus one row per message, so saving a turn writes only the affected conversation instead of re-serializing the whole history
- The browser build has no persistent database and keeps the full conversation list as a JSON blob in the settings store (see [encryption.md](encryption.md))
- Conversations are upserted — updating a conversation replaces the existing entry by ID, new conversations are appended; the list loads ordered by creation time
- Each conversation also retains a rolling tail of its sandbox shell transcript (last ~10,000 characters) so that follow-up commands in a resumed conversation see the prior shell context; transcript updates write only that field
- Migration chain, run once on first load: the legacy encrypted `conversations.enc` file (XOR with a 32-byte random key) migrates into the settings-store blob; a settings-store blob found on a database-capable platform is imported into the database and removed. Settings import reuses the same path — imported conversations are staged in the settings store and absorbed into the database on the next load, replacing its content
- The database structure is versioned and lives on user devices: any future change to its tables or columns requires an accompanying migration step so existing installs upgrade in place (message contents are stored as JSON and tolerate unknown fields, so message-level additions do not need one)
- A stored message is capped at roughly one megabyte, because Android reads database rows through a fixed-size window and a larger row would abort the whole load and crash the app on every launch. A message past the cap is stored without its file attachments; if it is still too large, its text is kept only up to a fixed head length. The in-memory message the model sees during the ongoing turn is untouched — only the persisted copy shrinks
- Messages already stored above that cap by an earlier version are skipped when the conversation loads, so a database that used to crash the app opens with the rest of its history intact

## UI Elements

- **Top bar**: New Chat, Chat History, a Sandbox toggle (Android only, shown between History and TTS when the sandbox feature is available on the device), TTS toggle, Settings (on mobile; on non-mobile, Settings is in the navigation tab bar)
- **Scroll to bottom**: a small floating action button (down arrow) appears when the user has scrolled up past the latest messages; tapping it animates back to the bottom
- **Messages**: user (right-aligned, with optional image preview), assistant (Markdown-rendered + action buttons), tool executing (spinner), loading indicator, error with retry (or free-provider suggestions panel when Free is rate-limited with no services configured). When the fallback chain answered with an alternate service rather than the user's selected one, a small "Answered by …" label is shown under the assistant message naming the service that produced the response
- **Input**: text field, send/stop button, attachment button, file chip
- **Empty state**: animated logo + welcome message
- **Drag-and-drop**: supported for file attachments
- **History sheet**: bottom sheet listing saved conversations with title, date, active highlight, and delete

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../data/Conversation.kt` | Conversation and message data classes, type constants |
| `composeApp/src/commonMain/.../data/ConversationStorage.kt` | In-memory conversation flow, transcript trimming, legacy migration |
| `composeApp/src/commonMain/.../data/ConversationPersistence.kt` | SQL and settings-blob persistence backends, staged-import handling |
| `composeApp/src/commonMain/sqldelight/com/inspiredandroid/kai/db/conversation.sq` | Conversation and message table schema and queries |
| `composeApp/src/commonMain/.../data/FileClassification.kt` | File category enum, MIME/extension classifier, size constants, file exceptions |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | History management, conversation save/restore/delete, title derivation, message sending |
| `composeApp/src/commonMain/.../ui/chat/ChatViewModel.kt` | Chat UI state, send/retry/regenerate/cancel/loadConversation/deleteConversation actions |
| `composeApp/src/commonMain/.../ui/chat/ChatScreen.kt` | Chat UI composables, history sheet and heartbeat banner wiring |
| `composeApp/src/commonMain/.../ui/chat/composables/ChatHistorySheet.kt` | Bottom sheet listing saved conversations |
| `composeApp/src/commonMain/.../ui/chat/composables/HeartbeatBanner.kt` | Dismissable banner for heartbeat notifications |
| `composeApp/src/commonMain/.../ui/chat/composables/TopBar.kt` | Top bar with new chat, history, TTS, and settings icons |
| `composeApp/src/commonMain/.../ui/chat/composables/QuestionInput.kt` | Text input with send/stop button, mic and talk-mode button |
| `composeApp/src/commonMain/.../audio/VoiceRecorder.kt` | Recorder / player interfaces and silence-detection config |
| `composeApp/src/commonMain/.../audio/Speaker.kt` | Speaker abstraction: remote text-to-speech with sentence chunking, falling back to the system voice |
| `composeApp/src/androidMain/.../audio/VoiceRecorder.android.kt` | 16 kHz WAV recording with silence detection; attachment playback |
| `composeApp/src/commonMain/.../ui/chat/composables/UserMessage.kt` | User bubble with image previews, file and audio chips |
| `androidApp/src/main/AndroidManifest.xml` | Share-sheet registration for plain text (`ACTION_SEND`) |
| `androidApp/src/main/kotlin/.../MainActivity.kt` | Reads shared text from `ACTION_SEND` and hands it to chat |
