package ai.openclaw.runtime.engine.tools

import ai.openclaw.runtime.engine.ToolRegistry
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodingToolsModuleTest {

    @Test
    fun `default coding tools register without apply_patch`() {
        val registry = ToolRegistry()
        CodingToolsModule.registerAll(
            registry,
            CodingToolsModule.Config(
                workspaceDir = System.getProperty("java.io.tmpdir") ?: "/tmp",
                applyPatchEnabled = false,
            ),
        )

        val names = registry.names()
        assertTrue("read" in names)
        assertTrue("write" in names)
        assertTrue("edit" in names)
        assertTrue("exec" in names)
        assertTrue("process" in names)
        assertFalse("apply_patch" in names)
    }

    @Test
    fun `apply_patch registers when enabled`() {
        val registry = ToolRegistry()
        CodingToolsModule.registerAll(
            registry,
            CodingToolsModule.Config(
                workspaceDir = System.getProperty("java.io.tmpdir") ?: "/tmp",
                applyPatchEnabled = true,
            ),
        )

        assertTrue("apply_patch" in registry.names())
    }
}
