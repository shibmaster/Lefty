package com.inspiredandroid.kai.ui.settings

import com.inspiredandroid.kai.DaemonController
import com.inspiredandroid.kai.data.TaskScheduler
import com.inspiredandroid.kai.testutil.FakeDataRepository
import com.inspiredandroid.kai.tools.AppPermission
import com.inspiredandroid.kai.tools.PermissionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DaemonBatteryExemptionTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeDaemon(var exempt: Boolean) : DaemonController {
        var requests = 0
        override fun start() {}
        override fun stop() {}
        override fun isBatteryOptimizationExempt() = exempt
        override fun requestBatteryOptimizationExemption() {
            requests++
        }
    }

    @BeforeTest
    fun setup() = Dispatchers.setMain(testDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(daemon: FakeDaemon): SettingsViewModel {
        val repo = FakeDataRepository()
        return SettingsViewModel(repo, daemon, PermissionController(AppPermission.POST_NOTIFICATIONS), TaskScheduler(repo, enabled = false), testDispatcher)
    }

    @Test
    fun `exemption state is read at start and refreshed on resume`() = runTest(testDispatcher) {
        val daemon = FakeDaemon(exempt = false)
        val viewModel = vm(daemon)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        assertFalse(viewModel.state.value.isBatteryOptimizationExempt)

        daemon.exempt = true // user allowed it in the system dialog
        viewModel.actions.onRefreshBatteryExemption()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.isBatteryOptimizationExempt)
    }

    @Test
    fun `allow button asks the system`() = runTest(testDispatcher) {
        val daemon = FakeDaemon(exempt = false)
        vm(daemon).actions.onRequestBatteryExemption()
        assertEquals(1, daemon.requests)
    }
}
