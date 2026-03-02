package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.ToolContext
import kotlinx.serialization.json.*

/**
 * Agent tool for invoking skills. Agents can list available skills
 * and execute them by name.
 */
class SkillsTool(
    private val skillExecutor: SkillExecutor,
    private val availableSkills: () -> List<SkillDefinition>,
) : AgentTool {
    override val name = "skills"
    override val description = "List or invoke available skills (slash commands)."
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["list", "invoke"],
                    "description": "Action to perform"
                },
                "command": {
                    "type": "string",
                    "description": "Skill command name to invoke (e.g. 'github', 'summarize')"
                },
                "args": {
                    "type": "string",
                    "description": "Arguments to pass to the skill"
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
            "invoke" -> handleInvoke(params)
            else -> "Error: Unknown action '$action'. Valid: list, invoke"
        }
    }

    private fun handleList(): String {
        val skills = availableSkills()
        if (skills.isEmpty()) return "No skills available."
        return buildString {
            appendLine("Available skills (${skills.size}):")
            for (skill in skills) {
                appendLine("- /${skill.commandName}: ${skill.description}")
            }
        }
    }

    private fun handleInvoke(params: JsonObject): String {
        val command = params["command"]?.jsonPrimitive?.contentOrNull
            ?: return "Error: 'command' is required for invoke"
        val args = params["args"]?.jsonPrimitive?.contentOrNull ?: ""
        val skills = availableSkills()
        val skill = skillExecutor.findSkillByCommand(skills, command)
            ?: return "Error: No skill found for command '/$command'. Use action 'list' to see available skills."
        return skillExecutor.executeSkill(skill, args)
    }
}
