package dev.dreamingcodes.hcmcp

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

class NotificationListener : NotificationListenerService() {
  override fun onListenerConnected() {
    NotificationIndex.active.clear()
    activeNotifications?.forEach { NotificationIndex.active[it.key] = it }
    NotificationIndex.listener.value = this
  }

  override fun onListenerDisconnected() {
    NotificationIndex.listener.value = null
    NotificationIndex.active.clear()
  }

  override fun onNotificationPosted(sbn: StatusBarNotification) { NotificationIndex.active[sbn.key] = sbn }
  override fun onNotificationRemoved(sbn: StatusBarNotification) { NotificationIndex.active.remove(sbn.key) }
}

object NotificationIndex {
  val active = ConcurrentHashMap<String, StatusBarNotification>()
  val listener = MutableStateFlow<NotificationListener?>(null)

  fun component(ctx: Context) = ComponentName(ctx, NotificationListener::class.java)
  fun accessGranted(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(ctx))
}

sealed interface NotificationAction {
  data object Content : NotificationAction
  data class Button(val index: Int) : NotificationAction
}

class NotificationHandler(private val ctx: Context) : MethodGroup {
  override val methods = mapOf(
    "list_notifications" runs { connectedListener(); list(it) },
    "dismiss_notification" runs { dismiss(connectedListener(), it.key()) },
    "perform_notification_action" runs { perform(connectedListener(), it.key(), it.action(), it.optString("replyText").takeIf { r -> r.isNotEmpty() }) },
  )

  companion object {
    private const val REBIND_WAIT_MS = 3_000L
    private const val REMOVAL_WAIT_MS = 1_500L
  }

  private suspend fun connectedListener(): NotificationListener {
    NotificationIndex.listener.value?.let { return it }
    if (!NotificationIndex.accessGranted(ctx)) throw PhoneFailure("notification_access_missing", "notification access is not granted; open the app and tap Grant notification access")
    NotificationListenerService.requestRebind(NotificationIndex.component(ctx))
    return withTimeoutOrNull(REBIND_WAIT_MS) { NotificationIndex.listener.filterNotNull().first() }
      ?: throw PhoneFailure("notification_listener_unavailable", "notification access is granted but the listener did not connect; open the app once")
  }

  private fun JSONObject.key(): String = optString("key").ifEmpty { throw BadParams("key required") }
  private fun JSONObject.action(): NotificationAction = when (val a = opt("action")) {
    "content" -> NotificationAction.Content
    is Int -> if (a >= 0) NotificationAction.Button(a) else throw BadParams("action must be >= 0 or \"content\"")
    else -> throw BadParams("action must be an action index or \"content\"")
  }

  private fun find(key: String) = NotificationIndex.active[key] ?: throw PhoneFailure("not_found", "no active notification with that key")

  private fun list(p: JSONObject): JSONObject {
    val tz = p.zone()
    val since = p.instantMs("since")
    val pkg = p.optString("package").takeIf { it.isNotEmpty() }
    val limit = p.optInt("limit", 50).coerceIn(1, 200)
    val all = NotificationIndex.active.values.toList()
    val matching = all.filter { (pkg == null || it.packageName == pkg) && (since == null || it.postTime >= since) }.sortedByDescending { it.postTime }
    val redactedMarkers = redactionMarkers()
    return JSONObject().put("timezone", tz.id).put("total_active", all.size).put("count", matching.size.coerceAtMost(limit))
      .put("notifications", JSONArray(matching.take(limit).map { describe(it, tz, redactedMarkers) }))
  }

  private fun describe(sbn: StatusBarNotification, tz: ZoneId, redactedMarkers: Set<String>): JSONObject {
    val n = sbn.notification
    val x = n.extras
    val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    val text = x.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    return JSONObject().put("key", sbn.key).put("package", sbn.packageName).put("app_label", appLabel(sbn.packageName))
      .put("post_time", iso(sbn.postTime, tz))
      .put("title", title).put("text", text).put("sub_text", x.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
      .put("category", n.category).put("ongoing", sbn.isOngoing).put("clearable", sbn.isClearable)
      .put("redacted", title in redactedMarkers || text in redactedMarkers)
      .put("has_content_intent", n.contentIntent != null)
      .put("actions", JSONArray((n.actions ?: emptyArray()).mapIndexed { i, a ->
        JSONObject().put("index", i).put("title", a.title?.toString()).put("reply", !a.remoteInputs.isNullOrEmpty())
          .put("opens_activity", opensActivity(a.actionIntent))
          .put("auth_required", Build.VERSION.SDK_INT >= 31 && a.isAuthenticationRequired)
      }))
  }

  private fun opensActivity(pi: PendingIntent?) = pi != null && Build.VERSION.SDK_INT >= 31 && pi.isActivity

  private fun appLabel(pkg: String): String = try {
    ctx.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() }
  } catch (e: Exception) { pkg }

  private fun redactionMarkers(): Set<String> {
    val r = Resources.getSystem()
    return listOf("redacted_notification_message", "redacted_otp_notification_message", "redacted_notification_single_line_text", "redacted_notification_single_line_title")
      .mapNotNull { name -> r.getIdentifier(name, "string", "android").takeIf { it != 0 }?.let { r.getString(it) } }.toSet()
  }

  private suspend fun dismiss(listener: NotificationListener, key: String): JSONObject {
    val sbn = find(key)
    if (!sbn.isClearable) throw PhoneFailure("not_clearable", "notification is ongoing or not clearable")
    listener.cancelNotification(key)
    return JSONObject().put("key", key).put("removed", awaitRemoval(key))
  }

  private suspend fun awaitRemoval(key: String): Boolean = withTimeoutOrNull(REMOVAL_WAIT_MS) {
    while (NotificationIndex.active.containsKey(key)) delay(50)
    true
  } ?: false

  private suspend fun perform(listener: NotificationListener, key: String, action: NotificationAction, replyText: String?): JSONObject {
    val sbn = find(key)
    val n = sbn.notification
    val (pi, fillIn) = when (action) {
      NotificationAction.Content -> {
        if (replyText != null) throw BadParams("replyText only applies to reply actions")
        (n.contentIntent ?: throw PhoneFailure("not_found", "notification has no content intent")) to null
      }
      is NotificationAction.Button -> {
        val a = n.actions?.getOrNull(action.index) ?: throw PhoneFailure("not_found", "notification has no action ${action.index}")
        val inputs = a.remoteInputs?.takeIf { it.isNotEmpty() }
        when {
          inputs == null && replyText != null -> throw BadParams("action ${action.index} does not take a reply")
          inputs != null && replyText == null -> throw BadParams("action ${action.index} is a reply action; replyText required")
        }
        a.actionIntent to inputs?.let { replyIntent(it, replyText!!) }
      }
    }
    try {
      pi.send(ctx, 0, fillIn, null, null, null, sendOptions())
    } catch (e: PendingIntent.CanceledException) {
      throw PhoneFailure("action_canceled", "the app cancelled this action's intent")
    }
    if (action == NotificationAction.Content && n.flags and Notification.FLAG_AUTO_CANCEL != 0) listener.cancelNotification(key)
    return JSONObject().put("key", key).put("sent", true).put("opens_activity", opensActivity(pi))
  }

  private fun replyIntent(inputs: Array<RemoteInput>, text: String): Intent {
    val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
    return Intent().also {
      RemoteInput.addResultsToIntent(inputs, it, results)
      RemoteInput.setResultsSource(it, RemoteInput.SOURCE_FREE_FORM_INPUT)
    }
  }

  @Suppress("DEPRECATION")
  private fun sendOptions(): Bundle? = when {
    Build.VERSION.SDK_INT >= 36 -> ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE).toBundle()
    Build.VERSION.SDK_INT >= 34 -> ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
    else -> null
  }
}
