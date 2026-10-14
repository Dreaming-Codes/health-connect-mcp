package dev.dreamingcodes.hcmcp

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Foreground service (type specialUse: no daily runtime cap, unlike dataSync on Android 15+) holding the
 * WebSocket to the Worker's Durable Object, with reconnect + exponential backoff (1 s … 60 s).
 */
class BridgeService : Service() {
  companion object {
    private const val TAG = "HCBridge"
    private const val CHANNEL = "bridge"
    /** Returns null on success, or an error message. */
    fun start(ctx: Context): String? {
      val i = Intent(ctx, BridgeService::class.java)
      return try { ctx.startForegroundService(i); null }
      catch (e: Exception) { Log.w(TAG, "cannot start bridge: ${e.javaClass.simpleName}"); "Android refused to start the service (${e.javaClass.simpleName}). Open the app and try again." }
    }
    fun stop(ctx: Context) { ctx.stopService(Intent(ctx, BridgeService::class.java)); BridgeState.set { it.copy(phase = Phase.STOPPED, retryInSec = null, connectedSince = null) } }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
  private var ws: WebSocket? = null
  private var backoffMs = 1000L
  @Volatile private var stopped = false
  private var pingJob: Job? = null
  private val handler by lazy { HealthHandler(this) }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    getSystemService(NotificationManager::class.java)
      .createNotificationChannel(NotificationChannel(CHANNEL, "Health bridge", NotificationManager.IMPORTANCE_LOW))
    notify("Starting…") // promote to foreground ASAP
  }

  private fun notify(text: String) {
    val n = Notification.Builder(this, CHANNEL).setContentTitle("Health Connect bridge").setContentText(text)
      .setSmallIcon(R.drawable.ic_stat_bridge).setOngoing(true)
      .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()
    val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    startForeground(1, n, type)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    stopped = false
    if (ws == null) {
      notify("Connecting…")
      BridgeState.set { it.copy(phase = Phase.CONNECTING, retryInSec = null) }
      scope.launch {
        try { connect() } catch (e: Throwable) { scheduleReconnect("${e.javaClass.simpleName}: ${e.message}") }
      }
    }
    return START_STICKY
  }

  private fun connect() {
    val url = Settings.workerUrl(this); val token = Settings.deviceToken(this)
    if (url.isBlank() || token.isBlank()) {
      BridgeState.set { it.copy(phase = Phase.STOPPED, lastError = "Not configured: enter Worker URL and device token") }
      stopSelf(); return
    }
    Log.i(TAG, "connecting")
    val req = Request.Builder().url(url.replaceFirst(Regex("^http"), "ws") + "/phone/connect").header("Authorization", "Bearer $token").build()
    ws = http.newWebSocket(req, object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        backoffMs = 1000L; notify("Connected"); Log.i(TAG, "connected")
        BridgeState.set { it.copy(phase = Phase.CONNECTED, lastError = null, retryInSec = null, connectedSince = System.currentTimeMillis()) }
        webSocket.send(JSONObject().put("type", "hello").put("app_version", BuildConfig.VERSION_NAME)
          .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").toString())
        pingJob?.cancel()
        pingJob = scope.launch { while (isActive) { delay(30_000); webSocket.send("{\"type\":\"ping\"}") } }
      }
      override fun onMessage(webSocket: WebSocket, text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        if (msg.has("type")) return
        val id = msg.optString("id"); val method = msg.optString("method")
        if (id.isEmpty() || method.isEmpty()) return
        scope.launch {
          val reply = JSONObject().put("id", id)
          try { reply.put("result", handler.handle(method, msg.optJSONObject("params") ?: JSONObject())) }
          catch (e: Throwable) {
            val code = when (e) { is SecurityException -> "permission_denied"; is BadParams -> "bad_params"; is NoSuchMethodException -> "unknown_method"; else -> "internal" }
            reply.put("error", JSONObject().put("code", code).put("message", e.message ?: e.javaClass.simpleName))
          }
          Log.i(TAG, "handled $method") // method name only, never health values
          val now = System.currentTimeMillis()
          Settings.setLastRequest(this@BridgeService, now, method)
          BridgeState.set { it.copy(lastRequestAt = now, lastRequestMethod = method) }
          webSocket.send(reply.toString())
        }
      }
      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = scheduleReconnect("Closed by server ($code${if (reason.isNotBlank()) ": $reason" else ""})")
      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (response?.code == 401) {
          pingJob?.cancel(); ws = null; notify("Rejected: wrong device token")
          BridgeState.set { it.copy(phase = Phase.REJECTED, lastError = "Server rejected the device token (401). Check the token.", retryInSec = null) }
          return
        }
        scheduleReconnect(t.message ?: t.javaClass.simpleName)
      }
    })
  }

  private fun scheduleReconnect(why: String) {
    pingJob?.cancel(); ws = null
    if (stopped) return
    Log.w(TAG, "disconnected: $why")
    val wait = backoffMs; backoffMs = (backoffMs * 2).coerceAtMost(60_000)
    notify("Offline, retrying in ${wait / 1000}s")
    BridgeState.set { it.copy(phase = Phase.OFFLINE, lastError = why, retryInSec = wait / 1000, connectedSince = null) }
    scope.launch {
      delay(wait)
      if (!stopped && ws == null) { BridgeState.set { it.copy(phase = Phase.CONNECTING, retryInSec = null) }; try { connect() } catch (e: Throwable) { scheduleReconnect(e.message ?: "error") } }
    }
  }

  override fun onDestroy() {
    stopped = true
    ws?.close(1000, "service stopped"); ws = null
    scope.cancel()
    BridgeState.set { if (it.phase == Phase.REJECTED) it else it.copy(phase = Phase.STOPPED, retryInSec = null, connectedSince = null) }
    super.onDestroy()
  }
}
