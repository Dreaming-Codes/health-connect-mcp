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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
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
  var name by rememberSaveable { mutableStateOf(Build.MODEL ?: "Android phone") }
  var savedName by remember { mutableStateOf("") }
  var savedDeviceId by remember { mutableStateOf("") }
  var pairing by rememberSaveable { mutableStateOf("") }
  var linkMode by rememberSaveable { mutableStateOf(false) }
  var manualToken by rememberSaveable { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  var issued by remember { mutableStateOf<Pair<CodeKind, IssuedCode>?>(null) }
  var setupError by remember { mutableStateOf<String?>(null) }
  var setupY by remember { mutableIntStateOf(0) }
  val urlFocus = remember { FocusRequester() }
  val tokenFocus = remember { FocusRequester() }
  var lastReq by remember { mutableStateOf<Pair<Long, String>?>(null) }
  var tick by remember { mutableIntStateOf(0) }

  OnResume { lastReq = withContext(Dispatchers.IO) { Settings.lastRequest(ctx) } }
  LaunchedEffect(Unit) {
    withContext(Dispatchers.IO) {
      val u = Settings.workerUrl(ctx); val t = Settings.deviceToken(ctx)
      savedUrl = u; savedToken = t; savedDeviceId = Settings.deviceId(ctx); savedName = Settings.deviceName(ctx)
      if (!loaded) { url = u.ifBlank { Settings.DEFAULT_URL }; token = t; savedName.takeIf { it.isNotBlank() }?.let { name = it }; loaded = true }
      val pa = Settings.pushRegisteredAt(ctx); val pe = Settings.pushError(ctx)
      BridgeState.set { it.copy(pushRegisteredAt = it.pushRegisteredAt ?: pa, pushError = it.pushError ?: pe) }
    }
  }
  LaunchedEffect(Unit) { while (true) { delay(15_000); tick++ } }
  LaunchedEffect(status.lastRequestAt) { status.lastRequestAt?.let { lastReq = it to (status.lastRequestMethod ?: "") } }

  val configured = savedUrl.isNotBlank() && savedToken.isNotBlank()

  fun goToSetup(msg: String) {
    setupError = msg
    scope.launch { scroll.animateScrollTo(setupY); (if (url.isBlank()) urlFocus else tokenFocus).requestFocus() }
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
    if (manualToken) {
      if (token.isBlank()) { goToSetup("Enter the device token"); return }
      Settings.save(ctx, u, token); savedUrl = u; savedToken = token.trim(); setupError = null
      scope.launch { registerPush() }
      return
    }
    val n = name.trim().ifBlank { Build.MODEL ?: "Android phone" }
    if (linkMode && pairing.isBlank()) { setupError = "Enter the pairing code shown on your other phone"; return }
    busy = true
    scope.launch {
      val result = withContext(Dispatchers.IO) { runCatching { WorkerApi.enroll(u, n, pairing.takeIf { linkMode }) } }
      busy = false
      result.onSuccess { e ->
        withContext(Dispatchers.IO) { Settings.saveEnrollment(ctx, u, e, n) }
        savedUrl = u; savedToken = e.deviceToken; savedDeviceId = e.deviceId; savedName = n; setupError = null; pairing = ""
        registerPush()
      }.onFailure { setupError = "Could not connect: ${it.message}" }
    }
  }
  fun issue(kind: CodeKind) {
    busy = true
    scope.launch {
      val result = withContext(Dispatchers.IO) { runCatching { WorkerApi.code(ctx, kind) } }
      busy = false
      result.onSuccess { c ->
        issued = kind to c
        if (kind == CodeKind.Connector && !GrokBot.share(ctx, GrokBot.connectorMessage(c, savedName.ifBlank { name })))
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
    Settings.clear(ctx)
    savedUrl = ""; savedToken = ""; savedDeviceId = ""; savedName = ""; token = ""
    BridgeState.set { it.copy(pushRegisteredAt = null, pushError = null) }
  }

  issued?.let { (kind, c) -> CodeDialog(kind, c, savedName.ifBlank { name }) { issued = null } }

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
            if (!manualToken) OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Device name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (linkMode && !manualToken) OutlinedTextField(
              value = pairing, onValueChange = { pairing = it.uppercase(); setupError = null }, label = { Text("Pairing code from your other phone") }, singleLine = true,
              modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
            )
            if (manualToken) OutlinedTextField(
              value = token, onValueChange = { token = it; setupError = null }, label = { Text("Device token") }, singleLine = true,
              modifier = Modifier.fillMaxWidth().focusRequester(tokenFocus),
              visualTransformation = if (showToken) VisualTransformation.None else MaskTransformation,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
              trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                  Icon(if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (showToken) "Hide token" else "Show token")
                }
              },
              textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            )
            Button(onClick = { connect() }, enabled = !busy && !status.registering, modifier = Modifier.fillMaxWidth().height(52.dp)) {
              if (busy || status.registering) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Link, null)
              Spacer(Modifier.width(8.dp)); Text(if (linkMode && !manualToken) "Link this phone" else "Connect", style = MaterialTheme.typography.titleMedium)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              if (!manualToken) TextButton(onClick = { linkMode = !linkMode; setupError = null }) { Text(if (linkMode) "Start a new connector" else "Link to an existing connector") }
              TextButton(onClick = { manualToken = !manualToken; setupError = null }) { Text(if (manualToken) "Enroll instead" else "I have a device token") }
            }
          } else {
            Text(savedUrl, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            if (savedDeviceId.isNotBlank()) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Device name") }, singleLine = true, modifier = Modifier.weight(1f))
                if (name.trim() != savedName && name.isNotBlank()) TextButton(onClick = { rename() }) { Text("Save") }
              }
              Text("Device id $savedDeviceId", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text("Connected with a device token (single-phone setup).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { issue(CodeKind.Connector) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
              Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Register with Grok Bot", style = MaterialTheme.typography.titleMedium)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(onClick = { issue(CodeKind.Pairing) }, enabled = !busy) { Text("Pair another phone") }
              OutlinedButton(onClick = { scope.launch { registerPush() } }, enabled = !status.registering) { Text("Re-register") }
              TextButton(onClick = { disconnect() }) { Text("Disconnect") }
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
private fun CodeDialog(kind: CodeKind, c: IssuedCode, deviceName: String, onDismiss: () -> Unit) {
  val ctx = LocalContext.current
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    dismissButton = {
      TextButton(onClick = {
        val text = if (kind == CodeKind.Connector) GrokBot.connectorMessage(c, deviceName) else c.code
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("code", text))
      }) { Text(if (kind == CodeKind.Connector) "Copy message" else "Copy") }
    },
    title = { Text(if (kind == CodeKind.Pairing) "Pair another phone" else "Grok Bot claim code") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(c.code, style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        Text(if (kind == CodeKind.Pairing) "On the other phone, enter the same Worker URL, tap Link to an existing connector and type this code. It works once and expires in 10 minutes."
          else "Grok Bot exchanges this code once for the connector address and key. It expires in 10 minutes. The key itself never appears in the chat.",
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
