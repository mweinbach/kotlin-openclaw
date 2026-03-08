package ai.openclaw.runtime.engine.tools.runtime

import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.ToolContext
import ai.openclaw.runtime.engine.tools.ToolParamAliases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit

class ExecTool(
    private val processRegistry: ProcessRegistry,
    private val workspaceDir: String,
    private val defaultTimeoutSec: Int = 120,
    private val defaultYieldMs: Long = 10_000L,
    private val maxOutputChars: Int = 30_000,
    private val shellPath: String? = null,
    private val baseEnv: Map<String, String> = emptyMap(),
) : AgentTool {
    override val name: String = "exec"
    override val description: String = "Run shell commands (supports background sessions)"
    override val parametersSchema: String = """
        {
          "type": "object",
          "properties": {
            "command": {"type": "string", "description": "Shell command to execute"},
            "workdir": {"type": "string", "description": "Working directory"},
            "env": {"type": "object", "additionalProperties": {"type": "string"}},
            "yieldMs": {"type": "integer", "description": "Wait before returning background status"},
            "background": {"type": "boolean", "description": "Run in background immediately"},
            "timeout": {"type": "integer", "description": "Timeout in seconds"},
            "ask": {"type": "string", "enum": ["off", "on-miss", "always"]}
          },
          "required": ["command"],
          "additionalProperties": true
        }
    """.trimIndent()

    override suspend fun execute(input: String, context: ToolContext): String = withContext(Dispatchers.IO) {
        val params = ToolParamAliases.parseObject(input)
            ?: return@withContext ToolParamAliases.jsonError(name, "Input must be a JSON object")
        val command = ToolParamAliases.getString(params, "command")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return@withContext ToolParamAliases.jsonError(name, "'command' is required")
        val workdir = ToolParamAliases.getString(params, "workdir") ?: workspaceDir
        val timeoutSec = ToolParamAliases.getInt(params, "timeout")
            ?.coerceIn(1, 3600)
            ?: defaultTimeoutSec
        val yieldMs = ToolParamAliases.getLong(params, "yieldMs")
            ?.coerceIn(0, 120_000)
            ?: defaultYieldMs
        val background = ToolParamAliases.getBoolean(params, "background") ?: false
        val env = resolveEnv(params)

        runCatching {
            val process = buildProcess(command, workdir, env)
            val session = processRegistry.create(command = command, workingDir = workdir, process = process)

            if (background) {
                return@runCatching backgroundResponse(session, pending = true)
            }

            val waitMs = if (yieldMs > 0) yieldMs else timeoutSec * 1_000L
            val completedInWindow = process.waitFor(waitMs, TimeUnit.MILLISECONDS)

            if (!completedInWindow) {
                val remainingMs = ((timeoutSec * 1_000L) - waitMs).coerceAtLeast(0L)
                val completedLater = if (remainingMs > 0) {
                    process.waitFor(remainingMs, TimeUnit.MILLISECONDS)
                } else {
                    false
                }
                if (!completedLater) {
                    process.destroyForcibly()
                    return@runCatching ToolParamAliases.jsonError(name, "Command timed out after ${timeoutSec}s")
                }
            }

            if (process.isAlive) {
                return@runCatching backgroundResponse(session, pending = true)
            }

            val output = processRegistry.tail(session, maxOutputChars)
            val exitCode = session.exitCode ?: process.exitValue()
            val status = if (exitCode == 0) "completed" else "failed"
            ToolParamAliases.jsonOk(
                name,
                mapOf(
                    "execStatus" to status,
                    "sessionId" to session.id,
                    "exitCode" to exitCode,
                    "output" to output,
                    "running" to false,
                ),
            )
        }.getOrElse { err ->
            ToolParamAliases.jsonError(name, err.message ?: "Failed to execute command")
        }
    }

    private fun backgroundResponse(session: ProcessRegistry.ProcessSession, pending: Boolean): String {
        return ToolParamAliases.jsonOk(
            name,
            mapOf(
                "execStatus" to if (pending) "background" else "completed",
                "sessionId" to session.id,
                "running" to session.isRunning(),
                "output" to processRegistry.tail(session, maxOutputChars),
            ),
        )
    }

    private fun buildProcess(
        command: String,
        workdir: String,
        env: Map<String, String>,
    ): Process {
        val resolvedShellPath = shellPath
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { File(it).exists() }
            ?: if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
        val mergedEnv = LinkedHashMap(System.getenv())
        mergedEnv.putAll(baseEnv)
        mergedEnv.putAll(env)
        val wrappedCommand = ShellCommandBootstrap.apply(command, mergedEnv)
        val pb = ProcessBuilder(resolvedShellPath, "-c", wrappedCommand)
        pb.redirectErrorStream(true)
        pb.directory(File(workdir))
        val processEnv = pb.environment()
        processEnv.clear()
        processEnv.putAll(mergedEnv)
        return pb.start()
    }

    private fun resolveEnv(params: JsonObject): Map<String, String> {
        val envObj = ToolParamAliases.getJsonObject(params, "env") ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        for ((key, value) in envObj) {
            val str = value.toString().trim().trim('"')
            if (str.isNotEmpty()) {
                out[key] = str
            }
        }
        return out
    }
}
