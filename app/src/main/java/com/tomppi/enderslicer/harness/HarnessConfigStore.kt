package com.tomppi.enderslicer.harness

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the harness lives, and the launch token that proves we may talk to it. */
data class HarnessConfig(
    val baseUrl: String = "",
    val token: String = "",
    /**
     * Working directory for sessions this app creates, on the harness host.
     *
     * This decides which skills the agent can see: skills are discovered from
     * the workspace, so a session rooted elsewhere cannot load them however
     * clearly the prompt names them. Left empty, the harness picks its own
     * default and the app's skills are invisible.
     */
    val workspace: String = "",
    /**
     * Session this app talks in.
     *
     * Remembered because sessions are cheap to create and expensive to lose:
     * creating one per launch left the previous conversation orphaned on the
     * harness and started the user from an empty chat every time.
     */
    val sessionId: String = "",
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    /**
     * Folds a freshly parsed address into this configuration.
     *
     * The address field holds a bare URL, and a bare URL parses to an *empty*
     * token - so saving the parsed value over the stored one silently drops the
     * credential the harness handed over from `auth.json`, and the next connect
     * has to bootstrap again. Only a token the user actually pasted replaces
     * the stored one.
     */
    fun mergedWith(parsed: HarnessConfig, workspace: String, sessionId: String): HarnessConfig = copy(
        baseUrl = parsed.baseUrl.ifBlank { baseUrl },
        token = parsed.token.ifBlank { token },
        workspace = workspace,
        sessionId = sessionId,
    )

    companion object {
        /**
         * Splits what the user pastes into an address and a token.
         *
         * The harness prints a URL carrying its launch token as `?token=…`, so
         * the natural thing to paste is that whole URL. A bare address is also
         * accepted, for a harness already authenticated by other means, and any
         * fragment is dropped because it never identifies the server.
         */
        fun parseLaunchUrl(pasted: String): HarnessConfig {
            val withoutFragment = pasted.trim().substringBefore('#')
            if (withoutFragment.isEmpty()) return HarnessConfig()
            val token = withoutFragment.substringAfter("token=", "").substringBefore('&')
            return HarnessConfig(
                baseUrl = withoutFragment.substringBefore('?').trimEnd('/'),
                token = token,
            )
        }
    }
}

/**
 * Persists the harness address and its launch token.
 *
 * The token is a bearer credential - anyone holding it can drive the harness -
 * so it is encrypted with an Android Keystore key rather than written to
 * preferences in the clear, matching how the OctoPrint API key is handled.
 */
class HarnessConfigStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): HarnessConfig = HarnessConfig(
        baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty(),
        token = loadEncrypted(KEY_ENCRYPTED_TOKEN),
        workspace = preferences.getString(KEY_WORKSPACE, "").orEmpty(),
        sessionId = preferences.getString(KEY_SESSION_ID, "").orEmpty(),
    )

    fun save(config: HarnessConfig) {
        check(
            preferences.edit()
                .putString(KEY_BASE_URL, config.baseUrl.trimEnd('/'))
                .putString(KEY_ENCRYPTED_TOKEN, encrypt(config.token))
                .putString(KEY_WORKSPACE, config.workspace.trim())
                .putString(KEY_SESSION_ID, config.sessionId)
                .commit(),
        ) { "Unable to persist the harness configuration" }
    }

    fun clear() {
        check(preferences.edit().clear().commit()) { "Unable to clear the harness configuration" }
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun loadEncrypted(key: String): String {
        val encoded = preferences.getString(key, null) ?: return ""
        return runCatching { decrypt(encoded) }
            .onFailure { preferences.edit().remove(key).commit() }
            .getOrDefault("")
            .takeIf(String::isNotBlank)
            .orEmpty()
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(encrypted)
            .array()
        return FORMAT_PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        require(value.startsWith(FORMAT_PREFIX)) { "Unsupported credential format" }
        val payload = Base64.decode(value.removePrefix(FORMAT_PREFIX), Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(payload)
        val ivSize = buffer.get().toInt() and 0xff
        require(ivSize in 12..32 && buffer.remaining() > ivSize) { "Corrupt encrypted credential" }
        val iv = ByteArray(ivSize).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFERENCES_NAME = "harness_config"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_WORKSPACE = "workspace"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_ENCRYPTED_TOKEN = "encrypted_token"
        private const val KEY_ALIAS = "enderslicer_harness_token"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_PREFIX = "v1:"
    }
}
