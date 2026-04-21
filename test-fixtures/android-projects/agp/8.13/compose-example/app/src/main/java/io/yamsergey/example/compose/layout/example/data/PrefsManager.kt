package io.yamsergey.example.compose.layout.example.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object PrefsManager {

    private const val PLAIN_PREFS = "app_settings"
    private const val ENCRYPTED_PREFS = "secure_data"

    fun getPlainPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE)

    fun getEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun seedDefaults(context: Context) {
        val plain = getPlainPrefs(context)
        if (!plain.contains("theme")) {
            plain.edit()
                .putString("theme", "system")
                .putBoolean("notifications_enabled", true)
                .putInt("refresh_interval_seconds", 30)
                .putFloat("font_scale", 1.0f)
                .putLong("install_timestamp", System.currentTimeMillis())
                .putStringSet("favorite_tags", setOf("android", "kotlin", "compose"))
                .apply()
        }

        val encrypted = getEncryptedPrefs(context)
        if (!encrypted.contains("auth_token")) {
            encrypted.edit()
                .putString("auth_token", "eyJhbGciOiJIUzI1NiJ9.demo-token-for-testing")
                .putString("refresh_token", "rt_abc123def456_demo")
                .putString("user_email", "demo@example.com")
                .putInt("user_id", 42)
                .putBoolean("biometric_enabled", false)
                .apply()
        }
    }
}
