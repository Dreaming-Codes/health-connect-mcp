package dev.dreamingcodes.hcmcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OnResume(action: suspend () -> Unit) {
  val scope = rememberCoroutineScope()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val latest by rememberUpdatedState(action)
  DisposableEffect(lifecycle) {
    val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) scope.launch { latest() } }
    lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
  }
}

private data class HcInfo(val sdk: Int, val granted: Int, val total: Int, val backgroundAvailable: Boolean, val backgroundGranted: Boolean)

private suspend fun readHc(ctx: Context): HcInfo {
  val sdk = HealthConnectClient.getSdkStatus(ctx)
  if (sdk != HealthConnectClient.SDK_AVAILABLE) return HcInfo(sdk, 0, HealthHandler.READ_PERMS.size, false, false)
  val c = HealthConnectClient.getOrCreate(ctx)
  val g = c.permissionController.getGrantedPermissions()
  val bg = HealthHandler.backgroundAvailable(c)
  return HcInfo(sdk, HealthHandler.READ_PERMS.count { it in g }, HealthHandler.READ_PERMS.size, bg, HealthHandler.BACKGROUND_PERM in g)
}

@Composable
fun HealthConnectCard() {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var hc by remember { mutableStateOf<HcInfo?>(null) }
  suspend fun refresh() { hc = withContext(Dispatchers.IO) { readHc(ctx) } }
  OnResume { refresh() }
  val permLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { scope.launch { refresh() } }
  ElevatedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      SectionTitle(Icons.Outlined.FavoriteBorder, "Health Connect")
      val h = hc
      when {
        h == null -> Text("Checking…")
        h.sdk != HealthConnectClient.SDK_AVAILABLE -> {
          Text("Health Connect is not available or needs an update.", color = MaterialTheme.colorScheme.error)
          OutlinedButton(onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          }) { Text("Get Health Connect") }
        }
        else -> {
          CheckRow(h.granted == h.total, "Read access: ${h.granted} of ${h.total} data types")
          if (h.backgroundAvailable) CheckRow(h.backgroundGranted, "Background reads ${if (h.backgroundGranted) "allowed" else "not allowed"}")
          else CheckRow(true, "Background reads: not needed on this Android version")
          val allOk = h.granted == h.total && (!h.backgroundAvailable || h.backgroundGranted)
          if (allOk) TextButton(onClick = { permLauncher.launch(HealthHandler.requestedPermissions(h.backgroundAvailable)) }) { Text("Review permissions") }
          else Button(onClick = { permLauncher.launch(HealthHandler.requestedPermissions(h.backgroundAvailable)) }, modifier = Modifier.fillMaxWidth()) { Text("Grant permissions") }
        }
      }
    }
  }
}

@Composable
fun NotificationAccessCard() {
  val ctx = LocalContext.current
  var notificationAccess by remember { mutableStateOf(false) }
  val listenerConnected by NotificationIndex.listener.collectAsState()
  OnResume { notificationAccess = NotificationIndex.accessGranted(ctx) }
  ElevatedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      SectionTitle(Icons.Outlined.Notifications, "Notifications")
      CheckRow(notificationAccess, if (notificationAccess) "Notification access granted" else "Notification access not granted")
      if (notificationAccess) CheckRow(listenerConnected != null, if (listenerConnected != null) "Listener connected" else "Listener not connected yet")
      val openSettings = { ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
      if (notificationAccess) TextButton(onClick = openSettings) { Text("Review notification access") }
      else Button(onClick = openSettings, modifier = Modifier.fillMaxWidth()) { Text("Grant notification access") }
      Text("Lets your assistant read, dismiss and act on notifications, including replies.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

private data class Access(val postNotifications: Boolean = false, val dnd: Boolean = false, val fullScreen: Boolean = false,
  val location: Boolean = false, val backgroundLocation: Boolean = false, val sms: Boolean = false, val contacts: Boolean = false)

private fun readAccess(ctx: Context) = Access(Capabilities.canPostNotifications(ctx), Capabilities.dndAccess(ctx), Calls.canFullScreen(ctx),
  Capabilities.location(ctx), Capabilities.backgroundLocation(ctx), Capabilities.SMS.all { ctx.has(it) }, Capabilities.CONTACTS.all { ctx.has(it) })

@Composable
fun PhoneAccessCard() {
  val ctx = LocalContext.current
  var access by remember { mutableStateOf(Access()) }
  var deniedOnce by rememberSaveable { mutableStateOf(false) }
  OnResume { access = readAccess(ctx) }
  val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
    if (res.values.any { !it }) deniedOnce = true
    access = readAccess(ctx)
  }
  fun open(action: String, withPackage: Boolean = false) {
    val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (withPackage) i.data = Uri.parse("package:${ctx.packageName}")
    runCatching { ctx.startActivity(i) }
  }
  ElevatedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      SectionTitle(Icons.Outlined.Security, "Phone access")
      AccessRow(access.postNotifications, "Show assistant notifications and calls") {
        if (Build.VERSION.SDK_INT >= 33 && !ctx.has(android.Manifest.permission.POST_NOTIFICATIONS)) runtimeLauncher.launch(Capabilities.POST_NOTIFICATIONS.toTypedArray())
        else ctx.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
      if (Build.VERSION.SDK_INT >= 34) AccessRow(access.fullScreen, "Full-screen incoming assistant calls") { open(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, withPackage = true) }
      AccessRow(access.dnd, "Alarms ring through Do Not Disturb") { open(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
      AccessRow(access.location, "Location") { runtimeLauncher.launch(Capabilities.LOCATION.toTypedArray()) }
      if (access.location && Capabilities.BACKGROUND_LOCATION.isNotEmpty())
        AccessRow(access.backgroundLocation, "Location while the app is closed (Allow all the time)") { runtimeLauncher.launch(Capabilities.BACKGROUND_LOCATION.toTypedArray()) }
      AccessRow(access.sms, "Read and send SMS") { runtimeLauncher.launch(Capabilities.SMS.toTypedArray()) }
      AccessRow(access.contacts, "Contacts") { runtimeLauncher.launch(Capabilities.CONTACTS.toTypedArray()) }
      if (deniedOnce && !(access.sms && access.contacts && access.location)) {
        Text("If Android refuses or a switch is greyed out, open App info, tap ⋮ > Allow restricted settings, then try again. Sideloaded apps need this for SMS and notification access.",
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { open(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true) }) { Text("Open App info") }
      }
    }
  }
}

@Composable private fun AccessRow(granted: Boolean, text: String, onGrant: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
  Box(Modifier.weight(1f)) { CheckRow(granted, text) }
  if (!granted) TextButton(onClick = onGrant) { Text("Allow") }
}
