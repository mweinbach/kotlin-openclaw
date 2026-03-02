package ai.openclaw.runtime.engine

import ai.openclaw.core.model.*
import ai.openclaw.core.skills.*

/**
 * A loaded skill definition ready for execution.
 */
data class SkillDefinition(
    val name: String,
    val commandName: String,
    val description: String,
    val body: String,
    val source: SkillSource,
    val metadata: OpenClawSkillMetadata? = null,
)

/**
 * Loads and resolves skill definitions from various sources.
 * Ported from src/agents/skills/workspace.ts.
 */
class SkillResolver(
    private val bundledSkills: List<SkillDefinition> = emptyList(),
    private val config: SkillsConfig? = null,
) {
    /**
     * Resolve available skills for an agent, applying filter and eligibility checks.
     */
    fun resolveSkills(
        agentSkillFilter: List<String>? = null,
        platform: String = "android",
    ): List<SkillDefinition> {
        val allSkills = mutableListOf<SkillDefinition>()

        // Add bundled skills (filtered by allowBundled config)
        val allowBundled = config?.allowBundled
        for (skill in bundledSkills) {
            if (allowBundled != null && skill.name !in allowBundled) continue
            if (!isSkillEligible(skill.metadata, platform)) continue
            allSkills.add(skill)
        }

        // Apply agent-level filter
        if (agentSkillFilter != null) {
            if (agentSkillFilter.isEmpty()) return emptyList()
            return allSkills.filter { it.name in agentSkillFilter || it.commandName in agentSkillFilter }
        }

        // Apply limits
        val maxInPrompt = config?.limits?.maxSkillsInPrompt ?: DEFAULT_MAX_SKILLS_IN_PROMPT
        return allSkills.take(maxInPrompt)
    }

    /**
     * Build SkillSummary list for SystemPromptBuilder.
     */
    fun buildSkillSummaries(skills: List<SkillDefinition>): List<SystemPromptBuilder.SkillSummary> {
        return skills.map { skill ->
            SystemPromptBuilder.SkillSummary(
                name = skill.commandName,
                description = skill.description,
                prompt = skill.body,
            )
        }
    }

    companion object {
        private const val DEFAULT_MAX_SKILLS_IN_PROMPT = 150
    }
}

/**
 * Executes a skill by injecting its prompt into the agent context.
 * The skill body becomes an instruction that the agent follows.
 */
class SkillExecutor {
    /**
     * Execute a skill: returns the skill body as an instruction prompt to be injected
     * into the agent conversation as a user message or system directive.
     */
    fun executeSkill(
        skill: SkillDefinition,
        args: String = "",
    ): String {
        val prompt = buildString {
            appendLine("Executing skill: /${skill.commandName}")
            if (skill.description.isNotBlank()) {
                appendLine("Description: ${skill.description}")
            }
            appendLine()
            appendLine("--- Skill Instructions ---")
            appendLine(skill.body)
            if (args.isNotBlank()) {
                appendLine()
                appendLine("--- User Arguments ---")
                appendLine(args)
            }
        }
        return prompt
    }

    /**
     * Find a skill by command invocation (e.g., "/github" or "/summarize").
     */
    fun findSkillByCommand(
        skills: List<SkillDefinition>,
        command: String,
    ): SkillDefinition? {
        val normalized = command.removePrefix("/").lowercase().trim()
        return skills.firstOrNull {
            it.commandName.lowercase() == normalized || it.name.lowercase() == normalized
        }
    }
}

/**
 * Load skill definitions from raw SKILL.md content.
 */
fun loadSkillFromContent(
    name: String,
    content: String,
    source: SkillSource,
    path: String = "",
): SkillDefinition? {
    if (content.isBlank()) return null
    val (metadata, body) = parseSkillFrontmatter(content)
    val commandName = deriveCommandName(name)
    val description = body.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: name
    return SkillDefinition(
        name = name,
        commandName = commandName,
        description = description,
        body = body,
        source = source,
        metadata = metadata,
    )
}
