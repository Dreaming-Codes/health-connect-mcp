package dev.dreamingcodes.hcmcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

class DebugNotificationReceiver : BroadcastReceiver() {
  override fun onReceive(ctx: Context, intent: Intent) {
    when (intent.action) {
      "dev.dreamingcodes.hcmcp.DEBUG_POST" -> post(ctx, intent.getStringExtra("tag") ?: "test")
      "dev.dreamingcodes.hcmcp.DEBUG_CALL" -> call(ctx, intent.getStringExtra("method")!!, intent.getStringExtra("params") ?: "{}")
      "dev.dreamingcodes.hcmcp.DEBUG_REPLY" -> File(ctx.filesDir, "debug_reply.txt").writeText(
        RemoteInput.getResultsFromIntent(intent)?.getCharSequence("reply_text")?.toString() ?: "<none>")
      "dev.dreamingcodes.hcmcp.DEBUG_MARK" -> File(ctx.filesDir, "debug_reply.txt").writeText("mark_read")
    }
  }

  private fun call(ctx: Context, method: String, params: String) {
    val pending = goAsync()
    Thread {
      val out = try {
        JSONObject().put("result", runBlocking { dispatch(ctx, method, JSONObject(params)) })
      } catch (e: Throwable) {
        JSONObject().put("error", JSONObject().put("code", (e as? PhoneFailure)?.code ?: e.javaClass.simpleName).put("message", e.message))
      }
      File(ctx.filesDir, "debug_result.json").writeText(out.toString(1))
      pending.finish()
    }.start()
  }

  private fun post(ctx: Context, tag: String) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("debug", "Debug", NotificationManager.IMPORTANCE_DEFAULT))
    fun pi(action: String, code: Int, mutable: Boolean) = PendingIntent.getBroadcast(ctx, code,
      Intent(action).setClass(ctx, DebugNotificationReceiver::class.java),
      if (mutable) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val reply = Notification.Action.Builder(null, "Reply", pi("dev.dreamingcodes.hcmcp.DEBUG_REPLY", 1, true))
      .addRemoteInput(RemoteInput.Builder("reply_text").setLabel("Reply").build()).build()
    val mark = Notification.Action.Builder(null, "Mark read", pi("dev.dreamingcodes.hcmcp.DEBUG_MARK", 2, false)).build()
    nm.notify(tag, 1, Notification.Builder(ctx, "debug").setSmallIcon(R.drawable.ic_stat_bridge)
      .setContentTitle("Test title").setContentText("Test body").setCategory(Notification.CATEGORY_MESSAGE)
      .addAction(reply).addAction(mark).build())
    if (tag == "ongoing") nm.notify("ongoing", 2, Notification.Builder(ctx, "debug").setSmallIcon(R.drawable.ic_stat_bridge)
      .setContentTitle("Ongoing").setOngoing(true).build())
  }
}
