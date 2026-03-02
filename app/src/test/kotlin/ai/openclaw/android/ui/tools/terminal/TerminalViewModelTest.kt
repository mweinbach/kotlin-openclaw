package ai.openclaw.android.ui.tools.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
    fun `executeCommand adds command to history`() {
        val vm = createVm()
        vm.executeCommand("echo test")

        assertTrue(vm.commandHistory.contains("echo test"),
            "History should contain the command")
    }

    @Test
    fun `executeCommand adds prompt to output`() {
        val vm = createVm()
        vm.executeCommand("echo test")

        assertTrue(vm.output.any { it.contains("$ echo test") },
            "Output should show the command with prompt: ${vm.output}")
    }

    @Test
    fun `clear empties output`() {
        val vm = createVm()
        vm.executeCommand("echo test")

        vm.clear()
        assertTrue(vm.output.isEmpty(), "Output should be empty after clear")
    }

    @Test
    fun `clear does not clear history`() {
        val vm = createVm()
        vm.executeCommand("echo test")

        vm.clear()
        assertTrue(vm.commandHistory.isNotEmpty(), "History should persist after clear")
    }

    @Test
    fun `previousCommand returns null when history empty`() {
        val vm = createVm()
        assertNull(vm.previousCommand())
    }

    @Test
    fun `previousCommand returns most recent command`() {
        val vm = createVm()
        vm.executeCommand("cmd1")
        vm.executeCommand("cmd2")

        val prev = vm.previousCommand()
        assertEquals("cmd2", prev, "Should return most recent command")
    }

    @Test
    fun `previousCommand navigates backwards through history`() {
        val vm = createVm()
        vm.executeCommand("cmd1")
        vm.executeCommand("cmd2")
        vm.executeCommand("cmd3")

        assertEquals("cmd3", vm.previousCommand())
        assertEquals("cmd2", vm.previousCommand())
        assertEquals("cmd1", vm.previousCommand())
    }

    @Test
    fun `nextCommand returns empty string when at end of history`() {
        val vm = createVm()
        vm.executeCommand("cmd1")

        val result = vm.nextCommand()
        assertEquals("", result, "Next should return empty string when at end")
    }

    @Test
    fun `nextCommand navigates forward after previousCommand`() {
        val vm = createVm()
        vm.executeCommand("cmd1")
        vm.executeCommand("cmd2")

        vm.previousCommand() // cmd2
        vm.previousCommand() // cmd1
        assertEquals("cmd2", vm.nextCommand())
    }

    @Test
    fun `multiple commands build up history in reverse order`() {
        val vm = createVm()
        vm.executeCommand("ls")
        vm.executeCommand("pwd")
        vm.executeCommand("date")

        assertEquals(3, vm.commandHistory.size)
        assertEquals("date", vm.commandHistory[0])
        assertEquals("pwd", vm.commandHistory[1])
        assertEquals("ls", vm.commandHistory[2])
    }
}
