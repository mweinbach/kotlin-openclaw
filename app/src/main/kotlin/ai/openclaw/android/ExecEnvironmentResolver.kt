package ai.openclaw.android

import ai.openclaw.core.model.OpenClawConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class ResolvedExecEnvironment(
    val shellPath: String,
    val environment: Map<String, String>,
    val nodePath: String? = null,
    val nodeVersion: String? = null,
) {
    companion object {
        fun fromSystem(): ResolvedExecEnvironment {
            val inherited = LinkedHashMap(System.getenv())
            val shellPath = ExecEnvironmentResolver.detectShellPath(inherited)
            val nodePath = ExecEnvironmentResolver.resolveBinaryOnPath("node", inherited["PATH"])?.absolutePath
            return ResolvedExecEnvironment(
                shellPath = shellPath,
                environment = inherited,
                nodePath = nodePath,
                nodeVersion = inherited["NODE_VERSION"],
            )
        }
    }
}

class ExecEnvironmentResolver(
    private val inheritedEnvProvider: () -> Map<String, String> = { System.getenv() },
    private val homeDirProvider: () -> String? = {
        System.getenv("HOME")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home")?.takeIf { it.isNotBlank() }
    },
    private val shellEnvCapture: suspend (
        shellPath: String,
        timeoutMs: Int,
        inheritedEnv: Map<String, String>,
    ) -> Map<String, String> = { shellPath, timeoutMs, inheritedEnv ->
        captureShellEnvironment(shellPath, timeoutMs, inheritedEnv)
    },
) {
    suspend fun resolve(
        config: OpenClawConfig,
        workspaceDir: String,
        managedNodePath: String? = null,
        managedPathPrepend: List<String> = emptyList(),
    ): ResolvedExecEnvironment = withContext(Dispatchers.IO) {
        val inherited = LinkedHashMap(inheritedEnvProvider())
        val shellPath = detectShellPath(inherited)
        val shellEnvConfig = config.env?.shellEnv
        val shellEnv = if (shellEnvConfig?.enabled == true) {
            runCatching {
                shellEnvCapture(
                    shellPath,
                    shellEnvConfig.timeoutMs?.coerceIn(250, 30_000) ?: 3_000,
                    inherited,
                )
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        val merged = LinkedHashMap<String, String>()
        merged.putAll(inherited)
        merged.putAll(shellEnv)
        merged.putAll(config.env?.vars.orEmpty())

        val prependEntries = mutableListOf<String>()
        prependEntries += managedPathPrepend
        val explicitNode = config.tools?.exec?.node
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: managedNodePath
        val explicitNodeFile = explicitNode?.let { spec ->
            resolveBinarySpec(
                spec = spec,
                currentPath = merged["PATH"],
                workspaceDir = workspaceDir,
                homeDir = homeDirProvider(),
                environment = merged,
            )
        }

        explicitNodeFile?.parentFile?.absolutePath?.let(prependEntries::add)
        prependEntries += config.tools?.exec?.pathPrepend
            .orEmpty()
            .mapNotNull { entry ->
                resolvePathEntry(
                    spec = entry,
                    workspaceDir = workspaceDir,
                    homeDir = homeDirProvider(),
                    environment = merged,
                )
            }

        if (prependEntries.isNotEmpty()) {
            merged["PATH"] = mergePath(
                prependEntries = prependEntries,
                currentPath = merged["PATH"],
            )
        }

        merged.putIfAbsent("SHELL", shellPath)

        val nodePath = explicitNodeFile?.absolutePath
            ?: resolveBinaryOnPath("node", merged["PATH"])?.absolutePath
        val nodeVersion = nodePath?.let { path ->
            queryNodeVersion(path = path, environment = merged)
        }

        if (nodePath != null) {
            merged["OPENCLAW_NODE_BIN"] = nodePath
        }
        if (nodeVersion != null) {
            merged["NODE_VERSION"] = nodeVersion
        }

        ResolvedExecEnvironment(
            shellPath = shellPath,
            environment = merged,
            nodePath = nodePath,
            nodeVersion = nodeVersion,
        )
    }

    companion object {
        private const val ENV_CAPTURE_MARKER = "__OPENCLAW_ENV_START__"

        fun detectShellPath(environment: Map<String, String> = System.getenv()): String {
            val candidates = buildList {
                environment["SHELL"]?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                add("/bin/bash")
                add("/bin/zsh")
                add("/system/bin/sh")
                add("/bin/sh")
            }
            return candidates
                .map(::File)
                .firstOrNull { it.exists() && it.canExecute() }
                ?.absolutePath
                ?: "/bin/sh"
        }

        fun resolveBinaryOnPath(binary: String, path: String?): File? {
            if (binary.isBlank() || path.isNullOrBlank()) return null
            return path.split(File.pathSeparatorChar)
                .asSequence()
                .mapNotNull { segment ->
                    segment.trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let(::File)
                        ?.resolve(binary)
                }
                .firstOrNull { it.exists() && it.canExecute() }
        }

        private suspend fun captureShellEnvironment(
            shellPath: String,
            timeoutMs: Int,
            inheritedEnv: Map<String, String>,
        ): Map<String, String> = withContext(Dispatchers.IO) {
            val captureCommand = "printf '%s\\n' '$ENV_CAPTURE_MARKER'; env -0"
            val process = ProcessBuilder(
                shellPath,
                *buildShellArgs(shellPath, captureCommand),
            ).redirectErrorStream(true).apply {
                val env = environment()
                env.clear()
                env.putAll(inheritedEnv)
            }.start()

            return@withContext try {
                if (!process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    emptyMap()
                } else {
                    parseCapturedEnvironment(process.inputStream.readBytes())
                }
            } catch (_: Throwable) {
                process.destroyForcibly()
                emptyMap()
            } finally {
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
            }
        }

        private fun buildShellArgs(shellPath: String, captureCommand: String): Array<String> {
            val shellName = File(shellPath).name.lowercase()
            return when (shellName) {
                "bash", "zsh", "ksh" -> arrayOf("-lic", captureCommand)
                else -> arrayOf("-c", captureCommand)
            }
        }

        private fun parseCapturedEnvironment(bytes: ByteArray): Map<String, String> {
            val payload = bytes.toString(Charsets.UTF_8)
            val markerIndex = payload.indexOf("$ENV_CAPTURE_MARKER\n")
            if (markerIndex < 0) return emptyMap()
            val envBlock = payload.substring(markerIndex + ENV_CAPTURE_MARKER.length + 1)
            return envBlock.split('\u0000')
                .mapNotNull { entry ->
                    val separator = entry.indexOf('=')
                    if (separator <= 0) {
                        null
                    } else {
                        val key = entry.substring(0, separator)
                        val value = entry.substring(separator + 1)
                        key to value
                    }
                }
                .toMap()
        }

        private fun resolveBinarySpec(
            spec: String,
            currentPath: String?,
            workspaceDir: String,
            homeDir: String?,
            environment: Map<String, String>,
        ): File? {
            val trimmed = spec.trim()
            if (trimmed.isEmpty()) return null
            if (!trimmed.contains('/')) {
                return resolveBinaryOnPath(trimmed, currentPath)
            }

            val file = File(
                normalizePathSpec(
                    spec = trimmed,
                    workspaceDir = workspaceDir,
                    homeDir = homeDir,
                    environment = environment,
                ),
            )
            val candidate = when {
                file.isDirectory -> file.resolve("node")
                else -> file
            }
            return candidate.takeIf { it.exists() && it.canExecute() }
        }

        private fun resolvePathEntry(
            spec: String,
            workspaceDir: String,
            homeDir: String?,
            environment: Map<String, String>,
        ): String? {
            val trimmed = spec.trim()
            if (trimmed.isEmpty()) return null
            val normalized = normalizePathSpec(
                spec = trimmed,
                workspaceDir = workspaceDir,
                homeDir = homeDir,
                environment = environment,
            )
            val file = File(normalized)
            return if (file.isFile) {
                file.parentFile?.absolutePath
            } else {
                file.absolutePath
            }
        }

        private fun normalizePathSpec(
            spec: String,
            workspaceDir: String,
            homeDir: String?,
            environment: Map<String, String>,
        ): String {
            var expanded = spec
            if (expanded == "~" || expanded.startsWith("~/")) {
                val resolvedHome = homeDir ?: environment["HOME"].orEmpty()
                if (resolvedHome.isNotBlank()) {
                    expanded = resolvedHome + expanded.removePrefix("~")
                }
            }
            expanded = expandVariables(expanded, environment + mapOf("WORKSPACE" to workspaceDir))
            val file = File(expanded)
            return if (file.isAbsolute) file.absolutePath else File(workspaceDir, expanded).absolutePath
        }

        private fun expandVariables(
            input: String,
            environment: Map<String, String>,
        ): String {
            val regex = Regex("""\$(\{)?([A-Za-z_][A-Za-z0-9_]*)\}?""")
            return regex.replace(input) { match ->
                val key = match.groupValues[2]
                environment[key] ?: match.value
            }
        }

        private fun mergePath(
            prependEntries: List<String>,
            currentPath: String?,
        ): String {
            val ordered = linkedSetOf<String>()
            prependEntries
                .map { File(it).absolutePath }
                .forEach(ordered::add)
            currentPath
                ?.split(File.pathSeparatorChar)
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.forEach(ordered::add)
            return ordered.joinToString(File.pathSeparator)
        }

        private suspend fun queryNodeVersion(
            path: String,
            environment: Map<String, String>,
        ): String? = withContext(Dispatchers.IO) {
            val process = ProcessBuilder(path, "--version")
                .redirectErrorStream(true)
                .apply {
                    val env = environment()
                    env.clear()
                    env.putAll(environment)
                }
                .start()
            return@withContext try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    null
                } else {
                    process.inputStream.bufferedReader().use { reader ->
                        reader.readText().trim().takeIf { it.isNotEmpty() }
                    }
                }
            } catch (_: Throwable) {
                process.destroyForcibly()
                null
            } finally {
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
            }
        }
    }
}
