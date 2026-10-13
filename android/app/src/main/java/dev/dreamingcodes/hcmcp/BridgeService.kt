package dev.dreamingcodes.hcmcp

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Foreground service holding the WebSocket to the Worker's Durable Object, with reconnect + exponential backoff. */
class BridgeService : Service() {
  companion object {
    private const val TAG = "HCBridge"
    private const val CHANNEL = "bridge"
    @Volatile var state: String = "stopped"; private set
    fun start(ctx: Context) {
      val i = Intent(ctx, BridgeService::class.java)
      try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
      catch (e: Exception) { Log.w(TAG, "cannot start bridge now: ${e.javaClass.simpleName}") }
    }
    fun stop(ctx: Context) = ctx.stopService(Intent(ctx, BridgeService::class.java))
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
  private var ws: WebSocket? = null
  private var backoffMs = 1000L
  private var stopped = false
  private var pingJob: Job? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private val handler by lazy { HealthHandler(this) }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Health bridge", NotificationManager.IMPORTANCE_LOW))
    notify("starting…") // promote to foreground ASAP
  }

  private fun notify(text: String) {
    state = text
    val n = Notification.Builder(this, CHANNEL).setContentTitle("Health Connect bridge").setContentText(text)
      .setSmallIcon(android.R.drawable.ic_menu_compass).setOngoing(true)
      .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()
    if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, n)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    notify("connecting…")
    stopped = false
    Log.i(TAG, "onStartCommand ws=${ws != null}")
    if (ws == null) scope.launch { // keystore/prefs access off the main thread
      try { connect() } catch (e: Throwable) { Log.e(TAG, "connect failed: ${e.javaClass.simpleName}: ${e.message}"); scheduleReconnect("connect failed") }
    }
    return START_STICKY
  }

  private fun connect() {
    val url = Settings.workerUrl(this); val token = Settings.deviceToken(this)
    if (url.isBlank() || token.isBlank()) { notify("not configured"); return }
    Log.i(TAG, "connecting")
    val wsUrl = url.replaceFirst(Regex("^http"), "ws") + "/phone/connect"
    val req = Request.Builder().url(wsUrl).header("Authorization", "Bearer $token").build()
    ws = http.newWebSocket(req, object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        backoffMs = 1000L; notify("connected"); Log.i(TAG, "connected")
        webSocket.send(JSONObject().put("type", "hello").put("app_version", BuildConfig.VERSION_NAME)
          .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").toString())
        pingJob?.cancel()
        pingJob = scope.launch { while (isActive) { delay(30_000); webSocket.send("{\"type\":\"ping\"}") } }
      }
      override fun onMessage(webSocket: WebSocket, text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        if (msg.has("type")) return // pong etc.
        val id = msg.optString("id"); val method = msg.optString("method")
        if (id.isEmpty() || method.isEmpty()) return
        scope.launch {
          val reply = JSONObject().put("id", id)
          try {
            reply.put("result", handler.handle(method, msg.optJSONObject("params") ?: JSONObject()))
          } catch (e: Throwable) {
            val code = when (e) { is SecurityException -> "permission_denied"; is BadParams -> "bad_params"; is NoSuchMethodException -> "unknown_method"; else -> "internal" }
            reply.put("error", JSONObject().put("code", code).put("message", e.message ?: e.javaClass.simpleName))
          }
          Log.i(TAG, "handled $method") // method name only — never health values
          webSocket.send(reply.toString())
        }
      }
      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = scheduleReconnect("closed $code")
      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (response?.code == 401) { notify("rejected: bad device token"); ws = null; return }
        scheduleReconnect("error: ${t.javaClass.simpleName}: ${t.message}")
      }
    })
  }

  private fun scheduleReconnect(why: String) {
    Log.w(TAG, "disconnected: $why")
    pingJob?.cancel(); ws = null
    if (stopped) return
    val wait = backoffMs; backoffMs = (backoffMs * 2).coerceAtMost(60_000)
    notify("reconnecting in ${wait / 1000}s ($why)")
    scope.launch { delay(wait); if (!stopped && ws == null) connect() }
  }

  override fun onDestroy() {
    stopped = true; state = "stopped"
    ws?.close(1000, "service stopped"); ws = null
    scope.cancel(); wakeLock?.release()
    super.onDestroy()
  }
}
