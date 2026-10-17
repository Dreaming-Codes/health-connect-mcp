package dev.dreamingcodes.hcmcp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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
  fun isConfigured(ctx: Context) = workerUrl(ctx).isNotBlank() && deviceToken(ctx).isNotBlank() && deviceId(ctx).isNotBlank()
  fun deviceId(ctx: Context) = prefs(ctx).getString("device_id", "") ?: ""
  fun deviceName(ctx: Context) = prefs(ctx).getString("device_name", "") ?: ""
  fun saveEnrollment(ctx: Context, url: String, e: Enrollment, name: String) = prefs(ctx).edit()
    .putString("worker_url", url.trim().trimEnd('/')).putString("device_token", e.deviceToken)
    .putString("device_id", e.deviceId).putString("device_name", name).commit()
  fun setDeviceName(ctx: Context, name: String) = prefs(ctx).edit().putString("device_name", name).commit()
  fun clear(ctx: Context) {
    prefs(ctx).edit().clear().commit()
    plain(ctx).edit().clear().commit()
  }
  fun setPush(ctx: Context, at: Long?, err: String?) = plain(ctx).edit().putLong("push_at", at ?: 0L).putString("push_err", err).apply()
  fun pushRegisteredAt(ctx: Context): Long? = plain(ctx).getLong("push_at", 0L).takeIf { it > 0 }
  fun pushError(ctx: Context): String? = plain(ctx).getString("push_err", null)
  fun setLastRequest(ctx: Context, at: Long, method: String) = plain(ctx).edit().putLong("last_req_at", at).putString("last_req_method", method).apply()
  fun lastRequest(ctx: Context): Pair<Long, String>? = plain(ctx).getLong("last_req_at", 0L).takeIf { it > 0 }?.let { it to (plain(ctx).getString("last_req_method", "") ?: "") }
}
