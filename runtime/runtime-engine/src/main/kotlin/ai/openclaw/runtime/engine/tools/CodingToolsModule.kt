package ai.openclaw.runtime.engine.tools

import ai.openclaw.runtime.engine.ToolRegistry
import ai.openclaw.runtime.engine.tools.fs.ApplyPatchTool
import ai.openclaw.runtime.engine.tools.fs.EditTool
import ai.openclaw.runtime.engine.tools.fs.ReadTool
import ai.openclaw.runtime.engine.tools.fs.WriteTool
import ai.openclaw.runtime.engine.tools.runtime.ExecTool
import ai.openclaw.runtime.engine.tools.runtime.ProcessRegistry
import ai.openclaw.runtime.engine.tools.runtime.ProcessTool

/**
 * Registers coding-oriented tools (read/write/edit/exec/process/apply_patch).
 */
object CodingToolsModule {

    data class Config(
        val workspaceDir: String,
        val workspaceOnly: Boolean = true,
        val applyPatchEnabled: Boolean = false,
        val applyPatchWorkspaceOnly: Boolean = true,
        val execTimeoutSec: Int = 120,
        val execYieldMs: Long = 10_000L,
        val processCleanupMs: Long = 5 * 60_000L,
    )

    fun registerAll(
        registry: ToolRegistry,
        config: Config,
    ) {
        val processRegistry = ProcessRegistry(cleanupMs = config.processCleanupMs)

        registry.register(ReadTool(config.workspaceDir, workspaceOnly = config.workspaceOnly))
        registry.register(WriteTool(config.workspaceDir, workspaceOnly = config.workspaceOnly))
        registry.register(EditTool(config.workspaceDir, workspaceOnly = config.workspaceOnly))
        registry.register(
            ExecTool(
                processRegistry = processRegistry,
                workspaceDir = config.workspaceDir,
                defaultTimeoutSec = config.execTimeoutSec,
                defaultYieldMs = config.execYieldMs,
            ),
        )
        registry.register(ProcessTool(processRegistry = processRegistry))

        if (config.applyPatchEnabled) {
            registry.register(
                ApplyPatchTool(
                    workspaceDir = config.workspaceDir,
                    workspaceOnly = config.applyPatchWorkspaceOnly,
                ),
            )
        }
    }
}
