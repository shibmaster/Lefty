package com.inspiredandroid.kai.ui.settings

import com.inspiredandroid.kai.DaemonController
import com.inspiredandroid.kai.data.InstanceAdvancedSettings
import com.inspiredandroid.kai.data.Service
import com.inspiredandroid.kai.data.TaskScheduler
import com.inspiredandroid.kai.network.parseVoiceList
import com.inspiredandroid.kai.testutil.FakeDataRepository
import com.inspiredandroid.kai.tools.AppPermission
import com.inspiredandroid.kai.tools.PermissionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TtsVoicesTest {

    @Test
    fun `voice lists in the common server shapes`() {
        val kobold = """{"status": "ok", "voices": ["kobo", "cheery", "sleepy"]}"""
        assertEquals(listOf("kobo", "cheery", "sleepy"), parseVoiceList(kobold))
        assertEquals(listOf("af_bella", "am_adam"), parseVoiceList("""["af_bella", "am_adam", "af_bella"]"""))
        assertEquals(listOf("a", "b", "c"), parseVoiceList("""{"data": [{"id": "a"}, {"voice_id": "b"}, {"name": "c"}]}"""))
        assertTrue(parseVoiceList("""{"detail": "Not Found"}""").isEmpty())
        assertTrue(parseVoiceList("not json").isEmpty())
    }

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repo: FakeDataRepository
    private val daemon = object : DaemonController {
        override fun start() {}
        override fun stop() {}
    }

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repo = FakeDataRepository()
        repo.setConfiguredServices(Service.OpenAICompatible)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(): SettingsViewModel {
        val vm = SettingsViewModel(repo, daemon, PermissionController(AppPermission.POST_NOTIFICATIONS), TaskScheduler(repo, enabled = false), testDispatcher)
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    private val id get() = repo.getConfiguredServiceInstances().single().instanceId

    @Test
    fun `loaded voices fill the picker and replace a blank voice`() = runTest(testDispatcher) {
        repo.fakeTtsVoices = Result.success(listOf("kobo", "cheery"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.actions.onLoadTtsVoices(id)
        advanceUntilIdle()

        val entry = vm.state.value.configuredServices.single()
        assertEquals(listOf("kobo", "cheery"), entry.ttsVoices)
        assertEquals(ServerDetectState.Success, entry.ttsVoicesState)
        assertEquals("kobo", entry.advanced.ttsVoice)
        assertEquals("kobo", repo.getInstanceAdvancedSettings(id).ttsVoice)
    }

    @Test
    fun `a chosen voice is kept`() = runTest(testDispatcher) {
        repo.updateInstanceAdvancedSettings(id, InstanceAdvancedSettings(ttsVoice = "chatty"))
        repo.fakeTtsVoices = Result.success(listOf("kobo", "chatty"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.actions.onLoadTtsVoices(id)
        advanceUntilIdle()

        assertEquals("chatty", vm.state.value.configuredServices.single().advanced.ttsVoice)
    }

    @Test
    fun `a server without a voice list reports none`() = runTest(testDispatcher) {
        repo.fakeTtsVoices = Result.failure(IllegalStateException("404"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.actions.onLoadTtsVoices(id)
        advanceUntilIdle()

        val entry = vm.state.value.configuredServices.single()
        assertTrue(entry.ttsVoices.isEmpty())
        assertEquals(ServerDetectState.Failed, entry.ttsVoicesState)
        assertEquals(null, entry.advanced.ttsVoice)
    }
}
