package dev.dreamingcodes.hcmcp

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.WorkerThread
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "HCBridge"

data class Enrollment(val deviceId: String, val deviceToken: String, val linked: Boolean) {
  companion object {
    fun parse(o: JSONObject) = Enrollment(o.getString("device_id"), o.getString("device_token"), o.optBoolean("linked"))
  }
}

enum class CodeKind(val path: String) { Connector("/phone/connector-code"), Pairing("/phone/pairing-code") }

data class IssuedCode(val code: String, val expiresAt: String, val claimUrl: String?) {
  companion object {
    fun parse(o: JSONObject) = IssuedCode(o.getString("code"), o.getString("expires_at"), o.optString("claim_url").ifBlank { null })
  }
}

class PhoneFailure(val code: String, message: String) : Exception(message)

object WorkerApi {
  private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).writeTimeout(8, TimeUnit.SECONDS).build()
  private val JSON = "application/json".toMediaType()

  fun post(ctx: Context, path: String, body: JSONObject): Pair<Int, String> {
    val url = Settings.workerUrl(ctx); val token = Settings.deviceToken(ctx)
    require(url.isNotBlank() && token.isNotBlank()) { "Not configured" }
    return send(url, path, body, token)
  }

  private fun send(url: String, path: String, body: JSONObject, token: String?): Pair<Int, String> {
    val req = Request.Builder().url(url + path).apply { token?.let { header("Authorization", "Bearer $it") } }.post(body.toString().toRequestBody(JSON)).build()
    http.newCall(req).execute().use { return it.code to (it.body?.string() ?: "") }
  }

  private fun errorOf(code: Int, body: String) = runCatching { JSONObject(body).getString("error") }.getOrNull() ?: "HTTP $code"

  fun enroll(url: String, name: String, pairingCode: String?): Enrollment {
    val body = JSONObject().put("name", name).apply { pairingCode?.let { put("pairing_code", it) } }
    val (code, text) = send(url, "/enroll", body, null)
    if (code != 200) throw IllegalStateException(errorOf(code, text))
    return Enrollment.parse(JSONObject(text))
  }

  fun code(ctx: Context, kind: CodeKind): IssuedCode {
    val (code, text) = post(ctx, kind.path, JSONObject())
    if (code != 200) throw IllegalStateException(errorOf(code, text))
    return IssuedCode.parse(JSONObject(text))
  }

  fun rename(ctx: Context, name: String) {
    val (code, text) = post(ctx, "/phone/name", JSONObject().put("name", name))
    if (code != 200) throw IllegalStateException(errorOf(code, text))
  }

  @WorkerThread
  fun register(ctx: Context, fcmToken: String? = null): String? {
    return try {
      val tok = fcmToken ?: Tasks.await(FirebaseMessaging.getInstance().token, 20, TimeUnit.SECONDS)
      val (code, _) = post(ctx, "/phone/register", JSONObject().put("fcm_token", tok).put("app_version", BuildConfig.VERSION_NAME)
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("mode", "push"))
      when (code) {
        200 -> { val now = System.currentTimeMillis(); Settings.setPush(ctx, now, null); BridgeState.set { it.copy(pushRegisteredAt = now, pushError = null) }; Log.i(TAG, "push registered"); null }
        401 -> "Server rejected the device token (401)"
        else -> "Register failed: HTTP $code"
      }
    } catch (e: Exception) { e.message ?: e.javaClass.simpleName }.also { err ->
      if (err != null) { Settings.setPush(ctx, Settings.pushRegisteredAt(ctx), err); BridgeState.set { it.copy(pushError = err) }; Log.w(TAG, "push register failed: $err") }
    }
  }
}

suspend fun dispatch(ctx: Context, method: String, params: JSONObject): Any {
  val groups = listOf(NotificationHandler(ctx), BotNotifications(ctx), CallHandler(ctx), LocationHandler(ctx), MessagesHandler(ctx), ContactsHandler(ctx))
  return groups.firstOrNull { method in it.methods }?.handle(method, params) ?: HealthHandler(ctx).handle(method, params)
}

class PushService : FirebaseMessagingService() {
  override fun onNewToken(token: String) {
    if (Settings.isConfigured(this)) WorkerApi.register(this, token)
  }

  override fun onMessageReceived(msg: RemoteMessage) {
    val d = msg.data
    val id = d["id"] ?: return
    val method = d["method"] ?: return
    val reply = JSONObject()
    // High-priority messages get about 20 s of execution; leave headroom to post the reply.
    runBlocking {
      try {
        withTimeout(15_000) { reply.put("result", dispatch(this@PushService, method, JSONObject(d["params"] ?: "{}"))) }
      } catch (e: Throwable) {
        val code = when (e) { is PhoneFailure -> e.code; is SecurityException -> "permission_denied"; is BadParams -> "bad_params"; is NoSuchMethodException -> "unknown_method"
          is kotlinx.coroutines.TimeoutCancellationException -> "timeout"; else -> "internal" }
        reply.put("error", JSONObject().put("code", code).put("message", e.message ?: e.javaClass.simpleName))
      }
    }
    try {
      val (code, _) = WorkerApi.post(this, "/phone/result/$id", reply)
      Log.i(TAG, "push handled $method -> HTTP $code")
    } catch (e: Exception) { Log.w(TAG, "result post failed: ${e.javaClass.simpleName}") }
    val now = System.currentTimeMillis()
    Settings.setLastRequest(this, now, method)
    BridgeState.set { it.copy(lastRequestAt = now, lastRequestMethod = method) }
  }

}
