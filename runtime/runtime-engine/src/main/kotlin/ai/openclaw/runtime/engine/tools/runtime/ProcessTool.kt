package ai.openclaw.runtime.engine.tools.runtime

import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.ToolContext
import ai.openclaw.runtime.engine.tools.ToolParamAliases
import java.util.concurrent.TimeUnit

class ProcessTool(
    private val processRegistry: ProcessRegistry,
    private val defaultPollTimeoutMs: Long = 10_000L,
    private val defaultTailChars: Int = 30_000,
) : AgentTool {
    override val name: String = "process"
    override val description: String = "Inspect and manage background exec sessions"
    override val parametersSchema: String = """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["list", "poll", "log", "kill", "remove", "write", "send_keys", "paste", "clear"]
            },
            "sessionId": {"type": "string"},
            "id": {"type": "string"},
            "timeout": {"type": "integer", "description": "Poll timeout in ms"},
            "maxChars": {"type": "integer", "description": "Max output chars"},
            "text": {"type": "string", "description": "Text to write to process stdin"}
          },
          "required": ["action"],
          "additionalProperties": true
        }
    """.trimIndent()

    override suspend fun execute(input: String, context: ToolContext): String {
        val params = ToolParamAliases.parseObject(input)
            ?: return ToolParamAliases.jsonError(name, "Input must be a JSON object")
        val action = ToolParamAliases.getString(params, "action")
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: return ToolParamAliases.jsonError(name, "'action' is required")

        return when (action) {
            "list" -> handleList()
            "poll" -> handlePoll(params)
            "log" -> handleLog(params)
            "kill" -> handleKill(params)
            "remove" -> handleRemove(params)
            "clear" -> handleClear(params)
            "write", "send_keys", "paste" -> handleWrite(params)
            else -> ToolParamAliases.jsonError(name, "Unknown action '$action'")
        }
    }

    private fun handleList(): String {
        val sessions = processRegistry.list()
        return ToolParamAliases.jsonOk(
            name,
            mapOf(
                "sessions" to sessions.map { session ->
                    mapOf(
                        "id" to session.id,
                        "command" to session.command,
                        "workingDir" to session.workingDir,
                        "running" to session.isRunning(),
                        "exitCode" to session.exitCode,
                        "startedAtMs" to session.startedAtMs,
                        "finishedAtMs" to session.finishedAtMs,
                    )
                },
            ),
        )
    }

    private fun handlePoll(params: kotlinx.serialization.json.JsonObject): String {
        val session = resolveSession(params) ?: return ToolParamAliases.jsonError(name, "Session not found")
        val timeoutMs = ToolParamAliases.getLong(params, "timeout")
            ?.coerceIn(0, 120_000)
            ?: defaultPollTimeoutMs

        if (session.isRunning() && timeoutMs > 0) {
            session.process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        }

        val maxChars = ToolParamAliases.getInt(params, "maxChars") ?: defaultTailChars
        return ToolParamAliases.jsonOk(
            name,
            mapOf(
                "id" to session.id,
                "running" to session.isRunning(),
                "exitCode" to session.exitCode,
                "output" to processRegistry.tail(session, maxChars),
            ),
        )
    }

    private fun handleLog(params: kotlinx.serialization.json.JsonObject): String {
        val session = resolveSession(params) ?: return ToolParamAliases.jsonError(name, "Session not found")
        val maxChars = ToolParamAliases.getInt(params, "maxChars") ?: defaultTailChars
        return ToolParamAliases.jsonOk(
            name,
            mapOf(
                "id" to session.id,
                "output" to processRegistry.tail(session, maxChars),
            ),
        )
    }

    private fun handleKill(params: kotlinx.serialization.json.JsonObject): String {
        val id = resolveSessionId(params) ?: return ToolParamAliases.jsonError(name, "'sessionId' is required")
        val killed = processRegistry.kill(id)
        return if (killed) {
            ToolParamAliases.jsonOk(name, mapOf("id" to id, "killed" to true))
        } else {
            ToolParamAliases.jsonError(name, "Session not found")
        }
    }

    private fun handleRemove(params: kotlinx.serialization.json.JsonObject): String {
        val id = resolveSessionId(params) ?: return ToolParamAliases.jsonError(name, "'sessionId' is required")
        val removed = processRegistry.remove(id)
        return if (removed) {
            ToolParamAliases.jsonOk(name, mapOf("id" to id, "removed" to true))
        } else {
            ToolParamAliases.jsonError(name, "Session not found")
        }
    }

    private fun handleClear(params: kotlinx.serialization.json.JsonObject): String {
        val id = resolveSessionId(params) ?: return ToolParamAliases.jsonError(name, "'sessionId' is required")
        val cleared = processRegistry.clearOutput(id)
        return if (cleared) {
            ToolParamAliases.jsonOk(name, mapOf("id" to id, "cleared" to true))
        } else {
            ToolParamAliases.jsonError(name, "Session not found")
        }
    }

    private fun handleWrite(params: kotlinx.serialization.json.JsonObject): String {
        val id = resolveSessionId(params) ?: return ToolParamAliases.jsonError(name, "'sessionId' is required")
        val text = ToolParamAliases.getString(params, "text")
            ?: return ToolParamAliases.jsonError(name, "'text' is required")
        val written = processRegistry.appendInput(id, text)
        return if (written) {
            ToolParamAliases.jsonOk(name, mapOf("id" to id, "written" to text.length))
        } else {
            ToolParamAliases.jsonError(name, "Session not found or stdin unavailable")
        }
    }

    private fun resolveSession(params: kotlinx.serialization.json.JsonObject): ProcessRegistry.ProcessSession? {
        val id = resolveSessionId(params) ?: return null
        return processRegistry.get(id)
    }

    private fun resolveSessionId(params: kotlinx.serialization.json.JsonObject): String? {
        return ToolParamAliases.getString(params, "sessionId", "id")
    }
}
