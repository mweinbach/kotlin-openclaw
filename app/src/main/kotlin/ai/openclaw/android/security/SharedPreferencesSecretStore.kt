package ai.openclaw.android.security

import android.content.Context
import android.content.SharedPreferences
import ai.openclaw.core.security.SecretCategory
import ai.openclaw.core.security.SecretStoreProvider

/**
 * Persistent secret store backed by SharedPreferences.
 * Keeps API keys across app restarts.
 */
class SharedPreferencesSecretStore(
    context: Context,
) : SecretStoreProvider {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    override suspend fun storeSecret(
        key: String,
        value: String,
        category: SecretCategory,
    ) {
        prefs.edit()
            .putString(key, value)
            .putString(categoryKey(key), category.name)
            .apply()
    }

    override suspend fun getSecret(key: String): String? = prefs.getString(key, null)

    override suspend fun deleteSecret(key: String): Boolean {
        val existed = prefs.contains(key)
        prefs.edit()
            .remove(key)
            .remove(categoryKey(key))
            .apply()
        return existed
    }

    override suspend fun listSecretKeys(): List<String> {
        return prefs.all.keys
            .asSequence()
            .filter { !it.startsWith(CATEGORY_PREFIX) }
            .toList()
    }

    override suspend fun hasSecret(key: String): Boolean = prefs.contains(key)

    private fun categoryKey(key: String): String = "$CATEGORY_PREFIX$key"

    companion object {
        private const val PREFS_NAME = "openclaw_secrets"
        private const val CATEGORY_PREFIX = "__category__"
    }
}
