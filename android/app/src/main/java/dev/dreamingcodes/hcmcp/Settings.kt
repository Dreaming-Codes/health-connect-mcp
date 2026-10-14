package dev.dreamingcodes.hcmcp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Worker URL + device token, encrypted at rest with an Android Keystore key. */
object Settings {
  const val DEFAULT_URL = "https://health-connect-mcp.dreamingcodes.workers.dev"
  @Volatile private var cached: SharedPreferences? = null
  private fun prefs(ctx: Context): SharedPreferences = cached ?: synchronized(this) {
    cached ?: run {
      val key = MasterKey.Builder(ctx.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
      EncryptedSharedPreferences.create(ctx.applicationContext, "bridge_settings", key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM).also { cached = it }
    }
  }
  private fun plain(ctx: Context) = ctx.getSharedPreferences("bridge_meta", Context.MODE_PRIVATE)
  fun workerUrl(ctx: Context) = prefs(ctx).getString("worker_url", "") ?: ""
  fun deviceToken(ctx: Context) = prefs(ctx).getString("device_token", "") ?: ""
  fun isConfigured(ctx: Context) = workerUrl(ctx).isNotBlank() && deviceToken(ctx).isNotBlank()
  fun enabled(ctx: Context) = plain(ctx).getBoolean("enabled", false)
  fun save(ctx: Context, url: String, token: String) =
    prefs(ctx).edit().putString("worker_url", url.trim().trimEnd('/')).putString("device_token", token.trim()).commit()
  fun setEnabled(ctx: Context, on: Boolean) = plain(ctx).edit().putBoolean("enabled", on).apply()
  fun setLastRequest(ctx: Context, at: Long, method: String) = plain(ctx).edit().putLong("last_req_at", at).putString("last_req_method", method).apply()
  fun lastRequest(ctx: Context): Pair<Long, String>? = plain(ctx).getLong("last_req_at", 0L).takeIf { it > 0 }?.let { it to (plain(ctx).getString("last_req_method", "") ?: "") }
}
