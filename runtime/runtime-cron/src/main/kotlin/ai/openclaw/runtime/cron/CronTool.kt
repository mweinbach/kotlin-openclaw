package ai.openclaw.runtime.cron

import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.ToolContext
import kotlinx.serialization.json.*

/**
 * Agent tool for managing cron jobs. Agents can create, list, delete, and run cron jobs.
 * Ported from the cron management capabilities in src/cron/service.ts.
 */
class CronTool(
    private val scheduler: CronScheduler,
) : AgentTool {
    override val name = "cron"
    override val description = "Manage scheduled tasks (cron jobs). Create, list, delete, enable/disable, or force-run jobs."
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["list", "create", "delete", "enable", "disable", "run"],
                    "description": "The action to perform"
                },
                "id": {
                    "type": "string",
                    "description": "Job ID (for delete/enable/disable/run)"
                },
                "name": {
                    "type": "string",
                    "description": "Job name (for create)"
                },
                "schedule_type": {
                    "type": "string",
                    "enum": ["cron", "every", "at"],
                    "description": "Schedule type (for create)"
                },
                "schedule_expr": {
                    "type": "string",
                    "description": "Cron expression or time value (for create)"
                },
                "schedule_every_ms": {
                    "type": "number",
                    "description": "Interval in ms (for every schedule type)"
                },
                "message": {
                    "type": "string",
                    "description": "Message to send when job fires (for create)"
                },
                "agent_id": {
                    "type": "string",
                    "description": "Target agent ID (for create)"
                },
                "timezone": {
                    "type": "string",
                    "description": "Timezone for cron expressions (for create)"
                },
                "delete_after_run": {
                    "type": "boolean",
                    "description": "Delete job after first execution (for create)"
                }
            },
            "required": ["action"]
        }
    """.trimIndent()

    override suspend fun execute(input: String, context: ToolContext): String {
        val params = Json.parseToJsonElement(input).jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull
            ?: return "Error: 'action' is required"

        return when (action) {
            "list" -> handleList()
            "create" -> handleCreate(params, context)
            "delete" -> handleDelete(params)
            "enable" -> handleSetEnabled(params, true)
            "disable" -> handleSetEnabled(params, false)
            "run" -> handleRun(params)
            else -> "Error: Unknown action '$action'. Valid actions: list, create, delete, enable, disable, run"
        }
    }

    private suspend fun handleList(): String {
        val jobs = scheduler.list(includeDisabled = true)
        if (jobs.isEmpty()) return "No cron jobs configured."
        return buildString {
            appendLine("Cron jobs (${jobs.size}):")
            for (job in jobs) {
                val status = if (job.enabled) "enabled" else "disabled"
                val scheduleDesc = when (val s = job.schedule) {
                    is CronSchedule.Cron -> "cron: ${s.expr}"
                    is CronSchedule.Every -> "every ${s.everyMs}ms"
                    is CronSchedule.At -> "at: ${s.at}"
                }
                appendLine("- [${job.id.take(8)}] ${job.name} ($status) - $scheduleDesc")
                job.state.lastRunStatus?.let { appendLine("  Last run: $it") }
                job.state.nextRunAtMs?.let { appendLine("  Next run: ${java.time.Instant.ofEpochMilli(it)}") }
            }
        }
    }

    private suspend fun handleCreate(params: JsonObject, context: ToolContext): String {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return "Error: 'name' is required for create"
        val message = params["message"]?.jsonPrimitive?.contentOrNull
            ?: return "Error: 'message' is required for create"
        val scheduleType = params["schedule_type"]?.jsonPrimitive?.contentOrNull ?: "cron"
        val agentId = params["agent_id"]?.jsonPrimitive?.contentOrNull ?: context.agentId
        val deleteAfterRun = params["delete_after_run"]?.jsonPrimitive?.booleanOrNull ?: false

        val schedule = when (scheduleType) {
            "cron" -> {
                val expr = params["schedule_expr"]?.jsonPrimitive?.contentOrNull
                    ?: return "Error: 'schedule_expr' is required for cron schedule"
                val tz = params["timezone"]?.jsonPrimitive?.contentOrNull
                CronSchedule.Cron(expr = expr, tz = tz)
            }
            "every" -> {
                val everyMs = params["schedule_every_ms"]?.jsonPrimitive?.longOrNull
                    ?: return "Error: 'schedule_every_ms' is required for every schedule"
                CronSchedule.Every(everyMs = everyMs)
            }
            "at" -> {
                val at = params["schedule_expr"]?.jsonPrimitive?.contentOrNull
                    ?: return "Error: 'schedule_expr' is required for at schedule"
                CronSchedule.At(at = at)
            }
            else -> return "Error: Unknown schedule_type '$scheduleType'"
        }

        val job = scheduler.add(
            name = name,
            schedule = schedule,
            payload = CronPayload.AgentTurn(message = message),
            agentId = agentId,
            sessionKey = context.sessionKey,
            deleteAfterRun = deleteAfterRun,
        )

        return "Created cron job '${job.name}' (id: ${job.id.take(8)}). Next run: ${
            job.state.nextRunAtMs?.let { java.time.Instant.ofEpochMilli(it) } ?: "none"
        }"
    }

    private suspend fun handleDelete(params: JsonObject): String {
        val id = params["id"]?.jsonPrimitive?.contentOrNull
            ?: return "Error: 'id' is required for delete"
        val fullId = resolveJobId(id) ?: return "Error: No job found matching '$id'"
        return if (scheduler.remove(fullId)) "Deleted job $id" else "Error: Job '$id' not found"
    }

    private suspend fun handleSetEnabled(params: JsonObject, enabled: Boolean): String {
        val id = params["id"]?.jsonPrimitive?.contentOrNull
            ?: return "Error: 'id' is required"
        val fullId = resolveJobId(id) ?: return "Error: No job found matching '$id'"
        val action = if (enabled) "Enabled" else "Disabled"
        val job = scheduler.setEnabled(fullId, enabled)
        return if (job != null) "$action job '${job.name}'" else "Error: Job '$id' not found"
    }

    private suspend fun handleRun(params: JsonObject): String {
        val id = params["id"]?.jsonPrimitive?.contentOrNull
            ?: return "Error: 'id' is required for run"
        val fullId = resolveJobId(id) ?: return "Error: No job found matching '$id'"
        scheduler.runNow(fullId)
        return "Triggered job '$id'"
    }

    /**
     * Resolve a potentially-shortened job ID to the full UUID.
     */
    private suspend fun resolveJobId(idPrefix: String): String? {
        val allJobs = scheduler.list(includeDisabled = true)
        return allJobs.firstOrNull { it.id == idPrefix || it.id.startsWith(idPrefix) }?.id
    }
}
