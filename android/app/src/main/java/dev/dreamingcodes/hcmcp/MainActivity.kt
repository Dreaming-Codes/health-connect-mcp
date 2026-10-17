package dev.dreamingcodes.hcmcp

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    if (BuildConfig.DEBUG) intent?.let { i ->
      if (i.hasExtra("worker_url")) Settings.save(this, i.getStringExtra("worker_url")!!, i.getStringExtra("device_token") ?: "")
      if (i.getBooleanExtra("seed", false)) lifecycleScope.launch { runCatching { TestData.insert(HealthConnectClient.getOrCreate(this@MainActivity)) } }
    }
    setContent { AppTheme { BridgeScreen() } }
    // Android 15+ blanks screens with password fields in recordings; the token uses MaskTransformation instead and the UI opts out.
    if (Build.VERSION.SDK_INT >= 35) markNotSensitive(window.decorView)
  }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
  val ctx = LocalContext.current
  val dark = isSystemInDarkTheme()
  val scheme = when {
    Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    dark -> darkColorScheme() else -> lightColorScheme()
  }
  MaterialTheme(colorScheme = scheme, content = content)
}

private data class Access(val postNotifications: Boolean = false, val dnd: Boolean = false, val fullScreen: Boolean = false,
  val location: Boolean = false, val backgroundLocation: Boolean = false, val sms: Boolean = false, val contacts: Boolean = false)

private fun readAccess(ctx: Context) = Access(Capabilities.canPostNotifications(ctx), Capabilities.dndAccess(ctx), Calls.canFullScreen(ctx),
  Capabilities.location(ctx), Capabilities.backgroundLocation(ctx), Capabilities.SMS.all { ctx.has(it) }, Capabilities.CONTACTS.all { ctx.has(it) })

private data class HcInfo(val sdk: Int, val granted: Int, val total: Int, val backgroundAvailable: Boolean, val backgroundGranted: Boolean)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BridgeScreen() {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val scroll = rememberScrollState()
  val status by BridgeState.status.collectAsState()

  var url by rememberSaveable { mutableStateOf("") }
  var token by rememberSaveable { mutableStateOf("") }
  var loaded by remember { mutableStateOf(false) }
  var savedUrl by remember { mutableStateOf("") }
  var savedToken by remember { mutableStateOf("") }
  var showToken by rememberSaveable { mutableStateOf(false) }
  var setupError by remember { mutableStateOf<String?>(null) }
  var setupY by remember { mutableIntStateOf(0) }
  val urlFocus = remember { FocusRequester() }
  val tokenFocus = remember { FocusRequester() }
  var hc by remember { mutableStateOf<HcInfo?>(null) }
  var notificationAccess by remember { mutableStateOf(false) }
  var access by remember { mutableStateOf(Access()) }
  var deniedOnce by rememberSaveable { mutableStateOf(false) }
  val listenerConnected by NotificationIndex.listener.collectAsState()
  var lastReq by remember { mutableStateOf<Pair<Long, String>?>(null) }
  var tick by remember { mutableIntStateOf(0) }

  suspend fun refresh() {
    hc = withContext(Dispatchers.IO) { readHc(ctx) }
    notificationAccess = NotificationIndex.accessGranted(ctx)
    access = readAccess(ctx)
    lastReq = withContext(Dispatchers.IO) { Settings.lastRequest(ctx) }
  }
  LaunchedEffect(Unit) {
    withContext(Dispatchers.IO) {
      val u = Settings.workerUrl(ctx); val t = Settings.deviceToken(ctx)
      savedUrl = u; savedToken = t
      if (!loaded) { url = u.ifBlank { Settings.DEFAULT_URL }; token = t; loaded = true }
      val pa = Settings.pushRegisteredAt(ctx); val pe = Settings.pushError(ctx)
      BridgeState.set { it.copy(pushRegisteredAt = it.pushRegisteredAt ?: pa, pushError = it.pushError ?: pe) }
    }
    refresh()
  }
  LaunchedEffect(Unit) { while (true) { delay(15_000); tick++ } }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle) {
    val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) scope.launch { refresh() } }
    lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
  }
  LaunchedEffect(status.lastRequestAt) { status.lastRequestAt?.let { lastReq = it to (status.lastRequestMethod ?: "") } }

  val permLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { scope.launch { refresh() } }
  val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
    if (res.values.any { !it }) deniedOnce = true
    scope.launch { refresh() }
  }
  fun open(action: String, withPackage: Boolean = false) {
    val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (withPackage) i.data = Uri.parse("package:${ctx.packageName}")
    runCatching { ctx.startActivity(i) }
  }

  val configured = savedUrl.isNotBlank() && savedToken.isNotBlank()
  val dirty = url.trim().trimEnd('/') != savedUrl || token.trim() != savedToken

  fun goToSetup(msg: String) {
    setupError = msg
    scope.launch { scroll.animateScrollTo(setupY); (if (url.isBlank()) urlFocus else tokenFocus).requestFocus() }
  }
  fun save(): Boolean {
    val u = url.trim().trimEnd('/')
    if (!u.startsWith("https://") && !u.startsWith("http://")) { goToSetup("Worker URL must start with https://"); return false }
    if (token.isBlank()) { goToSetup("Enter the device token"); return false }
    Settings.save(ctx, u, token); savedUrl = u; savedToken = token.trim(); setupError = null
    return true
  }

  Scaffold(
    topBar = { LargeTopAppBar(title = { Text("Health Connect Bridge") }) },
    snackbarHost = { SnackbarHost(snackbar) },
    contentWindowInsets = WindowInsets.safeDrawing,
  ) { inner ->
    Column(
      Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner).imePadding().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      key(tick) { StatusCard(status, configured, lastReq) }

      Button(
        onClick = {
          if (!configured || dirty) { if (!configured && (url.isBlank() || token.isBlank())) { goToSetup("Set up the Worker URL and device token first"); return@Button }; if (!save()) return@Button }
          BridgeState.set { it.copy(registering = true) }
          scope.launch {
            val err = withContext(Dispatchers.IO) { WorkerApi.register(ctx) }
            BridgeState.set { it.copy(registering = false) }
            snackbar.showSnackbar(err?.let { "Registration failed: $it" } ?: "Registered. Your assistant can now wake this phone.")
          }
        },
        enabled = !status.registering,
        modifier = Modifier.fillMaxWidth().height(56.dp),
      ) {
        if (status.registering) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.NotificationsActive, null)
        Spacer(Modifier.width(8.dp))
        Text(if (status.pushRegisteredAt != null) "Re-register device" else "Register device", style = MaterialTheme.typography.titleMedium)
      }

      ElevatedCard(Modifier.fillMaxWidth().onPlacedY { setupY = it }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          SectionTitle(Icons.Outlined.Settings, "Setup")
          if (setupError != null) Text(setupError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
          OutlinedTextField(
            value = url, onValueChange = { url = it; setupError = null }, label = { Text("Worker URL") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(urlFocus),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            isError = setupError != null && url.isBlank(),
          )
          OutlinedTextField(
            value = token, onValueChange = { token = it; setupError = null }, label = { Text("Device token") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(tokenFocus),
            visualTransformation = if (showToken) VisualTransformation.None else MaskTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            isError = setupError != null && token.isBlank(),
            trailingIcon = {
              IconButton(onClick = { showToken = !showToken }) {
                Icon(if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (showToken) "Hide token" else "Show token")
              }
            },
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
              val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
              val txt = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
              if (txt.isNullOrBlank()) scope.launch { snackbar.showSnackbar("Clipboard is empty") } else { token = txt; setupError = null }
            }) { Icon(Icons.Filled.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text("Paste token") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { if (save()) scope.launch { snackbar.showSnackbar("Saved. Tap Register device to apply.") } }, enabled = dirty || !configured) { Text("Save") }
          }
          Text(if (configured && !dirty) "Saved, stored encrypted on this device." else "Not saved yet.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }

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

      Text("Only method names and times are shown here. Health values, notifications, messages, contacts and locations are never logged or stored by the bridge.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(8.dp))
    }
  }
}

private fun Modifier.onPlacedY(f: (Int) -> Unit) = this.onGloballyPositioned { c -> f(c.positionInParent().y.toInt()) }

@Composable
private fun StatusCard(s: BridgeStatus, configured: Boolean, lastReq: Pair<Long, String>?) {
  val (label, color, icon) = when {
    !configured -> Triple("Not configured", MaterialTheme.colorScheme.outline, Icons.Outlined.SettingsSuggest)
    s.pushError != null -> Triple("Registration failed", MaterialTheme.colorScheme.error, Icons.Filled.ErrorOutline)
    s.pushRegisteredAt != null -> Triple("Ready", Color(0xFF2E7D32), Icons.Filled.CheckCircle)
    else -> Triple("Not registered", MaterialTheme.colorScheme.outline, Icons.Filled.PauseCircle)
  }
  val c by animateColorAsState(color, label = "status")
  OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(2.dp, c)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c, modifier = Modifier.size(32.dp)); Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
      }
      val sub = when {
        !configured -> "Enter your Worker URL and device token below."
        s.pushError != null -> "Tap Re-register to try again."
        s.pushRegisteredAt != null -> "Your assistant wakes this phone with a push message when it needs data. Nothing runs in the background. Registered ${ago(s.pushRegisteredAt)}."
        else -> "Tap Register device to finish setup."
      }
      if (s.pushError != null) Text("Last error: ${s.pushError}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      HorizontalDivider(Modifier.padding(vertical = 4.dp))
      Text(lastReq?.let { "Last request: ${it.second} · ${ago(it.first)}" } ?: "Last request: none yet",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable private fun SectionTitle(icon: ImageVector, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
  Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text(text, style = MaterialTheme.typography.titleMedium)
}
@Composable private fun AccessRow(granted: Boolean, text: String, onGrant: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
  Box(Modifier.weight(1f)) { CheckRow(granted, text) }
  if (!granted) TextButton(onClick = onGrant) { Text("Allow") }
}
@Composable private fun CheckRow(ok: Boolean, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
  Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
  Spacer(Modifier.width(8.dp)); Text(text, style = MaterialTheme.typography.bodyMedium)
}

private fun ago(t: Long): String {
  val s = (System.currentTimeMillis() - t) / 1000
  return when { s < 60 -> "just now"; s < 3600 -> "${s / 60} min ago"; s < 86400 -> "${s / 3600} h ago"; else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t)) }
}
private suspend fun readHc(ctx: Context): HcInfo {
  val sdk = HealthConnectClient.getSdkStatus(ctx)
  if (sdk != HealthConnectClient.SDK_AVAILABLE) return HcInfo(sdk, 0, HealthHandler.READ_PERMS.size, false, false)
  val c = HealthConnectClient.getOrCreate(ctx)
  val g = c.permissionController.getGrantedPermissions()
  val bg = HealthHandler.backgroundAvailable(c)
  return HcInfo(sdk, HealthHandler.READ_PERMS.count { it in g }, HealthHandler.READ_PERMS.size, bg, HealthHandler.BACKGROUND_PERM in g)
}

private object MaskTransformation : VisualTransformation {
  override fun filter(text: AnnotatedString): TransformedText {
    val n = text.length
    val masked = if (n <= 4) "•".repeat(n) else "•".repeat(n - 4) + text.text.takeLast(4)
    return TransformedText(AnnotatedString(masked), OffsetMapping.Identity)
  }
}

@androidx.annotation.RequiresApi(35)
private fun markNotSensitive(v: android.view.View) {
  v.setContentSensitivity(android.view.View.CONTENT_SENSITIVITY_NOT_SENSITIVE)
  if (v is android.view.ViewGroup) {
    v.setOnHierarchyChangeListener(object : android.view.ViewGroup.OnHierarchyChangeListener {
      override fun onChildViewAdded(parent: android.view.View?, child: android.view.View?) { child?.let { markNotSensitive(it) } }
      override fun onChildViewRemoved(parent: android.view.View?, child: android.view.View?) {}
    })
    for (i in 0 until v.childCount) markNotSensitive(v.getChildAt(i))
  }
}
