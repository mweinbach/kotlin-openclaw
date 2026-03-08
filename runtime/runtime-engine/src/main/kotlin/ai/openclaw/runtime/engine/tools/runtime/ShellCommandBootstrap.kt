package ai.openclaw.runtime.engine.tools.runtime

object ShellCommandBootstrap {
    const val NODE_EXEC_ENV = "OPENCLAW_NODE_EXEC"
    const val RG_EXEC_ENV = "OPENCLAW_RG_EXEC"
    const val NPM_CLI_JS_ENV = "OPENCLAW_NPM_CLI_JS"
    const val NPX_CLI_JS_ENV = "OPENCLAW_NPX_CLI_JS"
    const val COREPACK_JS_ENV = "OPENCLAW_COREPACK_JS"

    fun availableCommandShims(environment: Map<String, String>): Set<String> {
        val nodeExec = environment[NODE_EXEC_ENV].orEmpty()
        return buildSet {
            if (nodeExec.isNotBlank()) {
                add("node")
            }
            if (nodeExec.isNotBlank() && environment[NPM_CLI_JS_ENV].orEmpty().isNotBlank()) {
                add("npm")
            }
            if (nodeExec.isNotBlank() && environment[NPX_CLI_JS_ENV].orEmpty().isNotBlank()) {
                add("npx")
            }
            if (nodeExec.isNotBlank() && environment[COREPACK_JS_ENV].orEmpty().isNotBlank()) {
                add("corepack")
            }
            if (environment[RG_EXEC_ENV].orEmpty().isNotBlank()) {
                add("rg")
            }
        }
    }

    fun apply(command: String, environment: Map<String, String>): String {
        val bootstrap = render(environment) ?: return command
        return buildString {
            append(bootstrap)
            append('\n')
            append(command)
        }
    }

    private fun render(environment: Map<String, String>): String? {
        val lines = buildList {
            environment[NODE_EXEC_ENV]
                ?.takeIf { it.isNotBlank() }
                ?.let { nodeExec ->
                    add("""node() { ${shellQuote(nodeExec)} "${'$'}@"; }""")
                    environment[NPM_CLI_JS_ENV]
                        ?.takeIf { it.isNotBlank() }
                        ?.let { npmCli ->
                            add("""npm() { ${shellQuote(nodeExec)} ${shellQuote(npmCli)} "${'$'}@"; }""")
                        }
                    environment[NPX_CLI_JS_ENV]
                        ?.takeIf { it.isNotBlank() }
                        ?.let { npxCli ->
                            add("""npx() { ${shellQuote(nodeExec)} ${shellQuote(npxCli)} "${'$'}@"; }""")
                        }
                    environment[COREPACK_JS_ENV]
                        ?.takeIf { it.isNotBlank() }
                        ?.let { corepackCli ->
                            add("""corepack() { ${shellQuote(nodeExec)} ${shellQuote(corepackCli)} "${'$'}@"; }""")
                        }
                }
            environment[RG_EXEC_ENV]
                ?.takeIf { it.isNotBlank() }
                ?.let { rgExec ->
                    add("""rg() { ${shellQuote(rgExec)} "${'$'}@"; }""")
                }
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
