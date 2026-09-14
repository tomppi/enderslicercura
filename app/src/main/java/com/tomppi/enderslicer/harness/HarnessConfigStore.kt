package com.tomppi.enderslicer.harness

import android.content.Context
import java.security.KeyStore

/**
 * Where the harness lives, and what this app keeps there.
 *
 * The launch token the harness prints in its URL is deliberately not part of
 * this: every request authenticates from the harness's own `auth.json`, so a
 * token would be a bearer credential held for a reader that does not exist.
 */
data class HarnessConfig(
    val baseUrl: String = "",
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
     * The address field holds a bare URL, so a pasted value that parses to an
     * empty address - the field cleared, or a URL that was not an address at
     * all - must not overwrite the stored one the chat is still using. The
     * workspace and session always come from the caller, which is the code that
     * just connected.
     */
    fun mergedWith(parsed: HarnessConfig, workspace: String, sessionId: String): HarnessConfig = copy(
        baseUrl = parsed.baseUrl.ifBlank { baseUrl },
        workspace = workspace,
        sessionId = sessionId,
    )

    companion object {
        /**
         * Splits what the user pastes into an address.
         *
         * The harness prints a URL carrying its launch token as `?token=…`, so
         * the natural thing to paste is that whole URL. Everything from the
         * query onwards is dropped - the token included, because no request
         * path reads it - and so is any fragment, which never identifies the
         * server either.
         */
        fun parseLaunchUrl(pasted: String): HarnessConfig {
            val withoutFragment = pasted.trim().substringBefore('#')
            if (withoutFragment.isEmpty()) return HarnessConfig()
            return HarnessConfig(baseUrl = withoutFragment.substringBefore('?').trimEnd('/'))
        }
    }
}

/**
 * Persists where the harness lives, and which conversations to resume.
 *
 * Nothing secret is written here any more. The launch token used to be
 * encrypted with a device-bound Keystore key, but no request path ever read it
 * back, so [save] drops what an earlier build left behind instead of carrying a
 * credential forward.
 */
class HarnessConfigStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): HarnessConfig = HarnessConfig(
        baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty(),
        workspace = preferences.getString(KEY_WORKSPACE, "").orEmpty(),
        sessionId = preferences.getString(KEY_SESSION_ID, "").orEmpty(),
    )

    fun save(config: HarnessConfig) {
        check(
            preferences.edit()
                .putString(KEY_BASE_URL, config.baseUrl.trimEnd('/'))
                .putString(KEY_WORKSPACE, config.workspace.trim())
                .putString(KEY_SESSION_ID, config.sessionId)
                .remove(KEY_LEGACY_ENCRYPTED_TOKEN)
                .commit(),
        ) { "Unable to persist the harness configuration" }
        deleteLegacyTokenKey()
    }

    /**
     * Session id for the modelling conversation.
     *
     * Kept apart from [HarnessConfig.sessionId] because the two chats are two
     * conversations with two agents: modelling a part from scratch and turning
     * a photograph into one share nothing but the harness they run on, and
     * letting them share a session meant the modelling agent inherited a
     * transcript about somebody's photograph.
     */
    fun loadModellingSession(): String =
        preferences.getString(KEY_MODELLING_SESSION_ID, "").orEmpty()

    fun saveModellingSession(sessionId: String) {
        check(
            preferences.edit().putString(KEY_MODELLING_SESSION_ID, sessionId).commit(),
        ) { "Unable to persist the modelling session" }
    }

    fun clear() {
        check(preferences.edit().clear().commit()) { "Unable to clear the harness configuration" }
        deleteLegacyTokenKey()
    }

    /**
     * Removes the Keystore key an earlier build encrypted the launch token with.
     *
     * Device-bound and unreadable from anywhere else, it now has nothing left to
     * decrypt; leaving it behind would keep a credential's key alive for no
     * reader. A device that never ran that build has no such entry, which
     * [deleteEntry] treats as a no-op.
     */
    private fun deleteLegacyTokenKey() {
        runCatching {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }.deleteEntry(LEGACY_TOKEN_KEY_ALIAS)
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "harness_config"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_WORKSPACE = "workspace"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_MODELLING_SESSION_ID = "modelling_session_id"
        /** Token ciphertext written by a build that stored the launch token; dropped by [save]. */
        private const val KEY_LEGACY_ENCRYPTED_TOKEN = "encrypted_token"
        private const val LEGACY_TOKEN_KEY_ALIAS = "enderslicer_harness_token"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}
