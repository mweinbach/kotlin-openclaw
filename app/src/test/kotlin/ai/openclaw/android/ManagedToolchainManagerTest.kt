package ai.openclaw.android

import ai.openclaw.core.model.ExecToolConfig
import ai.openclaw.core.model.ExpandedToolsConfig
import ai.openclaw.core.model.ManagedExecToolchainsConfig
import ai.openclaw.core.model.ManagedNodeToolchainConfig
import ai.openclaw.core.model.OpenClawConfig
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ManagedToolchainManagerTest {

    private lateinit var context: Application

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.resolve("toolchains").deleteRecursively()
        context.filesDir.resolve("usr").deleteRecursively()
        context.filesDir.resolve("home").deleteRecursively()
    }

    @Test
    fun `installNode downloads verifies extracts and activates managed runtime`() = runTest {
        val version = "v22.22.0"
        val archiveName = "node-$version-darwin-arm64.tar.gz"
        val archiveBytes = createTarGzArchive(
            entries = listOf(
                TarEntry(
                    path = "node-$version-darwin-arm64/bin/node",
                    content = """
                        #!/bin/sh
                        if [ "${'$'}1" = "--version" ]; then
                          echo $version
                        else
                          echo node
                        fi
                    """.trimIndent().toByteArray(),
                    mode = 0b111101101,
                ),
                TarEntry(
                    path = "node-$version-darwin-arm64/lib/node_modules/npm/bin/npm-cli.js",
                    content = "console.log('npm');".toByteArray(),
                ),
                TarEntry(
                    path = "node-$version-darwin-arm64/lib/node_modules/npm/bin/npx-cli.js",
                    content = "console.log('npx');".toByteArray(),
                ),
                TarEntry(
                    path = "node-$version-darwin-arm64/lib/node_modules/corepack/dist/corepack.js",
                    content = "console.log('corepack');".toByteArray(),
                ),
            ),
        )
        val sha256 = sha256(archiveBytes)
        val baseUrl = "https://example.test/dist"
        val downloads = mapOf(
            "$baseUrl/$version/$archiveName" to archiveBytes,
        )
        val texts = mapOf(
            "$baseUrl/$version/SHASUMS256.txt" to "$sha256  $archiveName\n",
        )
        val manager = ManagedToolchainManager(
            context = context,
            client = OkHttpClient(),
            platformDetector = { ToolchainPlatform(os = "darwin", arch = "arm64") },
            downloadToFile = { url, target ->
                target.writeBytes(downloads.getValue(url))
            },
            downloadText = { url ->
                texts.getValue(url)
            },
            nowProvider = { Instant.parse("2026-03-07T00:00:00Z") },
        )
        val config = OpenClawConfig(
            tools = ExpandedToolsConfig(
                exec = ExecToolConfig(
                    managed = ManagedExecToolchainsConfig(
                        node = ManagedNodeToolchainConfig(
                            version = version.removePrefix("v"),
                            baseUrl = baseUrl,
                        ),
                    ),
                ),
            ),
        )

        val activation = manager.installNode(config)

        assertTrue(activation.nodeSupported)
        val nodePath = activation.nodePath
        assertNotNull(nodePath)
        val nodeFile = File(nodePath)
        assertTrue(nodeFile.exists())
        assertEquals(version, ProcessBuilder(nodePath, "--version").start().inputStream.bufferedReader().readText().trim())

        val binDir = nodeFile.parentFile ?: error("missing bin dir")
        assertTrue(binDir.resolve("npm").exists())
        assertTrue(binDir.resolve("npx").exists())
        assertTrue(binDir.resolve("corepack").exists())

        val resolved = ExecEnvironmentResolver().resolve(
            config = config,
            workspaceDir = context.filesDir.absolutePath,
            managedNodePath = activation.nodePath,
            managedPathPrepend = activation.prependPaths,
            managedEnvironmentOverrides = activation.environmentOverrides,
        )
        val status = manager.buildStatus(activation, resolved)

        assertTrue(status.nodeInstalled)
        assertTrue(status.nodeManaged)
        assertTrue(status.missingEssentialBins.isEmpty())
    }

    @Test
    fun `prepare auto installs managed node when configured`() = runTest {
        val version = "v22.22.0"
        val archiveName = "node-$version-darwin-arm64.tar.gz"
        val archiveBytes = createTarGzArchive(
            entries = listOf(
                TarEntry(
                    path = "node-$version-darwin-arm64/bin/node",
                    content = "#!/bin/sh\necho $version\n".toByteArray(),
                    mode = 0b111101101,
                ),
            ),
        )
        val sha256 = sha256(archiveBytes)
        val baseUrl = "https://example.test/dist"
        var downloadCount = 0
        val manager = ManagedToolchainManager(
            context = context,
            client = OkHttpClient(),
            platformDetector = { ToolchainPlatform(os = "darwin", arch = "arm64") },
            downloadToFile = { url, target ->
                downloadCount += 1
                assertEquals("$baseUrl/$version/$archiveName", url)
                target.writeBytes(archiveBytes)
            },
            downloadText = { url ->
                assertEquals("$baseUrl/$version/SHASUMS256.txt", url)
                "$sha256  $archiveName\n"
            },
            nowProvider = { Instant.parse("2026-03-07T00:00:00Z") },
        )
        val config = OpenClawConfig(
            tools = ExpandedToolsConfig(
                exec = ExecToolConfig(
                    managed = ManagedExecToolchainsConfig(
                        node = ManagedNodeToolchainConfig(
                            autoInstall = true,
                            version = version.removePrefix("v"),
                            baseUrl = baseUrl,
                        ),
                    ),
                ),
            ),
        )

        val activation = manager.prepare(config)

        assertEquals(1, downloadCount)
        assertNotNull(activation.nodePath)
        assertTrue(activation.prependPaths.isNotEmpty())
    }

    @Test
    fun `prepare reports android github release support when no custom bundle is configured`() = runTest {
        val manager = ManagedToolchainManager(
            context = context,
            client = OkHttpClient(),
            platformDetector = { ToolchainPlatform(os = "android", arch = "arm64") },
        )

        val activation = manager.prepare(OpenClawConfig())

        assertTrue(activation.nodeSupported)
        assertTrue(activation.nodeMessage?.contains("baked-in OpenClaw release metadata") == true)
    }

    @Test
    fun `installNode supports official linux tar xz archives`() = runTest {
        val version = "v22.22.0"
        val archiveName = "node-$version-linux-x64.tar.xz"
        val archiveBytes = createTarXzArchive(
            entries = listOf(
                TarEntry(
                    path = "node-$version-linux-x64/bin/node",
                    content = "#!/bin/sh\necho $version\n".toByteArray(),
                    mode = 0b111101101,
                ),
            ),
        )
        val sha256 = sha256(archiveBytes)
        val baseUrl = "https://example.test/dist"
        val manager = ManagedToolchainManager(
            context = context,
            client = OkHttpClient(),
            platformDetector = { ToolchainPlatform(os = "linux", arch = "x64") },
            downloadToFile = { url, target ->
                assertEquals("$baseUrl/$version/$archiveName", url)
                target.writeBytes(archiveBytes)
            },
            downloadText = { url ->
                assertEquals("$baseUrl/$version/SHASUMS256.txt", url)
                "$sha256  $archiveName\n"
            },
            nowProvider = { Instant.parse("2026-03-07T00:00:00Z") },
        )
        val config = OpenClawConfig(
            tools = ExpandedToolsConfig(
                exec = ExecToolConfig(
                    managed = ManagedExecToolchainsConfig(
                        node = ManagedNodeToolchainConfig(
                            version = version.removePrefix("v"),
                            baseUrl = baseUrl,
                        ),
                    ),
                ),
            ),
        )

        val activation = manager.installNode(config)

        assertTrue(activation.nodeSupported)
        assertNotNull(activation.nodePath)
        assertTrue(File(activation.nodePath!!).exists())
    }

    @Test
    fun `installNode uses baked in android release metadata without checksum fetch`() = runTest {
        val version = "v25.3.0"
        val archiveName = "openclaw-node-$version-android-arm64.tar.xz"
        val archiveBytes = createTarXzArchive(
            entries = listOf(
                TarEntry(
                    path = "openclaw-node-$version-android-arm64/usr/bin/node",
                    content = "#!/bin/sh\necho $version\n".toByteArray(),
                    mode = 0b111101101,
                ),
                TarEntry(
                    path = "openclaw-node-$version-android-arm64/usr/lib/node_modules/npm/bin/npm-cli.js",
                    content = "console.log('npm');".toByteArray(),
                ),
                TarEntry(
                    path = "openclaw-node-$version-android-arm64/usr/lib/node_modules/npm/bin/npx-cli.js",
                    content = "console.log('npx');".toByteArray(),
                ),
                TarEntry(
                    path = "openclaw-node-$version-android-arm64/usr/lib/node_modules/corepack/dist/corepack.js",
                    content = "console.log('corepack');".toByteArray(),
                ),
                TarEntry(
                    path = "openclaw-node-$version-android-arm64/usr/bin/rg",
                    content = "#!/bin/sh\necho rg\n".toByteArray(),
                    mode = 0b111101101,
                ),
            ),
        )
        val sha256 = sha256(archiveBytes)
        var checksumFetches = 0
        val expectedUrl =
            "https://github.com/mweinbach/kotlin-openclaw/releases/download/" +
                "toolchain-node-android-arm64/$archiveName"
        val manager = ManagedToolchainManager(
            context = context,
            client = OkHttpClient(),
            platformDetector = { ToolchainPlatform(os = "android", arch = "arm64") },
            downloadToFile = { url, target ->
                assertEquals(expectedUrl, url)
                target.writeBytes(archiveBytes)
            },
            downloadText = {
                checksumFetches += 1
                error("Built-in Android bundle should not need a checksum fetch")
            },
            nowProvider = { Instant.parse("2026-03-07T00:00:00Z") },
        )

        val activation = manager.installNode(
            OpenClawConfig(
                tools = ExpandedToolsConfig(
                    exec = ExecToolConfig(
                        managed = ManagedExecToolchainsConfig(
                            node = ManagedNodeToolchainConfig(
                                version = version.removePrefix("v"),
                                sha256 = sha256,
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(activation.nodeSupported)
        assertNotNull(activation.nodePath)
        assertEquals(0, checksumFetches)
    }

    @Test
    fun `installNode installs android bundle into app prefix and exports runtime env`() = runTest {
        val version = "v25.3.0"
        val archiveName = "openclaw-node-$version-android-arm64.tar.xz"
        val archiveBytes = createTarXzArchive(
            entries = listOf(
                TarEntry(
                    path = "openclaw-node-$version-android-arm64/usr/bin/node",
                    content = "#!/bin/sh\necho $version\n".toByteArray(),
                    mode = 0b111101101,
                ),
                TarEntry(
                    path = "openclaw-node-$version-android-arm64/usr/lib/node_modules/npm/bin/npm-cli.js",
                    content = "console.log('npm');".toByteArray(),
                ),
                TarEntry(
                    path = "openclaw-node-$version-android-arm64/usr/lib/node_modules/npm/bin/npx-cli.js",
                    content = "console.log('npx');".toByteArray(),
                ),
                TarEntry(
                    path = "openclaw-node-$version-android-arm64/usr/lib/node_modules/corepack/dist/corepack.js",
                    content = "console.log('corepack');".toByteArray(),
                ),
                TarEntry(
                    path = "openclaw-node-$version-android-arm64/usr/bin/rg",
                    content = "#!/bin/sh\necho rg\n".toByteArray(),
                    mode = 0b111101101,
                ),
                TarEntry(
                    path = "openclaw-node-$version-android-arm64/usr/etc/tls/cert.pem",
                    content = "cert".toByteArray(),
                ),
            ),
        )
        val sha256 = sha256(archiveBytes)
        val baseUrl = "https://example.test/releases/download/toolchain-node-android-arm64"
        val manager = ManagedToolchainManager(
            context = context,
            client = OkHttpClient(),
            platformDetector = { ToolchainPlatform(os = "android", arch = "arm64") },
            downloadToFile = { url, target ->
                assertEquals("$baseUrl/$archiveName", url)
                target.writeBytes(archiveBytes)
            },
            downloadText = { url ->
                assertEquals("$baseUrl/$archiveName.sha256", url)
                "$sha256  $archiveName\n"
            },
            nowProvider = { Instant.parse("2026-03-07T00:00:00Z") },
        )
        val config = OpenClawConfig(
            tools = ExpandedToolsConfig(
                exec = ExecToolConfig(
                    managed = ManagedExecToolchainsConfig(
                        node = ManagedNodeToolchainConfig(
                            version = version.removePrefix("v"),
                            baseUrl = baseUrl,
                        ),
                    ),
                ),
            ),
        )

        val activation = manager.installNode(config)
        val nodePath = activation.nodePath
        assertNotNull(nodePath)
        assertEquals(context.filesDir.resolve("usr/bin/node").absolutePath, nodePath)
        assertTrue(context.filesDir.resolve("usr/bin/sh").exists())
        assertTrue(context.filesDir.resolve("usr/bin/env").exists())
        assertTrue(context.filesDir.resolve("home").isDirectory)

        val resolved = ExecEnvironmentResolver().resolve(
            config = config,
            workspaceDir = context.filesDir.absolutePath,
            managedNodePath = activation.nodePath,
            managedPathPrepend = activation.prependPaths,
            managedEnvironmentOverrides = activation.environmentOverrides,
        )

        assertEquals(context.filesDir.resolve("usr").absolutePath, resolved.environment["PREFIX"])
        assertEquals(context.filesDir.resolve("home").absolutePath, resolved.environment["HOME"])
        assertEquals(context.filesDir.resolve("usr/tmp").absolutePath, resolved.environment["TMPDIR"])
        assertEquals(context.filesDir.resolve("usr/lib").absolutePath, resolved.environment["LD_LIBRARY_PATH"])
        assertEquals(context.filesDir.resolve("usr/etc/tls/cert.pem").absolutePath, resolved.environment["SSL_CERT_FILE"])
        assertTrue(resolved.nodePath?.endsWith("/usr/bin/node") == true)

        val status = manager.buildStatus(activation, resolved)
        assertEquals(listOf("corepack", "node", "npm", "npx", "rg"), status.availableBins)
        assertTrue(status.missingRecommendedBins.isEmpty())
    }

    private data class TarEntry(
        val path: String,
        val content: ByteArray,
        val mode: Int = 0b110100100,
    )

    private fun createTarGzArchive(entries: List<TarEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip ->
            writeTarArchive(gzip, entries)
        }
        return out.toByteArray()
    }

    private fun createTarXzArchive(entries: List<TarEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        XZOutputStream(out, LZMA2Options()).use { xz ->
            writeTarArchive(xz, entries)
        }
        return out.toByteArray()
    }

    private fun writeTarArchive(
        output: java.io.OutputStream,
        entries: List<TarEntry>,
    ) {
        for (entry in entries) {
            writeTarHeader(
                output = output,
                path = entry.path,
                size = entry.content.size.toLong(),
                mode = entry.mode.toLong(),
            )
            output.write(entry.content)
            val padding = ((512 - (entry.content.size % 512)) % 512)
            if (padding > 0) {
                output.write(ByteArray(padding))
            }
        }
        output.write(ByteArray(1024))
    }

    private fun writeTarHeader(
        output: java.io.OutputStream,
        path: String,
        size: Long,
        mode: Long,
    ) {
        val header = ByteArray(512)
        writeTarString(header, 0, 100, path)
        writeTarOctal(header, 100, 8, mode)
        writeTarOctal(header, 108, 8, 0)
        writeTarOctal(header, 116, 8, 0)
        writeTarOctal(header, 124, 12, size)
        writeTarOctal(header, 136, 12, 0)
        header[156] = '0'.code.toByte()
        writeTarString(header, 257, 6, "ustar")
        writeTarString(header, 263, 2, "00")
        for (index in 148 until 156) {
            header[index] = ' '.code.toByte()
        }
        val checksum = header.sumOf { it.toUByte().toInt() }
        writeTarOctal(header, 148, 8, checksum.toLong())
        output.write(header)
    }

    private fun writeTarString(target: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray()
        bytes.copyInto(target, offset, endIndex = minOf(bytes.size, length))
    }

    private fun writeTarOctal(target: ByteArray, offset: Int, length: Int, value: Long) {
        val rendered = value.toString(8).padStart(length - 1, '0') + "\u0000"
        writeTarString(target, offset, length, rendered)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
