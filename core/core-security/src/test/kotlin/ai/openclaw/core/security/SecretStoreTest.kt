package ai.openclaw.core.security

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretStoreTest {

    @Test
    fun `store and retrieve secret`() = runTest {
        val store = InMemorySecretStore()
        store.storeSecret("api-key", "sk-ant-test123", SecretCategory.LLM_API_KEY)
        assertEquals("sk-ant-test123", store.getSecret("api-key"))
    }

    @Test
    fun `get nonexistent secret returns null`() = runTest {
        val store = InMemorySecretStore()
        assertNull(store.getSecret("missing"))
    }

    @Test
    fun `delete secret`() = runTest {
        val store = InMemorySecretStore()
        store.storeSecret("token", "abc", SecretCategory.CHANNEL_TOKEN)
        assertTrue(store.deleteSecret("token"))
        assertNull(store.getSecret("token"))
    }

    @Test
    fun `delete nonexistent secret returns false`() = runTest {
        val store = InMemorySecretStore()
        assertFalse(store.deleteSecret("missing"))
    }

    @Test
    fun `has secret`() = runTest {
        val store = InMemorySecretStore()
        assertFalse(store.hasSecret("key"))
        store.storeSecret("key", "val")
        assertTrue(store.hasSecret("key"))
    }

    @Test
    fun `list secret keys`() = runTest {
        val store = InMemorySecretStore()
        store.storeSecret("a", "1")
        store.storeSecret("b", "2")
        store.storeSecret("c", "3")
        val keys = store.listSecretKeys()
        assertEquals(3, keys.size)
        assertTrue(keys.containsAll(listOf("a", "b", "c")))
    }

    @Test
    fun `overwrite existing secret`() = runTest {
        val store = InMemorySecretStore()
        store.storeSecret("key", "old")
        store.storeSecret("key", "new")
        assertEquals("new", store.getSecret("key"))
    }

    @Test
    fun `audit log records operations`() = runTest {
        val store = InMemorySecretStore()
        store.storeSecret("key", "val", SecretCategory.WEBHOOK_SECRET)
        store.getSecret("key")
        store.hasSecret("key")
        store.deleteSecret("key")

        val log = store.auditLog
        assertEquals(4, log.size)
        assertEquals("store", log[0].operation)
        assertEquals("get", log[1].operation)
        assertEquals("has", log[2].operation)
        assertEquals("delete", log[3].operation)
        assertEquals(SecretCategory.WEBHOOK_SECRET, log[0].category)
    }

    @Test
    fun `SecretStore wraps delegate and logs to audit log`() = runTest {
        val delegate = InMemorySecretStore()
        val auditLog = AuditLog()
        val secretStore = SecretStore(delegate, auditLog)

        secretStore.storeSecret("myKey", "myVal", SecretCategory.ENCRYPTION_KEY)
        assertEquals("myVal", secretStore.getSecret("myKey"))
        assertTrue(secretStore.hasSecret("myKey"))
        assertEquals(listOf("myKey"), secretStore.listSecretKeys())
        assertTrue(secretStore.deleteSecret("myKey"))

        // Audit log should have 5 entries: store, get, has, list, delete
        val entries = auditLog.recent(10)
        assertEquals(5, entries.size)
        assertEquals("secret_store", entries[0].event)
        assertEquals("secret_get", entries[1].event)
        assertEquals("secret_has", entries[2].event)
        assertEquals("secret_list", entries[3].event)
        assertEquals("secret_delete", entries[4].event)
    }

    @Test
    fun `clear resets store`() = runTest {
        val store = InMemorySecretStore()
        store.storeSecret("key", "val")
        store.clear()
        assertTrue(store.auditLog.isEmpty())
        assertFalse(store.hasSecret("key"))
    }

    @Test
    fun `audit log records correct categories`() = runTest {
        val store = InMemorySecretStore()
        store.storeSecret("llm", "sk-ant-123", SecretCategory.LLM_API_KEY)
        store.storeSecret("chan", "xoxb-123", SecretCategory.CHANNEL_TOKEN)
        store.storeSecret("enc", "aes-key", SecretCategory.ENCRYPTION_KEY)

        val log = store.auditLog
        assertEquals(3, log.size)
        assertEquals(SecretCategory.LLM_API_KEY, log[0].category)
        assertEquals(SecretCategory.CHANNEL_TOKEN, log[1].category)
        assertEquals(SecretCategory.ENCRYPTION_KEY, log[2].category)
    }

    @Test
    fun `store multiple secrets and list all keys`() = runTest {
        val store = InMemorySecretStore()
        store.storeSecret("key1", "val1", SecretCategory.CUSTOM)
        store.storeSecret("key2", "val2", SecretCategory.SERVICE_ACCOUNT)
        store.storeSecret("key3", "val3", SecretCategory.WEBHOOK_SECRET)

        val keys = store.listSecretKeys()
        assertEquals(3, keys.size)
        assertTrue(keys.containsAll(listOf("key1", "key2", "key3")))
    }

    @Test
    fun `SecretStore delegates get returning null for missing key`() = runTest {
        val delegate = InMemorySecretStore()
        val auditLog = AuditLog()
        val secretStore = SecretStore(delegate, auditLog)

        assertNull(secretStore.getSecret("nonexistent"))
        assertFalse(secretStore.hasSecret("nonexistent"))

        val entries = auditLog.recent(10)
        assertEquals(2, entries.size)
        assertEquals("secret_get", entries[0].event)
        assertEquals("secret_has", entries[1].event)
    }

    @Test
    fun `SecretStore delete of nonexistent key returns false`() = runTest {
        val delegate = InMemorySecretStore()
        val auditLog = AuditLog()
        val secretStore = SecretStore(delegate, auditLog)

        assertFalse(secretStore.deleteSecret("missing"))
        assertEquals(1, auditLog.recent(10).size)
        assertEquals("secret_delete", auditLog.recent(10)[0].event)
    }

    @Test
    fun `SecretAccessRecord has correct timestamp`() = runTest {
        val before = System.currentTimeMillis()
        val store = InMemorySecretStore()
        store.storeSecret("key", "val")
        val after = System.currentTimeMillis()

        val record = store.auditLog.first()
        assertTrue(record.timestamp in before..after)
    }
}
