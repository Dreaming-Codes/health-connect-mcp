package dev.dreamingcodes.hcmcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

private const val BOT_NOTIFICATION_ID = 7100
private const val REPLY_KEY = "reply"
private const val EXTRA_TAG = "bot_tag"
private const val EXTRA_ACTION = "bot_action"

enum class BotImportance(val level: Int) {
  min(NotificationManager.IMPORTANCE_MIN), low(NotificationManager.IMPORTANCE_LOW),
  default(NotificationManager.IMPORTANCE_DEFAULT), high(NotificationManager.IMPORTANCE_HIGH)
}

data class BotAction(val id: String, val label: String, val reply: Boolean, val placeholder: String?)

class BotNotifications(private val ctx: Context) : MethodGroup {
  override val methods = mapOf("send_notification" runs ::send)
  private val nm = ctx.getSystemService(NotificationManager::class.java)

  private fun send(p: JSONObject): JSONObject {
    Requirement.PostNotifications.check(ctx)
    val title = p.optString("title").ifEmpty { throw BadParams("title required") }
    val tag = p.optString("tag").ifEmpty { "n" + UUID.randomUUID().toString().take(8) }
    val alarm = p.optBoolean("alarm", false)
    val channel = if (alarm) alarmChannel(p.optBoolean("vibrate", true)) else userChannel(
      p.optString("channel", "Assistant").ifEmpty { "Assistant" },
      runCatching { BotImportance.valueOf(p.optString("importance", "default")) }.getOrElse { throw BadParams("invalid importance") },
      p.optBoolean("silent", false))
    if (nm.getNotificationChannel(channel.id)?.importance == NotificationManager.IMPORTANCE_NONE)
      throw PhoneFailure("channel_blocked", "the user turned off the ${channel.name} notification channel")
    val actions = parseActions(p.optJSONArray("actions") ?: JSONArray())
    val text = p.optString("text").takeIf { it.isNotEmpty() }
    val b = Notification.Builder(ctx, channel.id).setSmallIcon(R.drawable.ic_stat_bridge).setContentTitle(title)
      .setAutoCancel(true).setContentIntent(eventIntent(tag, "content", false)).setDeleteIntent(eventIntent(tag, "dismissed", false))
    if (text != null) b.setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
    if (alarm) b.setCategory(Notification.CATEGORY_ALARM)
    actions.forEach { a ->
      val ab = Notification.Action.Builder(null, a.label, eventIntent(tag, a.id, a.reply))
      if (a.reply) ab.addRemoteInput(RemoteInput.Builder(REPLY_KEY).setLabel(a.placeholder ?: a.label).build())
      b.addAction(ab.build())
    }
    val n = b.build()
    if (alarm && p.optBoolean("insistent", true)) n.flags = n.flags or Notification.FLAG_INSISTENT
    nm.notify(tag, BOT_NOTIFICATION_ID, n)
    val out = JSONObject().put("notification_id", tag).put("channel_id", channel.id)
    if (alarm) {
      val am = ctx.getSystemService(AudioManager::class.java)
      val vol = am.getStreamVolume(AudioManager.STREAM_ALARM)
      out.put("alarm_volume", vol).put("alarm_volume_max", am.getStreamMaxVolume(AudioManager.STREAM_ALARM))
        .put("bypass_dnd", channel.canBypassDnd()).put("dnd_filter", nm.currentInterruptionFilter)
      if (vol == 0) out.put("warning", "alarm volume is 0, so the alarm will not be audible")
    }
    return out
  }

  private fun parseActions(arr: JSONArray): List<BotAction> = (0 until arr.length()).map { i ->
    val a = arr.getJSONObject(i)
    val reply = a.optJSONObject("reply")
    BotAction(a.getString("id"), a.getString("label"), reply != null, reply?.optString("placeholder")?.takeIf { it.isNotEmpty() })
  }

  private fun userChannel(name: String, importance: BotImportance, silent: Boolean): NotificationChannel {
    val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "assistant" }
    val id = "bot_${slug}_${importance.name}" + if (silent) "_silent" else ""
    return nm.getNotificationChannel(id) ?: NotificationChannel(id, "$name (${importance.name}${if (silent) ", silent" else ""})", importance.level).also {
      if (silent) { it.setSound(null, null); it.enableVibration(false) }
      nm.createNotificationChannel(it)
    }
  }

  private fun alarmChannel(vibrate: Boolean): NotificationChannel {
    val dnd = Capabilities.dndAccess(ctx)
    val id = "bot_alarm" + (if (dnd) "_dnd" else "") + (if (vibrate) "" else "_novib")
    nm.getNotificationChannel(id)?.let { return it }
    val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    return NotificationChannel(id, "Assistant alarm" + (if (vibrate) "" else " (no vibration)"), NotificationManager.IMPORTANCE_HIGH).also {
      it.setSound(sound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
      it.enableVibration(vibrate)
      if (vibrate) it.vibrationPattern = longArrayOf(0, 800, 400, 800)
      it.setBypassDnd(dnd)
      nm.createNotificationChannel(it)
    }
  }

  private fun eventIntent(tag: String, action: String, mutable: Boolean): PendingIntent {
    val i = Intent(ctx, BotEventReceiver::class.java).setAction("$tag/$action").putExtra(EXTRA_TAG, tag).putExtra(EXTRA_ACTION, action)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getBroadcast(ctx, "$tag/$action".hashCode(), i, flags)
  }
}

fun dismissBotNotification(ctx: Context, tag: String): JSONObject {
  val nm = ctx.getSystemService(NotificationManager::class.java)
  if (nm.activeNotifications.none { it.id == BOT_NOTIFICATION_ID && it.tag == tag })
    throw PhoneFailure("not_found", "no active notification with that notification_id")
  nm.cancel(tag, BOT_NOTIFICATION_ID)
  return JSONObject().put("notification_id", tag).put("removed", true)
}

class BotEventReceiver : BroadcastReceiver() {
  override fun onReceive(ctx: Context, intent: Intent) {
    val tag = intent.getStringExtra(EXTRA_TAG) ?: return
    val action = intent.getStringExtra(EXTRA_ACTION) ?: return
    val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY_KEY)?.toString()
    if (action != "content" && action != "dismissed") ctx.getSystemService(NotificationManager::class.java).cancel(tag, BOT_NOTIFICATION_ID)
    val pending = goAsync()
    BotEvents.post(ctx, tag, action, reply) { pending.finish() }
  }
}

object BotEvents {
  fun post(ctx: Context, notificationId: String, action: String, reply: String? = null, done: () -> Unit = {}) {
    val event = JSONObject().put("notification_id", notificationId).put("action_id", action).put("at", Instant.now().toString())
    if (reply != null) event.put("reply_text", reply)
    Thread {
      try {
        val (code, _) = WorkerApi.post(ctx, "/phone/event", event)
        Log.i(TAG, "notification event -> HTTP $code")
      } catch (e: Exception) {
        Log.w(TAG, "notification event post failed: ${e.javaClass.simpleName}")
      } finally { done() }
    }.start()
  }
}
