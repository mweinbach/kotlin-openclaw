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
            "telegram" -> channels.copy(
                telegram = removeAccountConfig(channels.telegram, accountId, { it.accounts }) { cfg, accounts ->
                    cfg.copy(accounts = accounts)
                },
            )
            "discord" -> channels.copy(
                discord = removeAccountConfig(channels.discord, accountId, { it.accounts }) { cfg, accounts ->
                    cfg.copy(accounts = accounts)
                },
            )
            "slack" -> channels.copy(
                slack = removeAccountConfig(channels.slack, accountId, { it.accounts }) { cfg, accounts ->
                    cfg.copy(accounts = accounts)
                },
            )
            "signal" -> channels.copy(
                signal = removeAccountConfig(channels.signal, accountId, { it.accounts }) { cfg, accounts ->
                    cfg.copy(accounts = accounts)
                },
            )
            "matrix" -> channels.copy(
                matrix = removeAccountConfig(channels.matrix, accountId, { it.accounts }) { cfg, accounts ->
                    cfg.copy(accounts = accounts)
                },
            )
            "irc" -> channels.copy(
                irc = removeAccountConfig(channels.irc, accountId, { it.accounts }) { cfg, accounts ->
                    cfg.copy(accounts = accounts)
                },
            )
            "googlechat" -> channels.copy(
                googlechat = removeAccountConfig(channels.googlechat, accountId, { it.accounts }) { cfg, accounts ->
                    cfg.copy(accounts = accounts)
                },
            )
            "whatsapp" -> channels.copy(
                whatsapp = removeAccountConfig(channels.whatsapp, accountId, { it.accounts }) { cfg, accounts ->
                    cfg.copy(accounts = accounts)
                },
            )
            "msteams" -> channels.copy(msteams = null)
            "imessage" -> channels.copy(
                imessage = removeAccountConfig(channels.imessage, accountId, { it.accounts }) { cfg, accounts ->
                    cfg.copy(accounts = accounts)
                },
            )
            "line" -> channels.copy(
                line = removeAccountConfig(channels.line, accountId, { it.accounts }) { cfg, accounts ->
                    cfg.copy(accounts = accounts)
                },
            )
            "mattermost" -> channels.copy(
                mattermost = removeAccountConfig(channels.mattermost, accountId, { it.accounts }) { cfg, accounts ->
                    cfg.copy(accounts = accounts)
                },
            )
            "nostr" -> channels.copy(
                nostr = removeAccountConfig(channels.nostr, accountId, { it.accounts }) { cfg, accounts ->
                    cfg.copy(accounts = accounts)
                },
            )
            "webchat" -> channels.copy(webchat = null)
            else -> channels
        }
    }

    private fun <C, A> removeAccountConfig(
        cfg: C?,
        accountId: String,
        accountsOf: (C) -> Map<String, A>?,
        copyWithAccounts: (C, Map<String, A>) -> C,
    ): C? {
        if (cfg == null) return null
        val updatedAccounts = accountsOf(cfg)
            .orEmpty()
            .filterKeys { it != accountId }
        return if (updatedAccounts.isEmpty()) null else copyWithAccounts(cfg, updatedAccounts)
    }

    class Factory(private val engine: AgentEngine) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChannelsViewModel(engine) as T
        }
    }
}
