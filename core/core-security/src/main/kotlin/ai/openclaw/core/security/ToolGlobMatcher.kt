package ai.openclaw.core.security

object ToolGlobMatcher {
    fun matches(pattern: String, value: String): Boolean {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == "*") return true
        val regex = buildString {
            append('^')
            for (ch in trimmed) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> {
                        append('\\')
                        append(ch)
                    }
                    else -> append(ch)
                }
            }
            append('$')
        }
        return Regex(regex, RegexOption.IGNORE_CASE).matches(value)
    }
}
