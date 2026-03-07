package ai.openclaw.android

import ai.openclaw.core.model.EnvConfig
import ai.openclaw.core.model.ExecToolConfig
import ai.openclaw.core.model.ExpandedToolsConfig
import ai.openclaw.core.model.OpenClawConfig
import ai.openclaw.core.model.ShellEnvConfig
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecEnvironmentResolverTest {

    @Test
    fun `resolve merges shell env config vars and explicit node runtime`() = runTest {
        val workspaceDir = createTempDir(prefix = "openclaw-env")
        val nodeDir = workspaceDir.resolve("node-bin").apply { mkdirs() }
        val toolsDir = workspaceDir.resolve("custom-tools").apply { mkdirs() }
        val nodeBinary = nodeDir.resolve("node")
        nodeBinary.writeText(
            """
            #!/bin/sh
            echo v18.77.0
            """.trimIndent(),
        )
        nodeBinary.setExecutable(true)

        try {
            val resolver = ExecEnvironmentResolver(
                inheritedEnvProvider = {
                    mapOf(
                        "PATH" to "/usr/bin",
                        "HOME" to workspaceDir.absolutePath,
                    )
                },
                homeDirProvider = { workspaceDir.absolutePath },
                shellEnvCapture = { _, _, _ ->
                    mapOf(
                        "FROM_SHELL" to "1",
                        "PATH" to "/shell/bin",
                    )
                },
            )

            val resolved = resolver.resolve(
                config = OpenClawConfig(
                    env = EnvConfig(
                        shellEnv = ShellEnvConfig(enabled = true, timeoutMs = 1_000),
                        vars = mapOf("FOO" to "bar"),
                    ),
                    tools = ExpandedToolsConfig(
                        exec = ExecToolConfig(
                            node = nodeBinary.absolutePath,
                            pathPrepend = listOf(toolsDir.absolutePath),
                        ),
                    ),
                ),
                workspaceDir = workspaceDir.absolutePath,
                managedEnvironmentOverrides = mapOf(
                    "PREFIX" to "/managed/usr",
                    "HOME" to "/managed/home",
                ),
            )

            val pathEntries = resolved.environment.getValue("PATH").split(File.pathSeparator)
            assertEquals(nodeDir.absolutePath, pathEntries[0])
            assertEquals(toolsDir.absolutePath, pathEntries[1])
            assertTrue(pathEntries.contains("/shell/bin"))
            assertEquals("1", resolved.environment["FROM_SHELL"])
            assertEquals("bar", resolved.environment["FOO"])
            assertEquals("/managed/usr", resolved.environment["PREFIX"])
            assertEquals("/managed/home", resolved.environment["HOME"])
            assertEquals(nodeBinary.absolutePath, resolved.nodePath)
            assertEquals("v18.77.0", resolved.nodeVersion)
            assertEquals("v18.77.0", resolved.environment["NODE_VERSION"])
        } finally {
            workspaceDir.deleteRecursively()
        }
    }
}
