package dev.dreamingcodes.hcmcp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
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
    // Debug-only automation hook for emulator tests.
    if (BuildConfig.DEBUG) intent?.let { i ->
      if (i.hasExtra("worker_url")) Settings.save(this, i.getStringExtra("worker_url")!!, i.getStringExtra("device_token") ?: "")
      if (i.getBooleanExtra("seed", false)) lifecycleScope.launch { runCatching { TestData.insert(HealthConnectClient.getOrCreate(this@MainActivity)) } }
    }
    setContent { AppTheme { BridgeScreen() } }
    // Android 15+ marks screens with password fields as sensitive and blanks them in recordings/screen share.
    // The token field is a plain text field with our own mask, and we opt the whole UI out explicitly.
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
  var batteryExempt by remember { mutableStateOf(isIgnoringBattery(ctx)) }
  var lastReq by remember { mutableStateOf<Pair<Long, String>?>(null) }
  var tick by remember { mutableIntStateOf(0) }

  suspend fun refresh() {
    hc = withContext(Dispatchers.IO) { readHc(ctx) }
    batteryExempt = isIgnoringBattery(ctx)
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
  LaunchedEffect(Unit) { while (true) { delay(15_000); tick++ } } // keeps "x min ago" fresh
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle) {
    val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) scope.launch { refresh() } }
    lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
  }
  LaunchedEffect(status.lastRequestAt) { status.lastRequestAt?.let { lastReq = it to (status.lastRequestMethod ?: "") } }

  val permLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { scope.launch { refresh() } }
  val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
  val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { batteryExempt = isIgnoringBattery(ctx) }

  val configured = savedUrl.isNotBlank() && savedToken.isNotBlank()
  val dirty = url.trim().trimEnd('/') != savedUrl || token.trim() != savedToken
  val running = status.phase in setOf(Phase.CONNECTING, Phase.CONNECTED, Phase.OFFLINE)

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
      // ---------- Status ----------
      key(tick) { StatusCard(status, configured, lastReq) }

      // ---------- Primary action: push registration ----------
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

      // ---------- Setup ----------
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
            Button(onClick = { if (save()) scope.launch { snackbar.showSnackbar(if (running) "Saved. Restart the bridge to apply." else "Saved") } }, enabled = dirty || !configured) { Text("Save") }
          }
          Text(if (configured && !dirty) "Saved, stored encrypted on this device." else "Not saved yet.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }

      // ---------- Health Connect ----------
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

      // ---------- Battery ----------
      ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          SectionTitle(Icons.Outlined.BatteryChargingFull, "Battery")
          CheckRow(batteryExempt, if (batteryExempt) "Unrestricted: Android won't put the bridge to sleep" else "Optimized: Android may cut the connection while the phone sleeps")
          if (!batteryExempt) OutlinedButton(onClick = {
            @SuppressLint("BatteryLife")
            val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            runCatching { batteryLauncher.launch(i) }
          }, modifier = Modifier.fillMaxWidth()) { Text("Allow running in background") }
        }
      }

      // ---------- Advanced: always-on connection ----------
      ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text("Always-on connection", style = MaterialTheme.typography.titleMedium)
              Text("Faster answers, but keeps a connection and a notification running and uses more battery. Push works without it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = running, onCheckedChange = { on ->
              if (!on) { Settings.setEnabled(ctx, false); BridgeService.stop(ctx); return@Switch }
              if (!configured || dirty) { if (!configured && (url.isBlank() || token.isBlank())) { goToSetup("Set up the Worker URL and device token first"); return@Switch }; if (!save()) return@Switch }
              if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
              Settings.setEnabled(ctx, true)
              BridgeService.start(ctx)?.let { scope.launch { snackbar.showSnackbar(it) } }
            })
          }
        }
      }

      Text("Only method names and times are shown here. Health values are never logged or stored by the bridge.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(8.dp))
    }
  }
}

private fun Modifier.onPlacedY(f: (Int) -> Unit) = this.onGloballyPositioned { c -> f(c.positionInParent().y.toInt()) }

@Composable
private fun StatusCard(s: BridgeStatus, configured: Boolean, lastReq: Pair<Long, String>?) {
  val live = s.phase in setOf(Phase.CONNECTING, Phase.CONNECTED, Phase.OFFLINE, Phase.REJECTED)
  val (label, color, icon) = when {
    !configured && !live -> Triple("Not configured", MaterialTheme.colorScheme.outline, Icons.Outlined.SettingsSuggest)
    s.phase == Phase.CONNECTED -> Triple("Connected (always-on)", Color(0xFF2E7D32), Icons.Filled.CheckCircle)
    s.phase == Phase.CONNECTING -> Triple("Connecting…", Color(0xFFF9A825), Icons.Filled.Sync)
    s.phase == Phase.OFFLINE -> Triple("Offline", MaterialTheme.colorScheme.error, Icons.Filled.CloudOff)
    s.phase == Phase.REJECTED -> Triple("Token rejected", MaterialTheme.colorScheme.error, Icons.Filled.ErrorOutline)
    s.pushError != null -> Triple("Registration failed", MaterialTheme.colorScheme.error, Icons.Filled.ErrorOutline)
    s.pushRegisteredAt != null -> Triple("Ready (push)", Color(0xFF2E7D32), Icons.Filled.CheckCircle)
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
        !configured && !live -> "Enter your Worker URL and device token below."
        s.phase == Phase.CONNECTED -> "Answering over a live connection." + (s.connectedSince?.let { " Since ${fmt(it)}." } ?: "")
        s.phase == Phase.OFFLINE -> "Retrying${s.retryInSec?.let { " in ${it}s" } ?: ""}. Push still works."
        s.phase == Phase.CONNECTING -> "Opening a secure connection to the Worker."
        s.phase == Phase.REJECTED -> "Fix the device token and try again."
        s.pushError != null -> "Tap Re-register to try again."
        s.pushRegisteredAt != null -> "Your assistant wakes this phone on demand. No background connection. Registered ${ago(s.pushRegisteredAt)}."
        else -> "Tap Register device to finish setup."
      }
      if (s.pushError != null && !live) Text("Last error: ${s.pushError}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      if (s.lastError != null && live && s.phase != Phase.CONNECTED)
        Text("Last error: ${s.lastError}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      HorizontalDivider(Modifier.padding(vertical = 4.dp))
      Text(lastReq?.let { "Last request: ${it.second} · ${ago(it.first)}" } ?: "Last request: none yet",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable private fun SectionTitle(icon: ImageVector, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
  Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text(text, style = MaterialTheme.typography.titleMedium)
}
@Composable private fun CheckRow(ok: Boolean, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
  Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
  Spacer(Modifier.width(8.dp)); Text(text, style = MaterialTheme.typography.bodyMedium)
}

private fun fmt(t: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))
private fun ago(t: Long): String {
  val s = (System.currentTimeMillis() - t) / 1000
  return when { s < 60 -> "just now"; s < 3600 -> "${s / 60} min ago"; s < 86400 -> "${s / 3600} h ago"; else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t)) }
}
private fun isIgnoringBattery(ctx: Context) = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
private suspend fun readHc(ctx: Context): HcInfo {
  val sdk = HealthConnectClient.getSdkStatus(ctx)
  if (sdk != HealthConnectClient.SDK_AVAILABLE) return HcInfo(sdk, 0, HealthHandler.READ_PERMS.size, false, false)
  val c = HealthConnectClient.getOrCreate(ctx)
  val g = c.permissionController.getGrantedPermissions()
  val bg = HealthHandler.backgroundAvailable(c)
  return HcInfo(sdk, HealthHandler.READ_PERMS.count { it in g }, HealthHandler.READ_PERMS.size, bg, HealthHandler.BACKGROUND_PERM in g)
}

/** Masks all but the last 4 characters without using a password input type (avoids Android 15 sensitive-content blanking). */
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
