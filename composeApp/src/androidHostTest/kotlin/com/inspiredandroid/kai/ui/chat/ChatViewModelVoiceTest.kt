package com.inspiredandroid.kai.ui.chat

import com.inspiredandroid.kai.audio.RecordingResult
import com.inspiredandroid.kai.audio.VadConfig
import com.inspiredandroid.kai.audio.VoiceRecorder
import com.inspiredandroid.kai.data.TaskScheduler
import com.inspiredandroid.kai.testutil.FakeDataRepository
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scripted recorder: each take returns the next queued result; a null entry waits for stop()/cancel(). */
private class FakeVoiceRecorder(vararg results: RecordingResult?) : VoiceRecorder {
    val queue = ArrayDeque(results.toList())
    val vadConfigs = mutableListOf<VadConfig?>()
    private var pending: CompletableDeferred<RecordingResult>? = null
    var manualFile: PlatformFile? = null

    override fun isSupported() = true
    override val level: StateFlow<Float> = MutableStateFlow(0f)

    override suspend fun record(vad: VadConfig?, maxDurationMs: Long): RecordingResult {
        vadConfigs += vad
        val next = queue.removeFirstOrNull() ?: return CompletableDeferred<RecordingResult>().also { pending = it }.await()
        return next
    }

    override fun stop() {
        pending?.complete(RecordingResult.Recorded(manualFile!!, 1000))
    }

    override fun cancel() {
        pending?.complete(RecordingResult.Cancelled)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelVoiceTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repo: FakeDataRepository

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repo = FakeDataRepository()
        repo.audioInputSupported = true
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun wavFile(): PlatformFile {
        val f = File.createTempFile("voice-", ".wav")
        f.writeBytes(ByteArray(64))
        f.deleteOnExit()
        return PlatformFile(f)
    }

    /** Builds the VM and keeps its WhileSubscribed state flow collected so `state.value` is live. */
    private fun TestScope.viewModel(recorder: VoiceRecorder, micGranted: Boolean = true): ChatViewModel {
        val vm = ChatViewModel(
            repo,
            TaskScheduler(repo, enabled = false),
            UnconfinedTestDispatcher(testDispatcher.scheduler),
            requestMicPermission = { micGranted },
            voiceRecorder = recorder,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test
    fun `tap record then stop sends the recording as audio`() = runTest(testDispatcher) {
        val file = wavFile()
        val recorder = FakeVoiceRecorder(null).apply { manualFile = file }
        val vm = viewModel(recorder)

        vm.state.value.actions.startRecording()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(VoiceState.Recording, vm.state.value.voiceState)
        assertNull(recorder.vadConfigs.single(), "manual recording has no silence detection")

        vm.state.value.actions.stopRecording()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(VoiceState.Idle, vm.state.value.voiceState)
        val (question, files) = repo.askCalls.single()
        assertEquals("", question)
        assertEquals(listOf(file), files)
    }

    @Test
    fun `cancel discards the recording`() = runTest(testDispatcher) {
        val recorder = FakeVoiceRecorder(null).apply { manualFile = wavFile() }
        val vm = viewModel(recorder)
        vm.state.value.actions.startRecording()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.state.value.actions.cancelRecording()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(repo.askCalls.isEmpty())
        assertEquals(VoiceState.Idle, vm.state.value.voiceState)
    }

    @Test
    fun `transcribe first puts the transcript in the composer`() = runTest(testDispatcher) {
        repo.fakeVoiceTranscribeFirst = true
        repo.transcriptResult = "hello there"
        val file = wavFile()
        val recorder = FakeVoiceRecorder(RecordingResult.Recorded(file, 1500))
        val vm = viewModel(recorder)

        vm.state.value.actions.startRecording()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(file), repo.transcribedFiles)
        assertTrue(repo.askCalls.isEmpty())
        assertEquals("hello there", vm.state.value.composerPrefill)
    }

    @Test
    fun `denied microphone shows a snackbar and does not record`() = runTest(testDispatcher) {
        val recorder = FakeVoiceRecorder(null)
        val vm = viewModel(recorder, micGranted = false)
        vm.state.value.actions.startRecording()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(recorder.vadConfigs.isEmpty())
        assertNotNull(vm.state.value.snackbarMessage)
    }

    @Test
    fun `mic shows even without an audio model and explains on tap`() = runTest(testDispatcher) {
        repo.audioInputSupported = false
        val recorder = FakeVoiceRecorder(null)
        val vm = viewModel(recorder)
        assertTrue(vm.state.value.isVoiceInputAvailable)

        vm.state.value.actions.startRecording()
        vm.state.value.actions.toggleTalkMode()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(recorder.vadConfigs.isEmpty(), "nothing is recorded for a model that can't hear")
        assertEquals(TalkMode.Off, vm.state.value.talkMode)
        assertNotNull(vm.state.value.snackbarMessage)
    }

    @Test
    fun `speech-to-text model sends the transcript as text right away`() = runTest(testDispatcher) {
        repo.audioInputSupported = false
        repo.speechToTextConfigured = true
        repo.transcriptResult = "wie spät ist es"
        val file = wavFile()
        val vm = viewModel(FakeVoiceRecorder(RecordingResult.Recorded(file, 1500)))

        vm.state.value.actions.startRecording()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(file), repo.transcribedFiles)
        val (question, files) = repo.askCalls.single()
        assertEquals("wie spät ist es", question)
        assertTrue(files.isEmpty(), "the chat model gets text, not audio")
        assertNull(vm.state.value.composerPrefill)
    }

    @Test
    fun `speech-to-text with review puts the transcript in the composer`() = runTest(testDispatcher) {
        repo.audioInputSupported = false
        repo.speechToTextConfigured = true
        repo.fakeVoiceTranscribeFirst = true
        repo.transcriptResult = "draft"
        val vm = viewModel(FakeVoiceRecorder(RecordingResult.Recorded(wavFile(), 1500)))

        vm.state.value.actions.startRecording()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(repo.askCalls.isEmpty())
        assertEquals("draft", vm.state.value.composerPrefill)
    }

    @Test
    fun `talk mode with a speech-to-text model sends transcripts`() = runTest(testDispatcher) {
        repo.audioInputSupported = false
        repo.speechToTextConfigured = true
        repo.fakeVoiceTranscribeFirst = true // review is skipped in talk mode
        repo.transcriptResult = "hallo"
        val vm = viewModel(FakeVoiceRecorder(RecordingResult.Recorded(wavFile(), 2000), RecordingResult.NoSpeech))

        vm.state.value.actions.toggleTalkMode()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("hallo" to emptyList<PlatformFile>(), repo.askCalls.single())
        assertEquals(TalkMode.Off, vm.state.value.talkMode)
    }

    @Test
    fun `talk mode sends each take and ends when nobody speaks`() = runTest(testDispatcher) {
        repo.fakeTalkSilenceMs = 900
        val recorder = FakeVoiceRecorder(
            RecordingResult.Recorded(wavFile(), 2000),
            RecordingResult.Recorded(wavFile(), 2000),
            RecordingResult.NoSpeech,
        )
        val vm = viewModel(recorder)

        vm.state.value.actions.toggleTalkMode()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, repo.askCalls.size)
        assertEquals(3, recorder.vadConfigs.size)
        assertEquals(900, recorder.vadConfigs.first()!!.silenceMs)
        assertTrue(vm.state.value.isSpeechOutputEnabled, "talk mode reads replies aloud")
        assertEquals(TalkMode.Off, vm.state.value.talkMode)
    }

    @Test
    fun `talk mode waits for the reply to be spoken before listening again`() = runTest(testDispatcher) {
        val recorder = FakeVoiceRecorder(RecordingResult.Recorded(wavFile(), 2000), null)
        val vm = viewModel(recorder)

        vm.state.value.actions.toggleTalkMode()
        testDispatcher.scheduler.runCurrent()
        // The screen starts reading the reply out; the loop must not record meanwhile.
        vm.state.value.actions.setIsSpeaking(true, "reply")
        testDispatcher.scheduler.runCurrent()
        assertEquals(TalkMode.Speaking, vm.state.value.talkMode)
        assertEquals(1, recorder.vadConfigs.size)

        vm.state.value.actions.setIsSpeaking(false, "reply")
        testDispatcher.scheduler.runCurrent()
        assertEquals(TalkMode.Listening, vm.state.value.talkMode)
        assertEquals(2, recorder.vadConfigs.size)

        vm.state.value.actions.toggleTalkMode()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(TalkMode.Off, vm.state.value.talkMode)
    }
}
