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

    private data class TarEntry(
        val path: String,
        val content: ByteArray,
        val mode: Int = 0b110100100,
    )

    private fun createTarGzArchive(entries: List<TarEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip ->
            for (entry in entries) {
                writeTarHeader(
                    output = gzip,
                    path = entry.path,
                    size = entry.content.size.toLong(),
                    mode = entry.mode.toLong(),
                )
                gzip.write(entry.content)
                val padding = ((512 - (entry.content.size % 512)) % 512)
                if (padding > 0) {
                    gzip.write(ByteArray(padding))
                }
            }
            gzip.write(ByteArray(1024))
        }
        return out.toByteArray()
    }

    private fun writeTarHeader(
        output: GZIPOutputStream,
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
