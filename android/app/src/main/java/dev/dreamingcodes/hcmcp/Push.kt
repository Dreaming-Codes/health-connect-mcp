package dev.dreamingcodes.hcmcp

import android.content.Context
import android.os.Build
import android.util.Log
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

/** HTTP calls from the phone to the Worker (all authenticated with the device token). */
object WorkerApi {
  private const val TAG = "HCBridge"
  private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).writeTimeout(8, TimeUnit.SECONDS).build()
  private val JSON = "application/json".toMediaType()

  fun post(ctx: Context, path: String, body: JSONObject): Pair<Int, String> {
    val url = Settings.workerUrl(ctx); val token = Settings.deviceToken(ctx)
    require(url.isNotBlank() && token.isNotBlank()) { "Not configured" }
    val req = Request.Builder().url(url + path).header("Authorization", "Bearer $token").post(body.toString().toRequestBody(JSON)).build()
    http.newCall(req).execute().use { return it.code to (it.body?.string() ?: "") }
  }

  /** Fetch the FCM token and register it with the Worker. Blocking: call off the main thread. */
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

/**
 * Receives high-priority FCM data messages {id, method, params} (no health data), runs the Health Connect
 * query in the background and POSTs the answer to /phone/result/<id>. No foreground service needed.
 */
class PushService : FirebaseMessagingService() {
  override fun onNewToken(token: String) {
    if (Settings.isConfigured(this)) WorkerApi.register(this, token)
  }

  override fun onMessageReceived(msg: RemoteMessage) {
    val d = msg.data
    val id = d["id"] ?: return
    val method = d["method"] ?: return
    val reply = JSONObject()
    // onMessageReceived runs on a background thread; high-priority messages get ~20 s.
    runBlocking {
      try {
        withTimeout(15_000) { reply.put("result", HealthHandler(this@PushService).handle(method, JSONObject(d["params"] ?: "{}"))) }
      } catch (e: Throwable) {
        val code = when (e) { is SecurityException -> "permission_denied"; is BadParams -> "bad_params"; is NoSuchMethodException -> "unknown_method"
          is kotlinx.coroutines.TimeoutCancellationException -> "timeout"; else -> "internal" }
        reply.put("error", JSONObject().put("code", code).put("message", e.message ?: e.javaClass.simpleName))
      }
    }
    try {
      val (code, _) = WorkerApi.post(this, "/phone/result/$id", reply)
      Log.i("HCBridge", "push handled $method -> HTTP $code") // method name only
    } catch (e: Exception) { Log.w("HCBridge", "result post failed: ${e.javaClass.simpleName}") }
    val now = System.currentTimeMillis()
    Settings.setLastRequest(this, now, method)
    BridgeState.set { it.copy(lastRequestAt = now, lastRequestMethod = method) }
  }
}
