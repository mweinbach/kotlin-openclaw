package ai.openclaw.android

import android.content.Context
import ai.openclaw.core.model.ManagedNodeToolchainConfig
import ai.openclaw.core.model.OpenClawConfig
import ai.openclaw.runtime.engine.tools.runtime.ShellCommandBootstrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

data class ToolchainPlatform(
    val os: String,
    val arch: String,
) {
    val label: String
        get() = "$os-$arch"

    companion object {
        fun detect(): ToolchainPlatform {
            val detectedArch = android.os.Build.SUPPORTED_ABIS.firstOrNull()
                ?.let(::normalizeArch)
                ?: normalizeArch(System.getProperty("os.arch").orEmpty())
            return ToolchainPlatform(
                os = "android",
                arch = detectedArch,
            )
        }

        fun normalizeArch(value: String): String {
            return when (value.trim().lowercase(Locale.US)) {
                "x86_64", "amd64" -> "x64"
                "x86" -> "x86"
                "aarch64", "arm64", "arm64-v8a" -> "arm64"
                "armeabi-v7a", "armv7", "armv7l" -> "armv7l"
                else -> value.trim().lowercase(Locale.US)
            }
        }
    }
}

data class ManagedToolchainActivation(
    val nodePath: String? = null,
    val prependPaths: List<String> = emptyList(),
    val environmentOverrides: Map<String, String> = emptyMap(),
    val nodeRecord: ManagedNodeInstallRecord? = null,
    val nodeSupported: Boolean = false,
    val nodeMessage: String? = null,
)

data class ManagedToolchainStatus(
    val nodeSupported: Boolean = false,
    val nodeInstalled: Boolean = false,
    val nodeActive: Boolean = false,
    val nodeManaged: Boolean = false,
    val nodeVersion: String? = null,
    val nodePath: String? = null,
    val nodeMessage: String? = null,
    val availableBins: List<String> = emptyList(),
    val missingEssentialBins: List<String> = emptyList(),
    val missingRecommendedBins: List<String> = emptyList(),
)

@Serializable
data class ManagedNodeInstallRecord(
    val version: String,
    val platform: String,
    val installDir: String,
    val distRoot: String,
    val binDir: String,
    val nodePath: String,
    val npmPath: String? = null,
    val npxPath: String? = null,
    val corepackPath: String? = null,
    val sourceUrl: String,
    val sha256: String,
    val installedAt: String,
)

@Serializable
private data class ManagedToolchainState(
    val node: ManagedNodeInstallRecord? = null,
)

private data class ManagedNodeInstallRequest(
    val version: String,
    val platform: ToolchainPlatform,
    val url: String,
    val sha256: String?,
    val shasumsUrl: String?,
    val archiveName: String,
    val bundledAssetPath: String? = null,
)

private data class BuiltInNodeBundle(
    val version: String,
    val arch: String,
    val archiveName: String,
    val downloadUrl: String,
    val sha256: String,
    val bundledAssetPath: String,
)

class ManagedToolchainManager(
    private val context: Context,
    private val client: OkHttpClient,
    private val platformDetector: () -> ToolchainPlatform = { ToolchainPlatform.detect() },
    private val androidApkRuntimeDirProvider: () -> File? = {
        defaultAndroidApkRuntimeDir(context)
    },
    private val downloadToFile: suspend (url: String, target: File) -> Unit = { url, target ->
        defaultDownloadToFile(client, url, target)
    },
    private val extractBundledAssetToFile: suspend (assetPath: String, target: File) -> Boolean = { assetPath, target ->
        defaultExtractBundledAssetToFile(context, assetPath, target)
    },
    private val downloadText: suspend (url: String) -> String = { url ->
        defaultDownloadText(client, url)
    },
    private val nowProvider: () -> Instant = { Instant.now() },
) {
    private val toolchainsDir = context.filesDir.resolve("toolchains")
    private val nodeRootDir = toolchainsDir.resolve("node")
    private val downloadsDir = toolchainsDir.resolve("downloads")
    private val stateFile = toolchainsDir.resolve("managed-toolchains.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    suspend fun prepare(config: OpenClawConfig): ManagedToolchainActivation = withContext(Dispatchers.IO) {
        toolchainsDir.mkdirs()
        downloadsDir.mkdirs()

        val state = loadState()
        val request = runCatching { resolveNodeInstallRequest(config) }.getOrNull()
        var nodeRecord = state.node?.takeIf(::isRecordUsable)
        var nodeMessage: String? = null

        if (nodeRecord == null && config.tools?.exec?.managed?.node?.autoInstall == true) {
            val installResult = runCatching { installNodeInternal(config, request, state) }
            nodeRecord = installResult.getOrNull()
            nodeMessage = installResult.exceptionOrNull()?.message
        }

        if (request == null && nodeMessage == null) {
            nodeMessage = runCatching { resolveNodeInstallRequest(config) }.exceptionOrNull()?.message
        }

        ManagedToolchainActivation(
            nodePath = nodeRecord?.nodePath,
            prependPaths = listOfNotNull(nodeRecord?.binDir),
            environmentOverrides = nodeRecord?.let(::buildEnvironmentOverrides).orEmpty(),
            nodeRecord = nodeRecord,
            nodeSupported = request != null,
            nodeMessage = nodeMessage ?: defaultNodeMessage(request, nodeRecord),
        )
    }

    suspend fun installNode(config: OpenClawConfig): ManagedToolchainActivation = withContext(Dispatchers.IO) {
        toolchainsDir.mkdirs()
        downloadsDir.mkdirs()
        val state = loadState()
        val request = resolveNodeInstallRequest(config)
        val record = installNodeInternal(config, request, state)
        ManagedToolchainActivation(
            nodePath = record.nodePath,
            prependPaths = listOf(record.binDir),
            environmentOverrides = buildEnvironmentOverrides(record),
            nodeRecord = record,
            nodeSupported = true,
            nodeMessage = null,
        )
    }

    fun buildStatus(
        activation: ManagedToolchainActivation,
        execEnvironment: ResolvedExecEnvironment,
    ): ManagedToolchainStatus {
        val path = execEnvironment.environment["PATH"]
        val recommendedHostBins = recommendedHostBins(activation)
        val nodeActive = !execEnvironment.nodePath.isNullOrBlank() && execEnvironment.nodeProbeError == null
        val shimBins = ShellCommandBootstrap.availableCommandShims(execEnvironment.environment)
        val availableBins = (ESSENTIAL_JS_BINS + recommendedHostBins)
            .filter { bin ->
                when (bin) {
                    "node" -> nodeActive
                    "npm", "npx", "corepack" -> (nodeActive && bin in shimBins) ||
                        ExecEnvironmentResolver.resolveBinaryOnPath(bin, path) != null
                    else -> bin in shimBins || ExecEnvironmentResolver.resolveBinaryOnPath(bin, path) != null
                }
            }
            .distinct()

        val missingEssentialBins = ESSENTIAL_JS_BINS.filterNot { it in availableBins }
        val missingRecommendedBins = recommendedHostBins.filterNot { it in availableBins }
        val managedNodePath = activation.nodeRecord?.nodePath

        return ManagedToolchainStatus(
            nodeSupported = activation.nodeSupported,
            nodeInstalled = activation.nodeRecord != null,
            nodeActive = nodeActive,
            nodeManaged = nodeActive && managedNodePath != null && managedNodePath == execEnvironment.nodePath,
            nodeVersion = execEnvironment.nodeVersion,
            nodePath = execEnvironment.nodePath,
            nodeMessage = combineNodeMessages(
                activationMessage = activation.nodeMessage,
                probeError = execEnvironment.nodeProbeError,
                nodeRecord = activation.nodeRecord,
            ),
            availableBins = availableBins.sorted(),
            missingEssentialBins = missingEssentialBins,
            missingRecommendedBins = missingRecommendedBins,
        )
    }

    private fun recommendedHostBins(activation: ManagedToolchainActivation): List<String> {
        val platform = activation.nodeRecord?.platform ?: platformDetector().label
        return if (platform.startsWith("android-")) {
            ANDROID_RECOMMENDED_HOST_BINS
        } else {
            DESKTOP_RECOMMENDED_HOST_BINS
        }
    }

    private suspend fun installNodeInternal(
        config: OpenClawConfig,
        request: ManagedNodeInstallRequest?,
        state: ManagedToolchainState,
    ): ManagedNodeInstallRecord {
        val resolvedRequest = request ?: resolveNodeInstallRequest(config)
        if (resolvedRequest.platform.os == ANDROID_PLATFORM) {
            return installAndroidNodeInternal(resolvedRequest, state)
        }

        val existing = state.node
            ?.takeIf(::isRecordUsable)
            ?.takeIf {
                it.version == resolvedRequest.version &&
                    it.platform == resolvedRequest.platform.label &&
                    it.sourceUrl == resolvedRequest.url
            }
        if (existing != null) {
            return existing
        }

        val installDir = nodeRootDir
            .resolve(resolvedRequest.version.removePrefix("v"))
            .resolve(resolvedRequest.platform.label)
        val stagingDir = File(installDir.parentFile, installDir.name + ".tmp")
        val archiveFile = downloadsDir.resolve(resolvedRequest.archiveName)

        stagingDir.deleteRecursively()
        installDir.deleteRecursively()
        archiveFile.parentFile?.mkdirs()

        downloadToFile(resolvedRequest.url, archiveFile)
        val expectedSha = resolvedRequest.sha256
            ?.takeIf { it.isNotBlank() }
            ?: fetchSha256(resolvedRequest)
        val actualSha = sha256(archiveFile)
        require(actualSha.equals(expectedSha, ignoreCase = true)) {
            "Managed Node checksum mismatch for ${resolvedRequest.archiveName}"
        }

        extractArchive(archiveFile, stagingDir)
        val distRoot = locateDistributionRoot(stagingDir)
        val nodeBinary = locateNodeBinary(distRoot)
        require(nodeBinary.exists()) {
            "Managed Node install did not produce a node binary"
        }

        val binDir = nodeBinary.parentFile ?: error("Managed Node install is missing a bin directory")
        ensureExecutable(nodeBinary)
        ensureNodeCompanionBinaries(distRoot, binDir)

        installDir.parentFile?.mkdirs()
        require(stagingDir.renameTo(installDir)) {
            "Failed to finalize managed Node install"
        }

        val finalDistRoot = locateDistributionRoot(installDir)
        val finalBinDir = locateNodeBinary(finalDistRoot).parentFile
            ?: error("Managed Node install is missing a finalized bin directory")
        val record = ManagedNodeInstallRecord(
            version = resolvedRequest.version,
            platform = resolvedRequest.platform.label,
            installDir = installDir.absolutePath,
            distRoot = finalDistRoot.absolutePath,
            binDir = finalBinDir.absolutePath,
            nodePath = finalBinDir.resolve("node").absolutePath,
            npmPath = finalBinDir.resolve("npm").takeIf(File::exists)?.absolutePath,
            npxPath = finalBinDir.resolve("npx").takeIf(File::exists)?.absolutePath,
            corepackPath = finalBinDir.resolve("corepack").takeIf(File::exists)?.absolutePath,
            sourceUrl = resolvedRequest.url,
            sha256 = actualSha,
            installedAt = nowProvider().toString(),
        )

        saveState(state.copy(node = record))
        pruneOldNodeInstalls(activeInstallDir = installDir)
        archiveFile.delete()
        return record
    }

    private suspend fun installAndroidNodeInternal(
        request: ManagedNodeInstallRequest,
        state: ManagedToolchainState,
    ): ManagedNodeInstallRecord {
        val existing = state.node
            ?.takeIf(::isRecordUsable)
            ?.takeIf {
                it.version == request.version &&
                    it.platform == request.platform.label &&
                    it.sourceUrl == request.url
            }
        if (existing != null) {
            return existing
        }

        val archiveFile = downloadsDir.resolve(request.archiveName)
        val stagingDir = toolchainsDir.resolve("android-node-rootfs.tmp")
        val finalPrefixDir = context.filesDir.resolve(ANDROID_PREFIX_SUBDIR)
        val finalHomeDir = context.filesDir.resolve(ANDROID_HOME_SUBDIR)

        stagingDir.deleteRecursively()
        archiveFile.parentFile?.mkdirs()

        val extractedFromBundle = request.bundledAssetPath
            ?.let { assetPath -> extractBundledAssetToFile(assetPath, archiveFile) }
            ?: false
        if (!extractedFromBundle) {
            downloadToFile(request.url, archiveFile)
        }
        val expectedSha = request.sha256
            ?.takeIf { it.isNotBlank() }
            ?: fetchSha256(request)
        val actualSha = sha256(archiveFile)
        require(actualSha.equals(expectedSha, ignoreCase = true)) {
            "Managed Node checksum mismatch for ${request.archiveName}"
        }

        extractArchive(archiveFile, stagingDir)
        val bundleRoot = locateDistributionRoot(stagingDir)
        val stagedPrefixDir = locateAndroidPrefixRoot(bundleRoot)
        val stagedNodeBinary = locateNodeBinary(stagedPrefixDir)
        require(stagedNodeBinary.exists()) {
            "Managed Android Node bundle did not produce a node binary"
        }

        val stagedBinDir = stagedNodeBinary.parentFile
            ?: error("Managed Android Node bundle is missing a bin directory")
        ensureExecutable(stagedNodeBinary)
        ensureNodeCompanionBinaries(stagedPrefixDir, stagedBinDir)
        ensureAndroidCompatBinaries(stagedBinDir)

        finalPrefixDir.deleteRecursively()
        require(moveDirectory(stagedPrefixDir, finalPrefixDir)) {
            "Failed to finalize managed Android Node install"
        }

        finalHomeDir.mkdirs()
        finalPrefixDir.resolve("tmp").mkdirs()
        finalHomeDir.resolve(".npm").mkdirs()
        finalHomeDir.resolve(".cache/corepack").mkdirs()

        val packagedNodeBinary = locateAndroidApkRuntimeBinary(ANDROID_APK_NODE_BINARY)
        require(packagedNodeBinary != null || context.applicationInfo.targetSdkVersion < 29) {
            missingAndroidApkRuntimeBinaryMessage(ANDROID_APK_NODE_BINARY)
        }
        val finalNodeBinary = packagedNodeBinary ?: locateNodeBinary(finalPrefixDir)
        val finalBinDir = finalPrefixDir.resolve("bin")
        ensureNodeCompanionBinaries(finalPrefixDir, finalBinDir)
        ensureAndroidCompatBinaries(finalBinDir)

        val record = ManagedNodeInstallRecord(
            version = request.version,
            platform = request.platform.label,
            installDir = finalPrefixDir.absolutePath,
            distRoot = finalPrefixDir.absolutePath,
            binDir = finalBinDir.absolutePath,
            nodePath = finalNodeBinary.absolutePath,
            npmPath = finalBinDir.resolve("npm").takeIf(File::exists)?.absolutePath,
            npxPath = finalBinDir.resolve("npx").takeIf(File::exists)?.absolutePath,
            corepackPath = finalBinDir.resolve("corepack").takeIf(File::exists)?.absolutePath,
            sourceUrl = request.url,
            sha256 = actualSha,
            installedAt = nowProvider().toString(),
        )

        saveState(state.copy(node = record))
        archiveFile.delete()
        stagingDir.deleteRecursively()
        return record
    }

    private fun resolveNodeInstallRequest(config: OpenClawConfig): ManagedNodeInstallRequest {
        val nodeConfig = config.tools?.exec?.managed?.node
        require(nodeConfig?.enabled != false) {
            "Managed Node runtime is disabled in config"
        }

        val platform = platformDetector()
        val version = normalizeNodeVersion(nodeConfig?.version ?: DEFAULT_NODE_VERSION)
        val customUrl = nodeConfig?.downloadUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (customUrl != null) {
            require(!(platform.os == ANDROID_PLATFORM && context.applicationInfo.targetSdkVersion >= 29)) {
                "Custom Android managed Node bundles require matching APK-packaged native binaries. " +
                    "Rebuild the app with matching jniLibs instead of using tools.exec.managed.node.downloadUrl."
            }
            val customSha = nodeConfig.sha256?.trim()?.takeIf { it.isNotEmpty() }
            require(customSha != null) {
                "tools.exec.managed.node.sha256 is required when using a custom downloadUrl"
            }
            return ManagedNodeInstallRequest(
                version = version,
                platform = platform,
                url = customUrl,
                sha256 = customSha,
                shasumsUrl = null,
                archiveName = customUrl.substringAfterLast('/'),
            )
        }

        if (platform.os == ANDROID_PLATFORM) {
            require(platform.arch == ANDROID_RELEASE_ARCH) {
                unsupportedBuiltInMessage(platform)
            }
            val explicitBaseUrl = nodeConfig?.baseUrl?.trim()?.takeIf { it.isNotEmpty() }
            val builtInBundle = builtInAndroidBundle(version, platform)
            require(!(explicitBaseUrl != null && context.applicationInfo.targetSdkVersion >= 29)) {
                "Custom Android managed Node bundle base URLs require matching APK-packaged native binaries. " +
                    "Rebuild the app with matching jniLibs instead of overriding tools.exec.managed.node.baseUrl."
            }
            require(!(builtInBundle == null && context.applicationInfo.targetSdkVersion >= 29)) {
                "OpenClaw only packages APK-backed Android Node binaries for $DEFAULT_NODE_VERSION. " +
                    "Rebuild the app with matching jniLibs before using Android managed Node $version."
            }
            if (explicitBaseUrl == null && builtInBundle != null) {
                return ManagedNodeInstallRequest(
                    version = version,
                    platform = platform,
                    url = builtInBundle.downloadUrl,
                    sha256 = nodeConfig?.sha256?.trim()?.takeIf { it.isNotEmpty() } ?: builtInBundle.sha256,
                    shasumsUrl = null,
                    archiveName = builtInBundle.archiveName,
                    bundledAssetPath = builtInBundle.bundledAssetPath,
                )
            }
            val baseUrl = normalizeBaseUrl(nodeConfig, platform)
            val archiveName = androidBundleArchiveName(version, platform)
            return ManagedNodeInstallRequest(
                version = version,
                platform = platform,
                url = "$baseUrl/$archiveName",
                sha256 = nodeConfig?.sha256?.trim()?.takeIf { it.isNotEmpty() },
                shasumsUrl = "$baseUrl/$archiveName.sha256",
                archiveName = archiveName,
            )
        }

        require(platform.os in setOf("darwin", "linux")) {
            unsupportedBuiltInMessage(platform)
        }
        require(platform.arch in setOf("x64", "arm64")) {
            unsupportedBuiltInMessage(platform)
        }

        val baseUrl = normalizeBaseUrl(nodeConfig, platform)
        val archiveName = when (platform.os) {
            "linux" -> "node-$version-${platform.os}-${platform.arch}.tar.xz"
            else -> "node-$version-${platform.os}-${platform.arch}.tar.gz"
        }
        return ManagedNodeInstallRequest(
            version = version,
            platform = platform,
            url = "$baseUrl/$version/$archiveName",
            sha256 = nodeConfig?.sha256?.trim()?.takeIf { it.isNotEmpty() },
            shasumsUrl = "$baseUrl/$version/SHASUMS256.txt",
            archiveName = archiveName,
        )
    }

    private fun normalizeBaseUrl(
        nodeConfig: ManagedNodeToolchainConfig?,
        platform: ToolchainPlatform,
    ): String {
        val raw = nodeConfig?.baseUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: when (platform.os) {
                ANDROID_PLATFORM -> DEFAULT_ANDROID_NODE_BASE_URL
                else -> DEFAULT_NODE_BASE_URL
            }
        return raw.trimEnd('/')
    }

    private suspend fun fetchSha256(request: ManagedNodeInstallRequest): String {
        val shasumsUrl = request.shasumsUrl
            ?: error("A SHA-256 checksum is required for ${request.archiveName}")
        val checksums = downloadText(shasumsUrl)
        val trimmedLines = checksums.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val matchingLine = trimmedLines.firstOrNull { it.endsWith(request.archiveName) }
        if (matchingLine != null) {
            return matchingLine.substringBefore(' ').trim()
        }
        val singleValue = trimmedLines.singleOrNull()?.substringBefore(' ')?.trim()
        if (singleValue != null && SHA256_HEX.matches(singleValue)) {
            return singleValue
        }
        error("Could not find checksum for ${request.archiveName}")
    }

    private fun loadState(): ManagedToolchainState {
        if (!stateFile.exists()) return ManagedToolchainState()
        return runCatching {
            json.decodeFromString(ManagedToolchainState.serializer(), stateFile.readText())
        }.getOrDefault(ManagedToolchainState())
    }

    private fun saveState(state: ManagedToolchainState) {
        stateFile.parentFile?.mkdirs()
        val tmp = File(stateFile.parentFile, ".managed-toolchains.tmp")
        tmp.writeText(json.encodeToString(ManagedToolchainState.serializer(), state))
        require(tmp.renameTo(stateFile)) {
            "Failed to persist managed toolchain state"
        }
    }

    private fun isRecordUsable(record: ManagedNodeInstallRecord): Boolean {
        val node = File(record.nodePath)
        val bin = File(record.binDir)
        val dist = File(record.distRoot)
        return node.exists() && node.canExecute() && bin.isDirectory && dist.isDirectory
    }

    private fun pruneOldNodeInstalls(activeInstallDir: File) {
        nodeRootDir.listFiles()
            ?.forEach { versionDir ->
                versionDir.listFiles()
                    ?.filter { it.absolutePath != activeInstallDir.absolutePath }
                    ?.forEach { stale -> stale.deleteRecursively() }
                if (versionDir.listFiles().isNullOrEmpty()) {
                    versionDir.delete()
                }
            }
    }

    private fun extractArchive(archiveFile: File, targetDir: File) {
        targetDir.mkdirs()
        val name = archiveFile.name.lowercase(Locale.US)
        when {
            name.endsWith(".zip") -> unzip(archiveFile, targetDir)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> untarGz(archiveFile, targetDir)
            name.endsWith(".tar.xz") || name.endsWith(".txz") -> untarXz(archiveFile, targetDir)
            else -> error("Unsupported managed toolchain archive: ${archiveFile.name}")
        }
    }

    private fun unzip(archiveFile: File, targetDir: File) {
        ZipInputStream(archiveFile.inputStream().buffered()).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                val output = safeExtractFile(targetDir, entry.name)
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    output.outputStream().buffered().use { sink ->
                        input.copyTo(sink)
                    }
                    if (output.parentFile?.name == "bin") {
                        ensureExecutable(output)
                    }
                }
                input.closeEntry()
                entry = input.nextEntry
            }
        }
    }

    private fun untarGz(archiveFile: File, targetDir: File) {
        GZIPInputStream(archiveFile.inputStream().buffered()).use { gzip ->
            untar(gzip, targetDir)
        }
    }

    private fun untarXz(archiveFile: File, targetDir: File) {
        XZInputStream(archiveFile.inputStream().buffered()).use { xz ->
            untar(xz, targetDir)
        }
    }

    private fun untar(input: InputStream, targetDir: File) {
        while (true) {
            val header = ByteArray(TAR_BLOCK_SIZE)
            val read = input.readFully(header)
            if (read == 0 || header.all { it == 0.toByte() }) break
            require(read == TAR_BLOCK_SIZE) {
                "Truncated tar archive while installing managed Node"
            }

            val name = parseTarString(header, 0, 100)
            if (name.isBlank()) break
            val prefix = parseTarString(header, 345, 155)
            val fullName = listOf(prefix, name).filter { it.isNotBlank() }.joinToString("/")
            val typeFlag = header[156].toInt().toChar()
            val size = parseTarOctal(header, 124, 12)
            val mode = parseTarOctal(header, 100, 8)
            val output = safeExtractFile(targetDir, fullName)

            when (typeFlag) {
                '5' -> output.mkdirs()
                '2' -> {
                    skipFully(input, size)
                }
                else -> {
                    output.parentFile?.mkdirs()
                    output.outputStream().buffered().use { sink ->
                        copyFixed(input, sink, size)
                    }
                    if ((mode and 0b001_001_001L) != 0L || output.parentFile?.name == "bin") {
                        ensureExecutable(output)
                    }
                }
            }

            val padding = ((TAR_BLOCK_SIZE - (size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE).toInt()
            if (padding > 0) {
                skipFully(input, padding.toLong())
            }
        }
    }

    private fun unsupportedBuiltInMessage(platform: ToolchainPlatform): String {
        return if (platform.os == "android") {
            if (platform.arch == ANDROID_RELEASE_ARCH) {
                "OpenClaw ships built-in Android Node bundle metadata for ${platform.label}. If that download fails, configure tools.exec.managed.node.downloadUrl and sha256 for a custom bundle."
            } else {
                "OpenClaw only publishes a built-in Android Node bundle for android-$ANDROID_RELEASE_ARCH. Configure tools.exec.managed.node.downloadUrl and sha256 for a custom ${platform.label} bundle."
            }
        } else {
            "No built-in Node bundle for ${platform.label}. Configure tools.exec.managed.node.downloadUrl and sha256."
        }
    }

    private fun locateDistributionRoot(root: File): File {
        val children = root.listFiles()
            ?.filterNot { it.name.startsWith(".") }
            .orEmpty()
        return if (children.size == 1 && children[0].isDirectory) {
            children[0]
        } else {
            root
        }
    }

    private fun locateNodeBinary(distRoot: File): File {
        val unixNode = distRoot.resolve("bin/node")
        if (unixNode.exists()) return unixNode
        val rootNode = distRoot.resolve("node")
        if (rootNode.exists()) return rootNode
        error("Managed Node install is missing a node binary under ${distRoot.absolutePath}")
    }

    private fun locateAndroidPrefixRoot(root: File): File {
        val nestedPrefix = root.resolve(ANDROID_PREFIX_SUBDIR)
        if (nestedPrefix.resolve("bin/node").exists()) return nestedPrefix
        if (root.resolve("bin/node").exists()) return root
        error(
            "Managed Android Node bundle must contain $ANDROID_PREFIX_SUBDIR/bin/node " +
                "under ${root.absolutePath}",
        )
    }

    private fun ensureNodeCompanionBinaries(distRoot: File, binDir: File) {
        val companionScripts = listOf(
            "npm" to "lib/node_modules/npm/bin/npm-cli.js",
            "npx" to "lib/node_modules/npm/bin/npx-cli.js",
            "corepack" to "lib/node_modules/corepack/dist/corepack.js",
        )
        for ((binary, relativeScript) in companionScripts) {
            val binFile = binDir.resolve(binary)
            val scriptFile = distRoot.resolve(relativeScript)
            if (!binFile.exists() && scriptFile.exists()) {
                binFile.parentFile?.mkdirs()
                binFile.writeText(
                    """
                    #!/bin/sh
                    basedir=${'$'}(CDPATH= cd -- "${'$'}(dirname "${'$'}0")" && pwd)
                    exec "${'$'}basedir/node" "${'$'}basedir/../$relativeScript" "${'$'}@"
                    """.trimIndent(),
                )
            }
            if (binFile.exists()) {
                ensureExecutable(binFile)
            }
        }
    }

    private fun ensureAndroidCompatBinaries(binDir: File) {
        ensureProxyBinary(binDir.resolve("sh"), "/system/bin/sh")
        ensureProxyBinary(binDir.resolve("env"), "/system/bin/env")
    }

    private fun ensureProxyBinary(target: File, delegatePath: String) {
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            target.writeText(
                """
                #!/system/bin/sh
                exec $delegatePath "${'$'}@"
                """.trimIndent(),
            )
        }
        ensureExecutable(target)
    }

    private fun locateAndroidApkRuntimeBinary(fileName: String): File? {
        val runtimeDir = androidApkRuntimeDirProvider() ?: return null
        return runtimeDir.resolve(fileName)
            .takeIf { it.exists() && it.canExecute() }
    }

    private fun missingAndroidApkRuntimeBinaryMessage(fileName: String): String {
        val runtimeDir = androidApkRuntimeDirProvider()
        return buildString {
            append("Managed Android Node runtime requires APK-packaged native binaries under ")
            append("nativeLibraryDir; $fileName is missing")
            when {
                runtimeDir == null -> {
                    append(" (nativeLibraryDir is null)")
                }

                else -> {
                    append(" (nativeLibraryDir=${runtimeDir.absolutePath}")
                    append(", exists=${runtimeDir.exists()}")
                    append(", isDirectory=${runtimeDir.isDirectory}")
                    val visibleFiles = runtimeDir.list()
                        ?.sorted()
                        ?.joinToString(limit = 8, truncated = "...") { it }
                    if (!visibleFiles.isNullOrBlank()) {
                        append(", files=$visibleFiles")
                    }
                    append(")")
                }
            }
            append(". Ensure the app is installed from an APK/AAB built with ")
            append("packaging.jniLibs.useLegacyPackaging = true so Android extracts ")
            append("the packaged $fileName binary onto disk under nativeLibraryDir.")
        }
    }

    private fun moveDirectory(source: File, destination: File): Boolean {
        destination.parentFile?.mkdirs()
        if (source.renameTo(destination)) {
            return true
        }
        if (!source.exists()) return false
        source.copyRecursively(destination, overwrite = true)
        return source.deleteRecursively()
    }

    private fun safeExtractFile(root: File, entryName: String): File {
        val output = File(root, entryName)
        val normalizedRoot = root.canonicalFile
        val normalizedOutput = output.canonicalFile
        require(normalizedOutput.path.startsWith(normalizedRoot.path + File.separator) || normalizedOutput == normalizedRoot) {
            "Refusing to extract managed toolchain entry outside target directory"
        }
        return normalizedOutput
    }

    private fun ensureExecutable(file: File) {
        runCatching { file.setExecutable(true, false) }
    }

    private fun combineNodeMessages(
        activationMessage: String?,
        probeError: String?,
        nodeRecord: ManagedNodeInstallRecord?,
    ): String? {
        val messages = buildList {
            activationMessage?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            probeError?.trim()?.takeIf { it.isNotEmpty() }?.let { error ->
                add("Node runtime probe failed: $error")
            }
            androidExecRestrictionHint(nodeRecord, probeError)?.let(::add)
        }
        return messages.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    private fun androidExecRestrictionHint(
        nodeRecord: ManagedNodeInstallRecord?,
        probeError: String?,
    ): String? {
        if (nodeRecord == null) return null
        if (!nodeRecord.platform.startsWith("$ANDROID_PLATFORM-")) return null
        if (context.applicationInfo.targetSdkVersion < 29) return null
        if (probeError.isNullOrBlank()) return null
        if (!probeError.contains("error=13", ignoreCase = true)) return null
        if (!probeError.contains("Permission denied", ignoreCase = true)) return null

        val installDir = File(nodeRecord.installDir)
        val filesDirPath = context.filesDir.absolutePath
        if (!installDir.absolutePath.startsWith(filesDirPath)) return null

        return buildString {
            append("Android 10+ blocks exec() from writable app home directories ")
            append("for apps targeting API 29+, so the managed Node binary under ")
            append("${installDir.absolutePath} cannot run from files/usr. ")
            append("This runtime needs an APK-packaged executable location such as ")
            append("the app's native library directory instead of an extracted filesDir toolchain.")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun normalizeNodeVersion(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith("v")) trimmed else "v$trimmed"
    }

    private fun parseTarString(header: ByteArray, offset: Int, length: Int): String {
        val slice = header.copyOfRange(offset, offset + length)
        val end = slice.indexOf(0).let { if (it >= 0) it else slice.size }
        return String(slice, 0, end, StandardCharsets.UTF_8).trim()
    }

    private fun parseTarOctal(header: ByteArray, offset: Int, length: Int): Long {
        val raw = parseTarString(header, offset, length)
        if (raw.isBlank()) return 0L
        return raw.trim().toLong(radix = 8)
    }

    private fun InputStream.readFully(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun copyFixed(input: InputStream, output: java.io.OutputStream, bytes: Long) {
        var remaining = bytes
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(read >= 0) {
                "Unexpected end of archive while installing managed Node"
            }
            output.write(buffer, 0, read)
            remaining -= read.toLong()
        }
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val read = input.read()
            if (read < 0) break
            remaining -= 1
        }
    }

    private fun buildEnvironmentOverrides(record: ManagedNodeInstallRecord): Map<String, String> {
        if (!record.platform.startsWith("$ANDROID_PLATFORM-")) {
            return emptyMap()
        }

        val prefixDir = File(record.distRoot)
        val homeDir = context.filesDir.resolve(ANDROID_HOME_SUBDIR)
        val tmpDir = prefixDir.resolve("tmp")
        val certFile = prefixDir.resolve("etc/tls/cert.pem")
        val npmCacheDir = homeDir.resolve(".npm")
        val corepackHomeDir = homeDir.resolve(".cache/corepack")
        val nativeLibDir = androidApkRuntimeDirProvider()
        val nativeNode = locateAndroidApkRuntimeBinary(ANDROID_APK_NODE_BINARY)
        val nativeRg = locateAndroidApkRuntimeBinary(ANDROID_APK_RG_BINARY)
        val ldLibraryPath = buildList {
            nativeLibDir?.absolutePath?.let(::add)
            add(prefixDir.resolve("lib").absolutePath)
        }.distinct().joinToString(File.pathSeparator)
        return buildMap {
            put("PREFIX", prefixDir.absolutePath)
            put("TERMUX_PREFIX", prefixDir.absolutePath)
            put("HOME", homeDir.absolutePath)
            put("TMPDIR", tmpDir.absolutePath)
            put("LD_LIBRARY_PATH", ldLibraryPath)
            put("npm_config_cache", npmCacheDir.absolutePath)
            put("COREPACK_HOME", corepackHomeDir.absolutePath)
            nativeNode?.absolutePath?.let { nodeExec ->
                put(ShellCommandBootstrap.NODE_EXEC_ENV, nodeExec)
                prefixDir.resolve("lib/node_modules/npm/bin/npm-cli.js")
                    .takeIf(File::exists)
                    ?.absolutePath
                    ?.let { put(ShellCommandBootstrap.NPM_CLI_JS_ENV, it) }
                prefixDir.resolve("lib/node_modules/npm/bin/npx-cli.js")
                    .takeIf(File::exists)
                    ?.absolutePath
                    ?.let { put(ShellCommandBootstrap.NPX_CLI_JS_ENV, it) }
                prefixDir.resolve("lib/node_modules/corepack/dist/corepack.js")
                    .takeIf(File::exists)
                    ?.absolutePath
                    ?.let { put(ShellCommandBootstrap.COREPACK_JS_ENV, it) }
            }
            nativeRg?.absolutePath?.let { put(ShellCommandBootstrap.RG_EXEC_ENV, it) }
            if (certFile.exists()) {
                put("SSL_CERT_FILE", certFile.absolutePath)
                put("NODE_EXTRA_CA_CERTS", certFile.absolutePath)
            }
        }
    }

    private fun defaultNodeMessage(
        request: ManagedNodeInstallRequest?,
        nodeRecord: ManagedNodeInstallRecord?,
    ): String? {
        if (nodeRecord != null || request == null) {
            return null
        }
        return if (request.platform.os == ANDROID_PLATFORM) {
            "Managed Android Node bundle available from baked-in OpenClaw release metadata."
        } else {
            null
        }
    }

    companion object {
        private const val DEFAULT_NODE_VERSION = "v25.3.0"
        private const val DEFAULT_NODE_BASE_URL = "https://nodejs.org/dist"
        private const val DEFAULT_ANDROID_NODE_BASE_URL =
            "https://github.com/mweinbach/kotlin-openclaw/releases/download/toolchain-node-android-arm64"
        private const val DEFAULT_ANDROID_NODE_RELEASE_TAG = "toolchain-node-android-arm64"
        private const val ANDROID_PLATFORM = "android"
        private const val ANDROID_RELEASE_ARCH = "arm64"
        private const val ANDROID_PREFIX_SUBDIR = "usr"
        private const val ANDROID_HOME_SUBDIR = "home"
        private const val ANDROID_APK_NODE_BINARY = "libopenclaw_node.so"
        private const val ANDROID_APK_RG_BINARY = "libopenclaw_rg.so"
        private const val TAR_BLOCK_SIZE = 512
        private val ESSENTIAL_JS_BINS = listOf("node", "npm", "npx", "corepack")
        private val ANDROID_RECOMMENDED_HOST_BINS = listOf("rg")
        private val DESKTOP_RECOMMENDED_HOST_BINS = listOf("git", "rg")
        private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")
        private val BUILT_IN_ANDROID_NODE_BUNDLES = listOf(
            BuiltInNodeBundle(
                version = "v25.3.0",
                arch = "arm64",
                archiveName = "openclaw-node-v25.3.0-android-arm64.tar.xz",
                downloadUrl =
                    "https://github.com/mweinbach/kotlin-openclaw/releases/download/" +
                        "$DEFAULT_ANDROID_NODE_RELEASE_TAG/openclaw-node-v25.3.0-android-arm64.tar.xz",
                sha256 = "b2fbc14f8b355d50f48b23e14373c80a873afed897628d274838141d3b0510ce",
                bundledAssetPath = "toolchains/openclaw-node-v25.3.0-android-arm64.tar.xz",
            ),
        )

        private fun androidBundleArchiveName(
            version: String,
            platform: ToolchainPlatform,
        ): String {
            return "openclaw-node-$version-${platform.os}-${platform.arch}.tar.xz"
        }

        private fun builtInAndroidBundle(
            version: String,
            platform: ToolchainPlatform,
        ): BuiltInNodeBundle? {
            if (platform.os != ANDROID_PLATFORM) return null
            return BUILT_IN_ANDROID_NODE_BUNDLES.firstOrNull {
                it.version == version && it.arch == platform.arch
            }
        }

        private fun defaultAndroidApkRuntimeDir(context: Context): File? {
            val path = context.applicationInfo.nativeLibraryDir
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            return File(path)
        }

        private suspend fun defaultDownloadToFile(
            client: OkHttpClient,
            url: String,
            target: File,
        ) = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) {
                    "Failed to download managed toolchain from $url (${response.code})"
                }
                val body = response.body ?: error("Managed toolchain download returned an empty body")
                target.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    target.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }

        private suspend fun defaultDownloadText(
            client: OkHttpClient,
            url: String,
        ): String = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) {
                    "Failed to fetch managed toolchain metadata from $url (${response.code})"
                }
                response.body?.string() ?: error("Managed toolchain metadata response was empty")
            }
        }

        private suspend fun defaultExtractBundledAssetToFile(
            context: Context,
            assetPath: String,
            target: File,
        ): Boolean = withContext(Dispatchers.IO) {
            try {
                context.assets.open(assetPath).use { input ->
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                }
                true
            } catch (_: FileNotFoundException) {
                false
            }
        }
    }
}
