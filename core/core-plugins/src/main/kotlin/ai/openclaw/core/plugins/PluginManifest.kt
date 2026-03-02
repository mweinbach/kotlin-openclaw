package ai.openclaw.core.plugins

import kotlinx.serialization.Serializable

// --- Plugin Manifest (ported from src/plugins/manifest.ts) ---

const val PLUGIN_MANIFEST_FILENAME = "openclaw.plugin.json"

@Serializable
data class PluginConfigUiHint(
    val label: String? = null,
    val help: String? = null,
    val tags: List<String>? = null,
    val advanced: Boolean? = null,
    val sensitive: Boolean? = null,
    val placeholder: String? = null,
)

@Serializable
data class PluginManifest(
    val id: String,
    val configSchema: Map<String, String> = emptyMap(),
    val kind: String? = null,
    val channels: List<String>? = null,
    val providers: List<String>? = null,
    val skills: List<String>? = null,
    val name: String? = null,
    val description: String? = null,
    val version: String? = null,
    val uiHints: Map<String, PluginConfigUiHint>? = null,
)

@Serializable
data class PluginPackageChannel(
    val id: String? = null,
    val label: String? = null,
    val selectionLabel: String? = null,
    val detailLabel: String? = null,
    val docsPath: String? = null,
    val docsLabel: String? = null,
    val blurb: String? = null,
    val order: Int? = null,
    val aliases: List<String>? = null,
    val preferOver: List<String>? = null,
    val systemImage: String? = null,
)

@Serializable
data class PluginPackageInstall(
    val npmSpec: String? = null,
    val localPath: String? = null,
    val defaultChoice: String? = null,
)

@Serializable
data class OpenClawPackageManifest(
    val extensions: List<String>? = null,
    val channel: PluginPackageChannel? = null,
    val install: PluginPackageInstall? = null,
)
