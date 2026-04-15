package com.vigil5.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vigil5.app.core.ApiSwitchboard

/**
 * Process-level Application entry point.
 *
 * Responsibilities:
 *  - Hold the singleton ApiSwitchboard (wired once, reused by every agent).
 *  - Provide encrypted storage for the Anthropic API key so it is not shipped
 *    in the APK or written to plain preferences.
 *  - Expose a tiny accessor for agents that need the switchboard without
 *    plumbing through DI frameworks.
 */
class VigilApp : Application() {

    @Volatile
    var switchboard: ApiSwitchboard? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        rebuildSwitchboard()
    }

    /** Called from onboarding UI after the user pastes their key. */
    fun saveApiKey(key: String) {
        securePrefs().edit().putString(PREF_ANTHROPIC_KEY, key.trim()).apply()
        rebuildSwitchboard()
    }

    fun clearApiKey() {
        securePrefs().edit().remove(PREF_ANTHROPIC_KEY).apply()
        switchboard = null
    }

    fun hasApiKey(): Boolean = !loadApiKey().isNullOrBlank()

    private fun rebuildSwitchboard() {
        val key = loadApiKey()
        switchboard = if (!key.isNullOrBlank()) ApiSwitchboard(key) else null
    }

    private fun loadApiKey(): String? {
        // 1. User-provided key from encrypted prefs.
        securePrefs().getString(PREF_ANTHROPIC_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        // 2. Build-time injected key (dev convenience; see gradle.properties).
        return BuildConfig.ANTHROPIC_API_KEY.takeIf { it.isNotBlank() }
    }

    private fun securePrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            this,
            SECURE_PREF_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private const val SECURE_PREF_FILE = "vigil5_secure"
        private const val PREF_ANTHROPIC_KEY = "anthropic_api_key"

        @Volatile
        private var instance: VigilApp? = null

        fun get(context: Context): VigilApp =
            instance ?: (context.applicationContext as VigilApp).also { instance = it }
    }
}
