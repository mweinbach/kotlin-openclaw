package ai.openclaw.runtime.engine

import ai.openclaw.core.model.SkillSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillExecutorTest {

    @Test
    fun `loadSkillFromContent parses basic skill`() {
        val content = """
            # GitHub Skill
            Interact with GitHub repositories, issues, and pull requests.

            Use the exec tool to run gh commands.
        """.trimIndent()

        val skill = loadSkillFromContent("github", content, SkillSource.BUNDLED)
        assertNotNull(skill)
        assertEquals("github", skill.name)
        assertEquals("github", skill.commandName)
        assertTrue(skill.body.contains("Interact with GitHub"))
    }

    @Test
    fun `loadSkillFromContent parses frontmatter`() {
        val content = """
            ---
            emoji: rocket
            primaryEnv: GITHUB_TOKEN
            ---
            # GitHub Skill
            Interact with GitHub.
        """.trimIndent()

        val skill = loadSkillFromContent("github", content, SkillSource.BUNDLED)
        assertNotNull(skill)
        assertNotNull(skill.metadata)
        assertEquals("rocket", skill.metadata?.emoji)
        assertEquals("GITHUB_TOKEN", skill.metadata?.primaryEnv)
        assertTrue(skill.body.contains("# GitHub Skill"))
    }

    @Test
    fun `loadSkillFromContent returns null for blank content`() {
        val skill = loadSkillFromContent("empty", "", SkillSource.BUNDLED)
        assertNull(skill)
    }

    @Test
    fun `SkillExecutor executeSkill produces instruction prompt`() {
        val executor = SkillExecutor()
        val skill = SkillDefinition(
            name = "summarize",
            commandName = "summarize",
            description = "Summarize content",
            body = "Read the input and produce a concise summary.",
            source = SkillSource.BUNDLED,
        )

        val prompt = executor.executeSkill(skill, "some text to summarize")
        assertTrue(prompt.contains("Executing skill: /summarize"))
        assertTrue(prompt.contains("Read the input and produce a concise summary."))
        assertTrue(prompt.contains("some text to summarize"))
    }

    @Test
    fun `SkillExecutor findSkillByCommand matches by name`() {
        val executor = SkillExecutor()
        val skills = listOf(
            SkillDefinition("github", "github", "GitHub", "body", SkillSource.BUNDLED),
            SkillDefinition("slack", "slack", "Slack", "body", SkillSource.BUNDLED),
        )

        val found = executor.findSkillByCommand(skills, "/github")
        assertNotNull(found)
        assertEquals("github", found.name)
    }

    @Test
    fun `SkillExecutor findSkillByCommand is case insensitive`() {
        val executor = SkillExecutor()
        val skills = listOf(
            SkillDefinition("GitHub", "github", "GitHub", "body", SkillSource.BUNDLED),
        )

        val found = executor.findSkillByCommand(skills, "GITHUB")
        assertNotNull(found)
    }

    @Test
    fun `SkillExecutor findSkillByCommand returns null for unknown`() {
        val executor = SkillExecutor()
        val found = executor.findSkillByCommand(emptyList(), "unknown")
        assertNull(found)
    }

    @Test
    fun `SkillResolver filters by agent skill list`() {
        val bundled = listOf(
            SkillDefinition("github", "github", "GitHub", "body", SkillSource.BUNDLED),
            SkillDefinition("slack", "slack", "Slack", "body", SkillSource.BUNDLED),
            SkillDefinition("weather", "weather", "Weather", "body", SkillSource.BUNDLED),
        )
        val resolver = SkillResolver(bundledSkills = bundled)

        val filtered = resolver.resolveSkills(agentSkillFilter = listOf("github", "weather"))
        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.name == "github" })
        assertTrue(filtered.any { it.name == "weather" })
    }

    @Test
    fun `SkillResolver empty filter returns nothing`() {
        val bundled = listOf(
            SkillDefinition("github", "github", "GitHub", "body", SkillSource.BUNDLED),
        )
        val resolver = SkillResolver(bundledSkills = bundled)

        val filtered = resolver.resolveSkills(agentSkillFilter = emptyList())
        assertEquals(0, filtered.size)
    }

    @Test
    fun `SkillResolver null filter returns all`() {
        val bundled = listOf(
            SkillDefinition("a", "a", "A", "body", SkillSource.BUNDLED),
            SkillDefinition("b", "b", "B", "body", SkillSource.BUNDLED),
        )
        val resolver = SkillResolver(bundledSkills = bundled)

        val filtered = resolver.resolveSkills(agentSkillFilter = null)
        assertEquals(2, filtered.size)
    }

    @Test
    fun `SkillResolver buildSkillSummaries produces correct format`() {
        val skills = listOf(
            SkillDefinition("github", "github", "GitHub integration", "Use gh CLI", SkillSource.BUNDLED),
        )
        val resolver = SkillResolver()
        val summaries = resolver.buildSkillSummaries(skills)
        assertEquals(1, summaries.size)
        assertEquals("github", summaries[0].name)
        assertEquals("GitHub integration", summaries[0].description)
        assertEquals("Use gh CLI", summaries[0].prompt)
    }
}
