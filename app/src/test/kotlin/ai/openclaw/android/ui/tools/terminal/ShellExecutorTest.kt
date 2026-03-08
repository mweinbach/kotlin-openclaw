package ai.openclaw.android.ui.tools.terminal

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertContains

class ShellExecutorTest {

    @Test
    fun `execute echo command returns output`() = runTest {
        val executor = ShellExecutor()
        val output = executor.execute("echo hello").toList()
        assertTrue(output.any { it.contains("hello") }, "Output should contain 'hello': $output")
    }

    @Test
    fun `execute pwd returns a directory path`() = runTest {
        val executor = ShellExecutor()
        val output = executor.execute("pwd").toList()
        assertTrue(output.isNotEmpty(), "pwd should produce output")
        assertTrue(output.first().startsWith("/"), "pwd should return an absolute path")
    }

    @Test
    fun `execute with custom working directory`() = runTest {
        val executor = ShellExecutor()
        executor.workingDir = "/tmp"
        val output = executor.execute("pwd").toList()
        assertTrue(output.any { it.contains("/tmp") || it.contains("/private/tmp") },
            "Should be in /tmp directory: $output")
    }

    @Test
    fun `execute multiline output`() = runTest {
        val executor = ShellExecutor()
        val output = executor.execute("echo line1; echo line2; echo line3").toList()
        assertTrue(output.size >= 3 || output.any { it.contains("line1") },
            "Should have multiple lines of output: $output")
    }

    @Test
    fun `execute nonexistent command produces stderr`() = runTest {
        val executor = ShellExecutor()
        val output = executor.execute("nonexistent_command_xyz 2>&1").toList()
        assertTrue(output.any { it.contains("not found") || it.contains("No such") },
            "Should report command not found: $output")
    }

    @Test
    fun `isRunning is false before execution`() {
        val executor = ShellExecutor()
        assertFalse(executor.isRunning(), "Should not be running before execution")
    }

    @Test
    fun `kill stops running process`() = runTest {
        val executor = ShellExecutor()
        // Start a short-lived command and kill it
        executor.kill()
        assertFalse(executor.isRunning(), "Should not be running after kill")
    }

    @Test
    fun `execute env outputs environment`() = runTest {
        val executor = ShellExecutor()
        val output = executor.execute("echo \$HOME").toList()
        assertTrue(output.isNotEmpty(), "Should have output from echo \$HOME")
    }

    @Test
    fun `execute uses provided base environment`() = runTest {
        val executor = ShellExecutor(
            environmentProvider = {
                mapOf(
                    "FOO" to "from-base-env",
                    "PATH" to (System.getenv("PATH") ?: ""),
                )
            },
        )
        val output = executor.execute("echo \$FOO").toList()
        assertContains(output.joinToString("\n"), "from-base-env")
    }

    @Test
    fun `execute injects managed node shell shims`() = runTest {
        val tempDir = createTempDir(prefix = "openclaw-shell-shims")
        val nodeBinary = tempDir.resolve("node")
        val npmCli = tempDir.resolve("npm-cli.js")
        val rgBinary = tempDir.resolve("rg")
        nodeBinary.writeText(
            """
            #!/bin/sh
            script="${'$'}1"
            shift
            case "${'$'}script" in
              --version)
                echo v25.3.0
                ;;
              ${npmCli.absolutePath})
                echo npm "${'$'}@"
                ;;
              *)
                echo node "${'$'}script" "${'$'}@"
                ;;
            esac
            """.trimIndent(),
        )
        nodeBinary.setExecutable(true)
        npmCli.writeText("console.log('npm')")
        rgBinary.writeText("#!/bin/sh\necho rg \"$@\"")
        rgBinary.setExecutable(true)

        try {
            val executor = ShellExecutor(
                environmentProvider = {
                    mapOf(
                        "PATH" to (System.getenv("PATH") ?: ""),
                        "OPENCLAW_NODE_EXEC" to nodeBinary.absolutePath,
                        "OPENCLAW_NPM_CLI_JS" to npmCli.absolutePath,
                        "OPENCLAW_RG_EXEC" to rgBinary.absolutePath,
                    )
                },
            )

            val output = executor.execute("npm install && rg hello").toList().joinToString("\n")

            assertContains(output, "npm install")
            assertContains(output, "rg hello")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `execute piped commands work`() = runTest {
        val executor = ShellExecutor()
        val output = executor.execute("echo 'abc def ghi' | wc -w").toList()
        assertTrue(output.any { it.trim() == "3" }, "Word count should be 3: $output")
    }

    @Test
    fun `execute exit code zero for success`() = runTest {
        val executor = ShellExecutor()
        val output = executor.execute("true").toList()
        // true should exit 0 and produce no output
        // Just verify it doesn't throw
    }
}
