package dev.dreamingcodes.hcmcp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Worker URL + device token, encrypted at rest with an Android Keystore key. */
object Settings {
  private fun prefs(ctx: Context): SharedPreferences {
    val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    return EncryptedSharedPreferences.create(ctx, "bridge_settings", key,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
  }
  fun workerUrl(ctx: Context) = prefs(ctx).getString("worker_url", "") ?: ""
  fun deviceToken(ctx: Context) = prefs(ctx).getString("device_token", "") ?: ""
  fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
  fun save(ctx: Context, url: String, token: String) =
    prefs(ctx).edit().putString("worker_url", url.trim().trimEnd('/')).putString("device_token", token.trim()).apply()
  fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("enabled", on).apply()
}
