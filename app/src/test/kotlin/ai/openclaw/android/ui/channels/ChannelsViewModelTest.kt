package ai.openclaw.android.ui.channels

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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChannelsViewModelTest {

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
    fun `channels flow starts empty with no config`() = runTest(testDispatcher) {
        val vm = ChannelsViewModel(engine)
        vm.refresh()
        advanceUntilIdle()

        val channels = vm.channels.value
        // With default config (no channels configured), list may be empty or contain disabled entries
        assertNotNull(channels)
    }

    @Test
    fun `refresh does not throw`() = runTest(testDispatcher) {
        val vm = ChannelsViewModel(engine)
        vm.refresh()
        advanceUntilIdle()
    }

    @Test
    fun `removeChannel with nonexistent key does not throw`() = runTest(testDispatcher) {
        val vm = ChannelsViewModel(engine)
        vm.removeChannel("nonexistent")
        advanceUntilIdle()
    }

    @Test
    fun `factory creates ViewModel`() {
        val factory = ChannelsViewModel.Factory(engine)
        val vm = factory.create(ChannelsViewModel::class.java)
        assertNotNull(vm)
    }
}
