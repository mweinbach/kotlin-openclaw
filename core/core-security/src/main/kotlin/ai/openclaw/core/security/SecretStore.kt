package ai.openclaw.core.security

/**
 * Category of secrets for organizational and audit purposes.
 */
enum class SecretCategory {
    LLM_API_KEY,
    CHANNEL_TOKEN,
    WEBHOOK_SECRET,
    ENCRYPTION_KEY,
    SERVICE_ACCOUNT,
    CUSTOM,
}

/**
 * Record of a secret access for audit logging.
 */
data class SecretAccessRecord(
    val key: String,
    val category: SecretCategory?,
    val operation: String,
    val caller: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Interface for secret storage, enabling testability via alternative implementations.
 */
interface SecretStoreProvider {
    suspend fun storeSecret(key: String, value: String, category: SecretCategory = SecretCategory.CUSTOM)
    suspend fun getSecret(key: String): String?
    suspend fun deleteSecret(key: String): Boolean
    suspend fun listSecretKeys(): List<String>
    suspend fun hasSecret(key: String): Boolean
}

/**
 * In-memory secret store for testing purposes.
 * Logs all accesses to its internal audit log.
 */
class InMemorySecretStore : SecretStoreProvider {

    private val secrets = mutableMapOf<String, String>()
    private val categories = mutableMapOf<String, SecretCategory>()
    private val _auditLog = mutableListOf<SecretAccessRecord>()

    val auditLog: List<SecretAccessRecord> get() = _auditLog.toList()

    private fun audit(key: String, operation: String, caller: String = "test") {
        _auditLog.add(
            SecretAccessRecord(
                key = key,
                category = categories[key],
                operation = operation,
                caller = caller,
            )
        )
    }

    override suspend fun storeSecret(key: String, value: String, category: SecretCategory) {
        secrets[key] = value
        categories[key] = category
        audit(key, "store")
    }

    override suspend fun getSecret(key: String): String? {
        audit(key, "get")
        return secrets[key]
    }

    override suspend fun deleteSecret(key: String): Boolean {
        audit(key, "delete")
        categories.remove(key)
        return secrets.remove(key) != null
    }

    override suspend fun listSecretKeys(): List<String> {
        audit("*", "list")
        return secrets.keys.toList()
    }

    override suspend fun hasSecret(key: String): Boolean {
        audit(key, "has")
        return secrets.containsKey(key)
    }

    fun clear() {
        secrets.clear()
        categories.clear()
        _auditLog.clear()
    }
}

/**
 * Android Keystore-backed secret store using EncryptedSharedPreferences.
 *
 * This class wraps Android's EncryptedSharedPreferences, which uses the Android
 * Keystore under the hood for AES-256 encryption. On Android it should be
 * instantiated with `AndroidSecretStore(context)` from the `core-storage` module.
 *
 * This base class provides the audit logging and structure; the actual Android
 * implementation is separated to keep core-security as a pure Kotlin/JVM module.
 */
class SecretStore(
    private val delegate: SecretStoreProvider,
    private val auditLog: AuditLog = AuditLog(),
) : SecretStoreProvider {

    override suspend fun storeSecret(key: String, value: String, category: SecretCategory) {
        auditLog.log(AuditEntry(
            event = "secret_store",
            details = "category=$category, key=$key",
        ))
        delegate.storeSecret(key, value, category)
    }

    override suspend fun getSecret(key: String): String? {
        auditLog.log(AuditEntry(
            event = "secret_get",
            details = "key=$key",
        ))
        return delegate.getSecret(key)
    }

    override suspend fun deleteSecret(key: String): Boolean {
        auditLog.log(AuditEntry(
            event = "secret_delete",
            details = "key=$key",
        ))
        return delegate.deleteSecret(key)
    }

    override suspend fun listSecretKeys(): List<String> {
        auditLog.log(AuditEntry(
            event = "secret_list",
            details = "listing all keys",
        ))
        return delegate.listSecretKeys()
    }

    override suspend fun hasSecret(key: String): Boolean {
        auditLog.log(AuditEntry(
            event = "secret_has",
            details = "key=$key",
        ))
        return delegate.hasSecret(key)
    }
}
