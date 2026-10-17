package dev.dreamingcodes.hcmcp

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject

typealias PhoneMethod = suspend (JSONObject) -> Any

infix fun String.runs(f: PhoneMethod) = this to f

interface MethodGroup {
  val methods: Map<String, PhoneMethod>
}

fun Context.has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

enum class Requirement(private val code: String, private val message: String, val met: (Context) -> Boolean) {
  PostNotifications("notifications_permission_missing", "the app may not post notifications; open the app and tap Allow notifications", Capabilities::canPostNotifications),
  Location("location_permission_missing", "location permission is not granted; open the app and tap Allow location", Capabilities::location),
  BackgroundLocation("background_location_missing", "location is only allowed while the app is open; set it to Allow all the time", Capabilities::backgroundLocation),
  ReadSms("sms_permission_missing", "SMS read permission is not granted; open the app and tap Allow SMS", { it.has(Manifest.permission.READ_SMS) }),
  SendSms("sms_permission_missing", "SMS send permission is not granted; open the app and tap Allow SMS", { it.has(Manifest.permission.SEND_SMS) }),
  Contacts("contacts_permission_missing", "contacts permission is not granted; open the app and tap Allow contacts", { it.has(Manifest.permission.READ_CONTACTS) });

  fun check(ctx: Context) { if (!met(ctx)) throw PhoneFailure(code, message) }
}

object Capabilities {
  val POST_NOTIFICATIONS = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
  val LOCATION = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
  val BACKGROUND_LOCATION = if (Build.VERSION.SDK_INT >= 29) listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else emptyList()
  val SMS = listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
  val CONTACTS = listOf(Manifest.permission.READ_CONTACTS)

  fun canPostNotifications(ctx: Context) = POST_NOTIFICATIONS.all { ctx.has(it) } && ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
  fun dndAccess(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
  fun location(ctx: Context) = LOCATION.any { ctx.has(it) }
  fun backgroundLocation(ctx: Context) = BACKGROUND_LOCATION.all { ctx.has(it) }

  fun json(ctx: Context): JSONObject = JSONObject()
    .put("notification_access", NotificationIndex.accessGranted(ctx))
    .put("notification_listener_connected", NotificationIndex.listener.value != null)
    .put("post_notifications", canPostNotifications(ctx))
    .put("dnd_access", dndAccess(ctx)).put("full_screen_intent", Calls.canFullScreen(ctx))
    .put("location", location(ctx)).put("background_location", backgroundLocation(ctx))
    .put("sms_read", ctx.has(Manifest.permission.READ_SMS)).put("sms_send", ctx.has(Manifest.permission.SEND_SMS))
    .put("contacts", ctx.has(Manifest.permission.READ_CONTACTS))
}
