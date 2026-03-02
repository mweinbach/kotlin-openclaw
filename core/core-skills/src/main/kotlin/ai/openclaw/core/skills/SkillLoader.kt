package ai.openclaw.core.skills

import ai.openclaw.core.model.*

/**
 * Parse SKILL.md frontmatter to extract metadata.
 */
fun parseSkillFrontmatter(content: String): Pair<OpenClawSkillMetadata?, String> {
    val lines = content.lines()
    if (lines.isEmpty() || lines[0].trim() != "---") {
        return null to content
    }

    val endIdx = lines.drop(1).indexOfFirst { it.trim() == "---" }
    if (endIdx < 0) return null to content

    val frontmatterLines = lines.subList(1, endIdx + 1)
    val body = lines.drop(endIdx + 2).joinToString("\n")

    // Simple YAML key-value parsing
    val metadata = parseFrontmatterToMetadata(frontmatterLines)
    return metadata to body
}

private fun parseFrontmatterToMetadata(lines: List<String>): OpenClawSkillMetadata {
    val map = mutableMapOf<String, String>()
    for (line in lines) {
        val colonIdx = line.indexOf(':')
        if (colonIdx > 0) {
            val key = line.substring(0, colonIdx).trim()
            val value = line.substring(colonIdx + 1).trim().trimStart('"').trimEnd('"')
            map[key] = value
        }
    }

    return OpenClawSkillMetadata(
        emoji = map["emoji"],
        os = map["os"]?.split(",")?.map { it.trim() },
        requiredBins = map["requiredBins"]?.split(",")?.map { it.trim() },
        requiredEnv = map["requiredEnv"]?.split(",")?.map { it.trim() },
        primaryEnv = map["primaryEnv"],
    )
}

/**
 * Derive a command name from a skill name.
 */
fun deriveCommandName(skillName: String): String {
    return skillName.lowercase()
        .replace(Regex("[^a-z0-9_-]"), "-")
        .replace(Regex("-+"), "-")
        .trim('-')
}

/**
 * Check skill eligibility for the current platform.
 */
fun isSkillEligible(
    metadata: OpenClawSkillMetadata?,
    platform: String = "android",
    availableBins: Set<String> = emptySet(),
    availableEnv: Set<String> = emptySet(),
): Boolean {
    if (metadata == null) return true

    // Check OS
    val os = metadata.os
    if (!os.isNullOrEmpty() && platform !in os) {
        return false
    }

    // Check required binaries
    val requiredBins = metadata.requiredBins
    if (!requiredBins.isNullOrEmpty()) {
        if (!availableBins.containsAll(requiredBins)) {
            return false
        }
    }

    // Check required env vars
    val requiredEnv = metadata.requiredEnv
    if (!requiredEnv.isNullOrEmpty()) {
        if (!availableEnv.containsAll(requiredEnv)) {
            return false
        }
    }

    return true
}

/**
 * Filter skills by agent config.
 */
fun filterSkillEntries(
    skills: List<SkillEntry>,
    filter: List<String>?,
): List<SkillEntry> {
    if (filter == null) return skills
    if (filter.isEmpty()) return emptyList()
    return skills.filter { it.name in filter || it.commandName in filter }
}

/**
 * Build the workspace skills prompt for model injection.
 */
fun buildWorkspaceSkillsPrompt(skills: List<SkillSnapshot>): String {
    if (skills.isEmpty()) return ""

    val sb = StringBuilder()
    sb.appendLine("# Available Skills")
    sb.appendLine()
    for (skill in skills) {
        sb.appendLine("## /${skill.commandName}")
        if (skill.description != null) {
            sb.appendLine(skill.description)
        }
        sb.appendLine()
    }
    return sb.toString()
}
