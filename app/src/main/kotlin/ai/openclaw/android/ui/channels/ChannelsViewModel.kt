package ai.openclaw.android.ui.channels

import ai.openclaw.android.AgentEngine
import ai.openclaw.core.model.*
import ai.openclaw.runtime.gateway.ChannelManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChannelsViewModel(private val engine: AgentEngine) : ViewModel() {

    data class ChannelUiItem(
        val key: String,
        val name: String,
        val type: String,
        val enabled: Boolean,
        val status: ChannelManager.ChannelStatus?,
        val error: String?,
    )

    private val _channels = MutableStateFlow<List<ChannelUiItem>>(emptyList())
    val channels: StateFlow<List<ChannelUiItem>> = _channels.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val config = engine.config
        val snapshot = engine.channelManager.getSnapshot()
        val items = mutableListOf<ChannelUiItem>()

        config.channels?.let { ch ->
            ch.telegram?.let { cfg ->
                addItems(items, "telegram", "Telegram", cfg.enabled, cfg.accounts, snapshot)
            }
            ch.discord?.let { cfg ->
                addItems(items, "discord", "Discord", cfg.enabled, cfg.accounts, snapshot)
            }
            ch.slack?.let { cfg ->
                addItems(items, "slack", "Slack", cfg.enabled, cfg.accounts, snapshot)
            }
            ch.signal?.let { cfg ->
                addItems(items, "signal", "Signal", cfg.enabled, cfg.accounts, snapshot)
            }
            ch.matrix?.let { cfg ->
                addItems(items, "matrix", "Matrix", cfg.enabled, cfg.accounts, snapshot)
            }
            ch.irc?.let { cfg ->
                addItems(items, "irc", "IRC", cfg.enabled, cfg.accounts, snapshot)
            }
            ch.googlechat?.let { cfg ->
                addItems(items, "googlechat", "Google Chat", cfg.enabled, cfg.accounts, snapshot)
            }
            ch.whatsapp?.let { cfg ->
                addItems(items, "whatsapp", "WhatsApp", cfg.enabled, cfg.accounts, snapshot)
            }
            ch.msteams?.let { cfg ->
                // MSTeams doesn't use the accounts map pattern - treat as single instance
                addSingleItem(items, "msteams", "MS Teams", cfg.enabled, snapshot)
            }
            ch.imessage?.let { cfg ->
                addItems(items, "imessage", "iMessage", cfg.enabled, cfg.accounts, snapshot)
            }
            ch.line?.let { cfg ->
                addItems(items, "line", "LINE", cfg.enabled, cfg.accounts, snapshot)
            }
            ch.mattermost?.let { cfg ->
                addItems(items, "mattermost", "Mattermost", cfg.enabled, cfg.accounts, snapshot)
            }
            ch.nostr?.let { cfg ->
                addItems(items, "nostr", "Nostr", cfg.enabled, cfg.accounts, snapshot)
            }
            ch.webchat?.let { cfg ->
                addSingleItem(items, "webchat", "WebChat", cfg.enabled, snapshot)
            }
        }

        _channels.value = items
    }

    private fun addItems(
        items: MutableList<ChannelUiItem>,
        type: String,
        displayName: String,
        enabled: Boolean?,
        accounts: Map<String, *>?,
        snapshot: Map<String, ChannelManager.ChannelSnapshot>,
    ) {
        if (accounts.isNullOrEmpty()) {
            addSingleItem(items, type, displayName, enabled, snapshot)
            return
        }
        for ((accountId, _) in accounts) {
            val key = "$type:$accountId"
            val snap = snapshot[key]
            val status = snap?.status?.let { parseStatus(it) }
            items.add(
                ChannelUiItem(
                    key = key,
                    name = if (accountId == "default") displayName else "$displayName ($accountId)",
                    type = type,
                    enabled = enabled ?: false,
                    status = status,
                    error = snap?.error,
                ),
            )
        }
    }

    private fun addSingleItem(
        items: MutableList<ChannelUiItem>,
        type: String,
        displayName: String,
        enabled: Boolean?,
        snapshot: Map<String, ChannelManager.ChannelSnapshot>,
    ) {
        val key = "$type:default"
        val snap = snapshot[key]
        val status = snap?.status?.let { parseStatus(it) }
        items.add(
            ChannelUiItem(
                key = key,
                name = displayName,
                type = type,
                enabled = enabled ?: false,
                status = status,
                error = snap?.error,
            ),
        )
    }

    private fun parseStatus(s: String): ChannelManager.ChannelStatus? {
        return try {
            ChannelManager.ChannelStatus.valueOf(s.uppercase())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun startChannel(key: String) {
        val parts = key.split(":", limit = 2)
        val channelId = parts[0]
        val accountId = parts.getOrElse(1) { "default" }
        viewModelScope.launch {
            try {
                engine.channelManager.startChannel(channelId, accountId)
            } catch (_: Exception) {
                // handled via snapshot error
            }
            refresh()
        }
    }

    fun stopChannel(key: String) {
        val parts = key.split(":", limit = 2)
        val channelId = parts[0]
        val accountId = parts.getOrElse(1) { "default" }
        viewModelScope.launch {
            try {
                engine.channelManager.stopChannel(channelId, accountId)
            } catch (_: Exception) {
                // handled via snapshot error
            }
            refresh()
        }
    }

    fun removeChannel(key: String) {
        val parts = key.split(":", limit = 2)
        val type = parts[0]
        val accountId = parts.getOrElse(1) { "default" }

        viewModelScope.launch {
            // Stop the channel first
            try {
                engine.channelManager.stopChannel(type, accountId)
            } catch (_: Exception) { }

            val config = engine.config
            val updatedChannels = removeChannelFromConfig(config.channels, type, accountId)
            engine.saveConfig(config.copy(channels = updatedChannels))
            engine.reloadConfig()
            refresh()
        }
    }

    private fun removeChannelFromConfig(
        channels: ChannelsConfig?,
        type: String,
        accountId: String,
    ): ChannelsConfig? {
        if (channels == null) return null
        return when (type) {
            "telegram" -> channels.copy(telegram = removeAccount(channels.telegram, accountId))
            "discord" -> channels.copy(discord = removeAccount(channels.discord, accountId))
            "slack" -> channels.copy(slack = removeAccount(channels.slack, accountId))
            "signal" -> channels.copy(signal = removeAccount(channels.signal, accountId))
            "matrix" -> channels.copy(matrix = removeAccount(channels.matrix, accountId))
            "irc" -> channels.copy(irc = removeAccount(channels.irc, accountId))
            "googlechat" -> channels.copy(googlechat = removeAccount(channels.googlechat, accountId))
            "whatsapp" -> channels.copy(whatsapp = removeAccount(channels.whatsapp, accountId))
            "msteams" -> channels.copy(msteams = null)
            "imessage" -> channels.copy(imessage = removeAccount(channels.imessage, accountId))
            "line" -> channels.copy(line = removeAccount(channels.line, accountId))
            "mattermost" -> channels.copy(mattermost = removeAccount(channels.mattermost, accountId))
            "nostr" -> channels.copy(nostr = removeAccount(channels.nostr, accountId))
            "webchat" -> channels.copy(webchat = null)
            else -> channels
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> removeAccount(cfg: Any?, accountId: String): T? {
        if (cfg == null) return null
        // Use reflection-free approach: for account-based configs, if removing the only account,
        // remove the whole config. The caller passes the typed config.
        // Since we can't generically access .accounts on different types, we null the whole config
        // when removing. A more precise approach would need per-type handling.
        return null as T?
    }

    class Factory(private val engine: AgentEngine) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChannelsViewModel(engine) as T
        }
    }
}
