package dev.dreamingcodes.hcmcp

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    if (BuildConfig.DEBUG && intent?.getBooleanExtra("seed", false) == true)
      lifecycleScope.launch { runCatching { TestData.insert(HealthConnectClient.getOrCreate(this@MainActivity)) } }
    setContent { AppTheme { BridgeScreen() } }
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BridgeScreen() {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val scroll = rememberScrollState()
  val status by BridgeState.status.collectAsState()

  var url by rememberSaveable { mutableStateOf("") }
  var loaded by remember { mutableStateOf(false) }
  var savedUrl by remember { mutableStateOf("") }
  var name by rememberSaveable { mutableStateOf(Build.MODEL ?: "Android phone") }
  var savedName by remember { mutableStateOf("") }
  var savedDeviceId by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var issued by remember { mutableStateOf<IssuedCode?>(null) }
  var setupError by remember { mutableStateOf<String?>(null) }
  var setupY by remember { mutableIntStateOf(0) }
  val urlFocus = remember { FocusRequester() }
  var lastReq by remember { mutableStateOf<Pair<Long, String>?>(null) }
  var tick by remember { mutableIntStateOf(0) }

  OnResume { lastReq = withContext(Dispatchers.IO) { Settings.lastRequest(ctx) } }
  LaunchedEffect(Unit) {
    withContext(Dispatchers.IO) {
      val u = Settings.workerUrl(ctx)
      savedUrl = u; savedDeviceId = Settings.deviceId(ctx); savedName = Settings.deviceName(ctx)
      if (!loaded) { url = u.ifBlank { Settings.DEFAULT_URL }; savedName.takeIf { it.isNotBlank() }?.let { name = it }; loaded = true }
      val pa = Settings.pushRegisteredAt(ctx); val pe = Settings.pushError(ctx)
      BridgeState.set { it.copy(pushRegisteredAt = it.pushRegisteredAt ?: pa, pushError = it.pushError ?: pe) }
    }
  }
  LaunchedEffect(Unit) { while (true) { delay(15_000); tick++ } }
  LaunchedEffect(status.lastRequestAt) { status.lastRequestAt?.let { lastReq = it to (status.lastRequestMethod ?: "") } }

  val configured = savedUrl.isNotBlank() && savedDeviceId.isNotBlank()

  fun goToSetup(msg: String) {
    setupError = msg
    scope.launch { scroll.animateScrollTo(setupY); urlFocus.requestFocus() }
  }
  suspend fun registerPush() {
    BridgeState.set { it.copy(registering = true) }
    val err = withContext(Dispatchers.IO) { WorkerApi.register(ctx) }
    BridgeState.set { it.copy(registering = false) }
    snackbar.showSnackbar(err?.let { "Registration failed: $it" } ?: "Connected. Your assistant can now reach this phone.")
  }
  fun connect() {
    val u = url.trim().trimEnd('/')
    if (!u.startsWith("https://") && !u.startsWith("http://")) { goToSetup("Worker URL must start with https://"); return }
    val n = name.trim().ifBlank { Build.MODEL ?: "Android phone" }
    busy = true
    scope.launch {
      val result = withContext(Dispatchers.IO) { runCatching { WorkerApi.enroll(u, n) } }
      busy = false
      result.onSuccess { e ->
        withContext(Dispatchers.IO) { Settings.saveEnrollment(ctx, u, e, n) }
        savedUrl = u; savedDeviceId = e.deviceId; savedName = n; setupError = null
        registerPush()
      }.onFailure { setupError = "Could not connect: ${it.message}" }
    }
  }
  fun registerWithGrokBot() {
    busy = true
    scope.launch {
      val result = withContext(Dispatchers.IO) { runCatching { WorkerApi.connectorCode(ctx) } }
      busy = false
      result.onSuccess { c ->
        issued = c
        if (!GrokBot.share(ctx, GrokBot.connectorMessage(c, savedName.ifBlank { name })))
          snackbar.showSnackbar("Grok Bot is not installed; copy the message instead")
      }.onFailure { snackbar.showSnackbar("Failed: ${it.message}") }
    }
  }
  fun rename() {
    val n = name.trim()
    if (n.isBlank()) return
    scope.launch {
      val r = withContext(Dispatchers.IO) { runCatching { WorkerApi.rename(ctx, n); Settings.setDeviceName(ctx, n) } }
      if (r.isSuccess) savedName = n
      snackbar.showSnackbar(if (r.isSuccess) "Renamed to $n" else "Rename failed: ${r.exceptionOrNull()?.message}")
    }
  }
  fun disconnect() {
    busy = true
    scope.launch {
      val revoked = withContext(Dispatchers.IO) {
        val r = runCatching { WorkerApi.revoke(ctx) }.getOrDefault(false)
        runCatching { Tasks.await(FirebaseMessaging.getInstance().deleteToken(), 20, TimeUnit.SECONDS) }
        Settings.clear(ctx)
        r
      }
      busy = false
      savedUrl = ""; savedDeviceId = ""; savedName = ""
      BridgeState.set { it.copy(pushRegisteredAt = null, pushError = null) }
      snackbar.showSnackbar(if (revoked) "Disconnected. This phone's keys no longer work." else "Disconnected here, but the Worker could not be reached to revoke the keys. They can no longer reach this phone.")
    }
  }

  issued?.let { CodeDialog(it, savedName.ifBlank { name }) { issued = null } }

  Scaffold(
    topBar = { LargeTopAppBar(title = { Text("Android Connect") }) },
    snackbarHost = { SnackbarHost(snackbar) },
    contentWindowInsets = WindowInsets.safeDrawing,
  ) { inner ->
    Column(
      Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner).imePadding().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      key(tick) { StatusCard(status, configured, lastReq) }

      ElevatedCard(Modifier.fillMaxWidth().onPlacedY { setupY = it }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          SectionTitle(Icons.Outlined.Settings, if (configured) "Connection" else "Connect")
          if (setupError != null) Text(setupError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
          if (!configured) {
            OutlinedTextField(
              value = url, onValueChange = { url = it; setupError = null }, label = { Text("Worker URL") }, singleLine = true,
              modifier = Modifier.fillMaxWidth().focusRequester(urlFocus),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Device name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { connect() }, enabled = !busy && !status.registering, modifier = Modifier.fillMaxWidth().height(52.dp)) {
              if (busy || status.registering) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Link, null)
              Spacer(Modifier.width(8.dp)); Text("Connect", style = MaterialTheme.typography.titleMedium)
            }
          } else {
            Text(savedUrl, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Device name") }, singleLine = true, modifier = Modifier.weight(1f))
              if (name.trim() != savedName && name.isNotBlank()) TextButton(onClick = { rename() }) { Text("Save") }
            }
            Text("Device id $savedDeviceId", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { registerWithGrokBot() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
              Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Register with Grok Bot", style = MaterialTheme.typography.titleMedium)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(onClick = { scope.launch { registerPush() } }, enabled = !status.registering) { Text("Re-register") }
              TextButton(onClick = { disconnect() }, enabled = !busy) { Text("Disconnect") }
            }
          }
        }
      }

      HealthConnectCard()
      NotificationAccessCard()
      PhoneAccessCard()

      Text("Only method names and times are shown here. Health values, notifications, messages, contacts and locations are never logged or stored by the app.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(8.dp))
    }
  }
}

@Composable
private fun CodeDialog(c: IssuedCode, deviceName: String, onDismiss: () -> Unit) {
  val ctx = LocalContext.current
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    dismissButton = {
      TextButton(onClick = {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("code", GrokBot.connectorMessage(c, deviceName)))
      }) { Text("Copy message") }
    },
    title = { Text("Grok Bot claim code") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(c.code, style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        Text("Grok Bot exchanges this code once for the connector address and key. It expires in 10 minutes. The key itself never appears in the chat.",
          style = MaterialTheme.typography.bodyMedium)
      }
    },
  )
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
        !configured -> "Enter your Worker URL below and tap Connect."
        s.pushError != null -> "Tap Re-register to try again."
        s.pushRegisteredAt != null -> "Your assistant wakes this phone with a push message when it needs data. Nothing runs in the background. Registered ${ago(s.pushRegisteredAt)}."
        else -> "Tap Re-register to finish setup."
      }
      if (s.pushError != null) Text("Last error: ${s.pushError}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      HorizontalDivider(Modifier.padding(vertical = 4.dp))
      Text(lastReq?.let { "Last request: ${it.second} · ${ago(it.first)}" } ?: "Last request: none yet",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable fun SectionTitle(icon: ImageVector, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
  Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text(text, style = MaterialTheme.typography.titleMedium)
}
@Composable fun CheckRow(ok: Boolean, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
  Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
  Spacer(Modifier.width(8.dp)); Text(text, style = MaterialTheme.typography.bodyMedium)
}

private fun ago(t: Long): String {
  val s = (System.currentTimeMillis() - t) / 1000
  return when { s < 60 -> "just now"; s < 3600 -> "${s / 60} min ago"; s < 86400 -> "${s / 3600} h ago"; else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t)) }
}
