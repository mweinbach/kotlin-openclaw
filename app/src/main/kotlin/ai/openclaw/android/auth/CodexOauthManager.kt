package ai.openclaw.android.auth

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Handles Codex OAuth authorization URL generation and token exchange using PKCE.
 */
class CodexOauthManager(context: Context) {
    data class CodexOauthSession(
        val accessToken: String,
        val refreshToken: String?,
        val idToken: String?,
        val expiresAtMs: Long?,
        val accountId: String?,
        val email: String?,
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun isOauthRedirect(uri: Uri): Boolean {
        return uri.scheme == REDIRECT_SCHEME &&
            uri.host == REDIRECT_HOST &&
            uri.path == REDIRECT_PATH
    }

    fun buildAuthorizationUrl(originator: String = "openclaw-android"): String {
        val codeVerifier = generatePkceVerifier()
        val codeChallenge = generatePkceChallenge(codeVerifier)
        val state = generateOauthState()
        val createdAt = System.currentTimeMillis()

        prefs.edit()
            .putString(KEY_PENDING_STATE, state)
            .putString(KEY_PENDING_CODE_VERIFIER, codeVerifier)
            .putLong(KEY_PENDING_CREATED_AT_MS, createdAt)
            .apply()

        return Uri.parse("$ISSUER/oauth/authorize")
            .buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", redirectUri())
            .appendQueryParameter("scope", "openid profile email offline_access")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("id_token_add_organizations", "true")
            .appendQueryParameter("codex_cli_simplified_flow", "true")
            .appendQueryParameter("state", state)
            .appendQueryParameter("originator", originator)
            .build()
            .toString()
    }

    suspend fun exchangeFromRedirect(uri: Uri): CodexOauthSession {
        return withContext(Dispatchers.IO) {
            val error = uri.getQueryParameter("error")
            if (!error.isNullOrBlank()) {
                val errorDescription = uri.getQueryParameter("error_description")
                throw IllegalStateException(errorDescription ?: error)
            }

            val pendingState = prefs.getString(KEY_PENDING_STATE, null)
                ?: throw IllegalStateException("OAuth state is missing. Start login again.")
            val codeVerifier = prefs.getString(KEY_PENDING_CODE_VERIFIER, null)
                ?: throw IllegalStateException("OAuth code verifier is missing. Start login again.")
            val createdAt = prefs.getLong(KEY_PENDING_CREATED_AT_MS, 0L)
            if (createdAt > 0L && System.currentTimeMillis() - createdAt > MAX_PENDING_AGE_MS) {
                clearPending()
                throw IllegalStateException("OAuth session expired. Start login again.")
            }

            val receivedState = uri.getQueryParameter("state")
            if (receivedState.isNullOrBlank() || receivedState != pendingState) {
                throw IllegalStateException("Invalid OAuth state.")
            }

            val code = uri.getQueryParameter("code")
                ?: throw IllegalStateException("Authorization code missing from callback.")

            val session = exchangeAuthorizationCode(code = code, codeVerifier = codeVerifier)
            clearPending()
            session
        }
    }

    suspend fun refreshFromRefreshToken(refreshToken: String): CodexOauthSession {
        return withContext(Dispatchers.IO) {
            val body = "grant_type=refresh_token" +
                "&refresh_token=${Uri.encode(refreshToken)}" +
                "&client_id=${Uri.encode(CLIENT_ID)}"
            val response = postForm("$ISSUER/oauth/token", body)
            parseTokenResponse(response)
        }
    }

    private fun exchangeAuthorizationCode(code: String, codeVerifier: String): CodexOauthSession {
        val body = "grant_type=authorization_code" +
            "&code=${Uri.encode(code)}" +
            "&redirect_uri=${Uri.encode(redirectUri())}" +
            "&client_id=${Uri.encode(CLIENT_ID)}" +
            "&code_verifier=${Uri.encode(codeVerifier)}"
        val response = postForm("$ISSUER/oauth/token", body)
        return parseTokenResponse(response)
    }

    private fun postForm(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        conn.doOutput = true
        conn.outputStream.use { out ->
            out.write(body.toByteArray(Charsets.UTF_8))
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("Token exchange failed ($code): ${text.take(400)}")
        }
        return text
    }

    private fun parseTokenResponse(raw: String): CodexOauthSession {
        val obj = json.parseToJsonElement(raw).jsonObject
        val accessToken = obj["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("Token response missing access_token.")
        val refreshToken = obj["refresh_token"]?.jsonPrimitive?.contentOrNull
        val idToken = obj["id_token"]?.jsonPrimitive?.contentOrNull
        val expiresInSec = obj["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val expiresAtMs = expiresInSec?.let { System.currentTimeMillis() + (it * 1000L) }
            ?: extractJwtExpiryMs(accessToken)

        val claims = decodeJwtPayload(idToken) ?: decodeJwtPayload(accessToken)
        val accountId = claims?.let(::extractAccountIdFromClaims)
        val email = claims?.let(::extractEmailFromClaims)

        return CodexOauthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = idToken,
            expiresAtMs = expiresAtMs,
            accountId = accountId,
            email = email,
        )
    }

    private fun clearPending() {
        prefs.edit()
            .remove(KEY_PENDING_STATE)
            .remove(KEY_PENDING_CODE_VERIFIER)
            .remove(KEY_PENDING_CREATED_AT_MS)
            .apply()
    }

    private fun generatePkceVerifier(): String {
        val bytes = ByteArray(64)
        random.nextBytes(bytes)
        return base64UrlNoPadding(bytes)
    }

    private fun generatePkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        return base64UrlNoPadding(digest)
    }

    private fun generateOauthState(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return base64UrlNoPadding(bytes)
    }

    private fun base64UrlNoPadding(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun redirectUri(): String = "$REDIRECT_SCHEME://$REDIRECT_HOST$REDIRECT_PATH"

    private fun decodeJwtPayload(token: String?): JsonObject? {
        if (token.isNullOrBlank()) return null
        val parts = token.split(".")
        if (parts.size < 2) return null
        val payload = parts[1]
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        return runCatching {
            val raw = Base64.getUrlDecoder().decode(padded)
            json.parseToJsonElement(raw.decodeToString()).jsonObject
        }.getOrNull()
    }

    private fun extractAccountIdFromClaims(claims: JsonObject): String? {
        claims["chatgpt_account_id"]?.jsonPrimitive?.contentOrNull?.let { return it }
        claims["organization_id"]?.jsonPrimitive?.contentOrNull?.let { return it }
        claims["account_id"]?.jsonPrimitive?.contentOrNull?.let { return it }

        val authNamespace = claims["https://api.openai.com/auth"]?.jsonObject
        authNamespace?.get("chatgpt_account_id")?.jsonPrimitive?.contentOrNull?.let { return it }

        val organizations = claims["organizations"]?.jsonArray.orEmpty()
        for (entry in organizations) {
            entry.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.let { return it }
        }

        return null
    }

    private fun extractEmailFromClaims(claims: JsonObject): String? {
        claims["email"]?.jsonPrimitive?.contentOrNull?.let { return it }
        val profileNamespace = claims["https://api.openai.com/profile"]?.jsonObject
        return profileNamespace?.get("email")?.jsonPrimitive?.contentOrNull
    }

    private fun extractJwtExpiryMs(accessToken: String): Long? {
        val claims = decodeJwtPayload(accessToken) ?: return null
        return claims["exp"]?.jsonPrimitive?.contentOrNull
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.times(1000L)
    }

    companion object {
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val ISSUER = "https://auth.openai.com"

        private const val PREFS_NAME = "openclaw_oauth"
        private const val KEY_PENDING_STATE = "codex_pending_state"
        private const val KEY_PENDING_CODE_VERIFIER = "codex_pending_code_verifier"
        private const val KEY_PENDING_CREATED_AT_MS = "codex_pending_created_at_ms"
        private const val MAX_PENDING_AGE_MS = 15 * 60 * 1000L

        private const val REDIRECT_SCHEME = "ai.openclaw.android"
        private const val REDIRECT_HOST = "oauth"
        private const val REDIRECT_PATH = "/callback"

        private val random = SecureRandom()
    }
}
