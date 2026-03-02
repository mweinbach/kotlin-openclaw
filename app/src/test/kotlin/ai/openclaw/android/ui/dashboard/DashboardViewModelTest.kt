package ai.openclaw.android.ui.dashboard

import ai.openclaw.android.AgentEngine
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DashboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var engine: AgentEngine

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.filesDir.resolve("config").deleteRecursively()
        context.filesDir.resolve("sessions").deleteRecursively()
        context.filesDir.resolve("cron").deleteRecursively()
        engine = AgentEngine(context)
        engine.loadConfig()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has default values`() = runTest(testDispatcher) {
        val vm = DashboardViewModel(engine)
        val state = vm.state.value
        assertNotNull(state)
    }

    @Test
    fun `refresh updates state`() = runTest(testDispatcher) {
        val vm = DashboardViewModel(engine)
        vm.refresh()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(0, state.channelCount, "No channels configured initially")
        assertEquals(0, state.sessionCount, "No sessions initially")
        assertEquals(0, state.memoryEntries, "No memory entries initially")
    }

    @Test
    fun `clearSessions empties sessions`() = runTest(testDispatcher) {
        val vm = DashboardViewModel(engine)
        vm.clearSessions()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(0, state.sessionCount)
    }

    @Test
    fun `reloadConfig does not throw`() = runTest(testDispatcher) {
        val vm = DashboardViewModel(engine)
        vm.reloadConfig()
        advanceUntilIdle()
    }

    @Test
    fun `gatewayPort defaults to 18789`() = runTest(testDispatcher) {
        val vm = DashboardViewModel(engine)
        vm.refresh()
        advanceUntilIdle()

        assertEquals(18789, vm.state.value.gatewayPort)
    }

    @Test
    fun `factory creates ViewModel`() {
        val factory = DashboardViewModelFactory(engine)
        val vm = factory.create(DashboardViewModel::class.java)
        assertNotNull(vm)
    }
}
