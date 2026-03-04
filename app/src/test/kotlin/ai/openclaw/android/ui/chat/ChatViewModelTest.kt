package ai.openclaw.android.ui.chat

import ai.openclaw.android.AgentEngine
import ai.openclaw.core.model.AcpRuntimeEvent
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatViewModelTest {

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
    fun `initial state has empty messages`() {
        val vm = ChatViewModel(engine)
        assertTrue(vm.messages.isEmpty())
    }

    @Test
    fun `initial state is not loading`() {
        val vm = ChatViewModel(engine)
        assertFalse(vm.isLoading)
    }

    @Test
    fun `initial state has no error`() {
        val vm = ChatViewModel(engine)
        assertNull(vm.errorMessage)
    }

    @Test
    fun `sendMessage adds user message to list`() = runTest(testDispatcher) {
        val vm = ChatViewModel(engine)
        vm.sendMessage("hello")
        advanceUntilIdle()

        // User message should be first
        assertTrue(vm.messages.isNotEmpty(), "Messages should not be empty after send")
        assertEquals("user", vm.messages.first().role)
        assertEquals("hello", vm.messages.first().content)
    }

    @Test
    fun `sendMessage with blank text is ignored`() = runTest(testDispatcher) {
        val vm = ChatViewModel(engine)
        vm.sendMessage("")
        advanceUntilIdle()

        assertTrue(vm.messages.isEmpty(), "Blank message should be ignored")
    }

    @Test
    fun `sendMessage with whitespace only is ignored`() = runTest(testDispatcher) {
        val vm = ChatViewModel(engine)
        vm.sendMessage("   ")
        advanceUntilIdle()

        assertTrue(vm.messages.isEmpty(), "Whitespace-only message should be ignored")
    }

    @Test
    fun `sessions flow starts empty`() = runTest(testDispatcher) {
        val vm = ChatViewModel(engine)
        val sessions = vm.sessions.value
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `refreshSessions does not throw`() = runTest(testDispatcher) {
        val vm = ChatViewModel(engine)
        vm.refreshSessions()
        advanceUntilIdle()
    }

    @Test
    fun `startNewSession clears messages`() = runTest(testDispatcher) {
        val vm = ChatViewModel(engine)
        vm.sendMessage("test")
        advanceUntilIdle()

        vm.startNewSession()
        assertTrue(vm.messages.isEmpty(), "Messages should be empty after starting new session")
    }

    @Test
    fun `deleteSession does not throw for nonexistent session`() = runTest(testDispatcher) {
        val vm = ChatViewModel(engine)
        vm.deleteSession("nonexistent-session-id")
        advanceUntilIdle()
    }

    @Test
    fun `factory creates ViewModel`() {
        val factory = ChatViewModelFactory(engine)
        val vm = factory.create(ChatViewModel::class.java)
        assertNotNull(vm)
    }

    @Test
    fun `sessionHeaders starts empty`() {
        val vm = ChatViewModel(engine)
        assertTrue(vm.sessionHeaders.value.isEmpty())
    }

    @Test
    fun `applyToolCallEvent merges updates by toolCallId`() {
        val toolCalls = linkedMapOf<String, ChatToolCall>()

        applyToolCallEvent(
            toolCalls = toolCalls,
            event = AcpRuntimeEvent.ToolCall(
                text = "Calling read",
                tag = "tool_call",
                toolCallId = "call_1",
                status = "in_progress",
                title = "read",
            ),
        )
        applyToolCallEvent(
            toolCalls = toolCalls,
            event = AcpRuntimeEvent.ToolCall(
                text = "Completed read",
                tag = "tool_call_update",
                toolCallId = "call_1",
                status = "completed",
                title = "read",
            ),
        )

        assertEquals(1, toolCalls.size)
        val merged = toolCalls["call_1"]
        assertNotNull(merged)
        assertEquals("completed", merged.status)
        assertEquals("read", merged.title)
    }

    @Test
    fun `normalizeToolCallStatus infers failed from update text`() {
        val status = normalizeToolCallStatus(
            rawStatus = null,
            rawTag = "tool_call_update",
            eventText = "Tool call denied by policy",
            rawOutput = null,
        )
        assertEquals("failed", status)
    }

    @Test
    fun `normalizeToolCallStatus infers completed from update tag`() {
        val status = normalizeToolCallStatus(
            rawStatus = null,
            rawTag = "tool_call_update",
            eventText = "Completed read",
            rawOutput = null,
        )
        assertEquals("completed", status)
    }

    @Test
    fun `applyToolCallEvent merges no-id updates using fallback ids`() {
        val toolCalls = linkedMapOf<String, ChatToolCall>()
        applyToolCallEvent(
            toolCalls = toolCalls,
            event = AcpRuntimeEvent.ToolCall(
                text = "Calling read",
                tag = "tool_call",
                title = "read",
                status = "in_progress",
            ),
        )
        applyToolCallEvent(
            toolCalls = toolCalls,
            event = AcpRuntimeEvent.ToolCall(
                text = "Completed read",
                tag = "tool_call_update",
                title = "read",
                status = "completed",
            ),
        )

        assertEquals(1, toolCalls.size)
        assertEquals("completed", toolCalls.values.first().status)
    }
}
