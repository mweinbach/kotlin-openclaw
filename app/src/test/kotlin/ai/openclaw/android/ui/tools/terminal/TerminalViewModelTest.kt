package ai.openclaw.android.ui.tools.terminal

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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val vms = mutableListOf<TerminalViewModel>()

    private fun createVm(): TerminalViewModel {
        val vm = TerminalViewModel()
        vms.add(vm)
        return vm
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        // Kill all shell processes before resetting Main to prevent leaked coroutines
        vms.forEach { it.kill() }
        vms.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `output starts empty`() {
        val vm = createVm()
        assertTrue(vm.output.isEmpty())
    }

    @Test
    fun `commandHistory starts empty`() {
        val vm = createVm()
        assertTrue(vm.commandHistory.isEmpty())
    }

    @Test
    fun `executeCommand adds command to history`() = runTest(testDispatcher) {
        val vm = createVm()
        vm.executeCommand("echo test")
        advanceUntilIdle()

        assertTrue(vm.commandHistory.contains("echo test"),
            "History should contain the command")
    }

    @Test
    fun `executeCommand adds prompt to output`() = runTest(testDispatcher) {
        val vm = createVm()
        vm.executeCommand("echo test")
        advanceUntilIdle()

        assertTrue(vm.output.any { it.contains("$ echo test") },
            "Output should show the command with prompt: ${vm.output}")
    }

    @Test
    fun `executeCommand captures output`() = runTest(testDispatcher) {
        val vm = createVm()
        vm.executeCommand("echo hello")
        advanceUntilIdle()

        assertTrue(vm.output.any { it.contains("hello") },
            "Output should contain command result: ${vm.output}")
    }

    @Test
    fun `clear empties output`() = runTest(testDispatcher) {
        val vm = createVm()
        vm.executeCommand("echo test")
        advanceUntilIdle()

        vm.clear()
        assertTrue(vm.output.isEmpty(), "Output should be empty after clear")
    }

    @Test
    fun `clear does not clear history`() = runTest(testDispatcher) {
        val vm = createVm()
        vm.executeCommand("echo test")
        advanceUntilIdle()

        vm.clear()
        assertTrue(vm.commandHistory.isNotEmpty(), "History should persist after clear")
    }

    @Test
    fun `previousCommand returns null when history empty`() {
        val vm = createVm()
        assertNull(vm.previousCommand())
    }

    @Test
    fun `previousCommand returns most recent command`() = runTest(testDispatcher) {
        val vm = createVm()
        vm.executeCommand("cmd1")
        vm.executeCommand("cmd2")
        advanceUntilIdle()

        val prev = vm.previousCommand()
        assertEquals("cmd2", prev, "Should return most recent command")
    }

    @Test
    fun `previousCommand navigates backwards through history`() = runTest(testDispatcher) {
        val vm = createVm()
        vm.executeCommand("cmd1")
        vm.executeCommand("cmd2")
        vm.executeCommand("cmd3")
        advanceUntilIdle()

        // History is [cmd3, cmd2, cmd1] (most recent first)
        assertEquals("cmd3", vm.previousCommand())
        assertEquals("cmd2", vm.previousCommand())
        assertEquals("cmd1", vm.previousCommand())
    }

    @Test
    fun `nextCommand returns empty string when at end of history`() = runTest(testDispatcher) {
        val vm = createVm()
        vm.executeCommand("cmd1")
        advanceUntilIdle()

        // nextCommand returns "" (empty string) when no more recent commands
        val result = vm.nextCommand()
        assertEquals("", result, "Next should return empty string when at end")
    }

    @Test
    fun `nextCommand navigates forward after previousCommand`() = runTest(testDispatcher) {
        val vm = createVm()
        vm.executeCommand("cmd1")
        vm.executeCommand("cmd2")
        advanceUntilIdle()

        // Go back through history
        vm.previousCommand() // cmd2 (index 0)
        vm.previousCommand() // cmd1 (index 1)
        // Go forward
        assertEquals("cmd2", vm.nextCommand())
    }

    @Test
    fun `multiple commands build up history in reverse order`() = runTest(testDispatcher) {
        val vm = createVm()
        vm.executeCommand("ls")
        vm.executeCommand("pwd")
        vm.executeCommand("date")
        advanceUntilIdle()

        // History is prepended: [date, pwd, ls]
        assertEquals(3, vm.commandHistory.size)
        assertEquals("date", vm.commandHistory[0])
        assertEquals("pwd", vm.commandHistory[1])
        assertEquals("ls", vm.commandHistory[2])
    }
}
