package dev.dreamingcodes.hcmcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.WorkerThread
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

const val TAG = "AndroidConnect"

data class Enrollment(val deviceId: String, val deviceToken: String) {
  companion object {
    fun parse(o: JSONObject) = Enrollment(o.getString("device_id"), o.getString("device_token"))
  }
}

data class IssuedCode(val code: String, val claimUrl: String) {
  companion object {
    fun parse(o: JSONObject) = IssuedCode(o.getString("code"), o.getString("claim_url"))
  }
}

open class PhoneFailure(val code: String, message: String) : Exception(message)

class BadParams(message: String) : PhoneFailure("bad_params", message)

fun errorJson(e: Throwable): JSONObject {
  val code = when (e) { is PhoneFailure -> e.code; is SecurityException -> "permission_denied"; is TimeoutCancellationException -> "timeout"; else -> "internal" }
  return JSONObject().put("code", code).put("message", e.message ?: e.javaClass.simpleName)
}

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

  private fun Pair<Int, String>.okJson(): JSONObject {
    val (code, text) = this
    if (code != 200) throw IllegalStateException(runCatching { JSONObject(text).getString("error") }.getOrNull() ?: "HTTP $code")
    return JSONObject(text)
  }

  fun enroll(url: String, name: String) = Enrollment.parse(send(url, "/enroll", JSONObject().put("name", name), null).okJson())

  fun connectorCode(ctx: Context) = IssuedCode.parse(post(ctx, "/phone/connector-code", JSONObject()).okJson())

  fun rename(ctx: Context, name: String) {
    post(ctx, "/phone/name", JSONObject().put("name", name)).okJson()
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
  val groups = listOf(HealthHandler(ctx), NotificationHandler(ctx), BotNotifications(ctx), CallHandler(ctx), LocationHandler(ctx), MessagesHandler(ctx), ContactsHandler(ctx))
  val run = groups.firstNotNullOfOrNull { it.methods[method] } ?: throw PhoneFailure("unknown_method", method)
  return run(params)
}

// FirebaseMessagingService.onMessageReceived must return within 20 s; the rest of the window is for posting the result.
private const val DISPATCH_BUDGET_MS = 15_000L

class PushService : FirebaseMessagingService() {
  override fun onNewToken(token: String) {
    if (Settings.isConfigured(this)) WorkerApi.register(this, token)
  }

  override fun onMessageReceived(msg: RemoteMessage) {
    val d = msg.data
    val id = d["id"] ?: return
    val method = d["method"] ?: return
    val reply = JSONObject()
    runBlocking {
      try {
        withTimeout(DISPATCH_BUDGET_MS) { reply.put("result", dispatch(this@PushService, method, JSONObject(d["params"] ?: "{}"))) }
      } catch (e: Throwable) {
        reply.put("error", errorJson(e))
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

class AppUpdatedReceiver : BroadcastReceiver() {
  override fun onReceive(ctx: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED || !Settings.isConfigured(ctx)) return
    val pending = goAsync()
    Thread { try { WorkerApi.register(ctx) } finally { pending.finish() } }.start()
  }
}
