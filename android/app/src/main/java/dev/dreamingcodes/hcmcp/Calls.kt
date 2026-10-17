package dev.dreamingcodes.hcmcp

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject

data class IncomingCall(val id: String, val agentId: String, val caller: String, val reason: String, val deadline: Long) {
  fun into(i: Intent): Intent = i.putExtra("call_id", id).putExtra("agent_id", agentId).putExtra("caller", caller).putExtra("reason", reason).putExtra("deadline", deadline)

  companion object {
    private val AGENT_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
    fun from(i: Intent): IncomingCall? {
      val agent = i.getStringExtra("agent_id")?.takeIf { AGENT_ID.matches(it) } ?: return null
      return IncomingCall(i.getStringExtra("call_id") ?: return null, agent, i.getStringExtra("caller") ?: "Grok Bot",
        i.getStringExtra("reason") ?: "", i.getLongExtra("deadline", 0))
    }
    fun parse(p: JSONObject, now: Long): IncomingCall {
      val agent = p.optString("agent_id").takeIf { AGENT_ID.matches(it) } ?: throw BadParams("agent_id must match ${AGENT_ID.pattern}")
      val id = p.optString("call_id").ifEmpty { throw BadParams("call_id required") }
      val reason = p.optString("reason").ifEmpty { throw BadParams("reason required") }.take(140)
      val ringMs = p.optLong("ring_seconds", 30).coerceIn(5, 60) * 1000
      return IncomingCall(id, agent, p.optString("caller_name").ifEmpty { "Grok Bot" }.take(60), reason, now + ringMs)
    }
  }
}

object Calls {
  const val CHANNEL = "bot_call"
  const val NOTIFICATION_ID = 7200
  const val ACTION_ANSWER = "dev.dreamingcodes.hcmcp.CALL_ANSWER"
  private const val GROK_BOT = "ai.x.grok.bot"

  fun canFullScreen(ctx: Context) = Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

  fun channel(nm: NotificationManager) {
    if (nm.getNotificationChannel(CHANNEL) != null) return
    val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Assistant calls", NotificationManager.IMPORTANCE_HIGH).apply {
      setSound(sound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
      enableVibration(true); vibrationPattern = longArrayOf(0, 1000, 1000)
    })
  }

  fun grokBotIntents(ctx: Context, agentId: String): List<Intent> = buildList {
    add(Intent(Intent.ACTION_VIEW, Uri.parse("grokbot://app/v1/voice-call?id=$agentId")).setPackage(GROK_BOT))
    add(Intent(Intent.ACTION_VIEW, Uri.parse("grokbot://app/v1/agent?id=$agentId")).setPackage(GROK_BOT))
    ctx.packageManager.getLaunchIntentForPackage(GROK_BOT)?.let { add(it) }
    add(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(GROK_BOT, "$GROK_BOT.MainActivity")))
  }

  fun launchGrokBot(ctx: Context, agentId: String): String? = grokBotIntents(ctx, agentId).firstNotNullOfOrNull { i ->
    if (i.resolveActivity(ctx.packageManager) == null) null
    else runCatching { ctx.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)); i.data?.path ?: "launcher" }.getOrNull()
  }

  fun end(ctx: Context, call: IncomingCall, outcome: String) {
    ctx.getSystemService(NotificationManager::class.java).cancel(call.id, NOTIFICATION_ID)
    BotEvents.post(ctx, call.id, outcome)
  }
}

class CallHandler(private val ctx: Context) : MethodGroup {
  override val methods = mapOf("start_call" runs ::ring)
  private val nm = ctx.getSystemService(NotificationManager::class.java)

  private fun ring(p: JSONObject): JSONObject {
    Requirement.PostNotifications.check(ctx)
    val now = System.currentTimeMillis()
    val call = IncomingCall.parse(p, now)
    Calls.channel(nm)
    val screen = PendingIntent.getActivity(ctx, call.id.hashCode(), call.into(Intent(ctx, IncomingCallActivity::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val answer = PendingIntent.getActivity(ctx, call.id.hashCode() + 1, call.into(Intent(ctx, IncomingCallActivity::class.java).setAction(Calls.ACTION_ANSWER)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val decline = PendingIntent.getBroadcast(ctx, call.id.hashCode() + 2, call.into(Intent(ctx, CallDeclineReceiver::class.java)), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val b = Notification.Builder(ctx, Calls.CHANNEL).setSmallIcon(R.drawable.ic_stat_bridge).setContentTitle(call.caller).setContentText(call.reason)
      .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setContentIntent(screen).setFullScreenIntent(screen, true)
      .setTimeoutAfter(call.deadline - now)
    if (Build.VERSION.SDK_INT >= 31) b.setStyle(Notification.CallStyle.forIncomingCall(Person.Builder().setName(call.caller).setImportant(true).build(), decline, answer))
    else b.addAction(Notification.Action.Builder(null, "Decline", decline).build()).addAction(Notification.Action.Builder(null, "Answer", answer).build())
    val n = b.build().apply { flags = flags or Notification.FLAG_INSISTENT }
    nm.notify(call.id, Calls.NOTIFICATION_ID, n)
    return JSONObject().put("call_id", call.id).put("ringing", true).put("full_screen", Calls.canFullScreen(ctx))
      .put("grok_bot_installed", Calls.grokBotIntents(ctx, call.agentId).any { it.resolveActivity(ctx.packageManager) != null })
  }
}

class CallDeclineReceiver : BroadcastReceiver() {
  override fun onReceive(ctx: Context, intent: Intent) {
    val call = IncomingCall.from(intent) ?: return
    val pending = goAsync()
    ctx.getSystemService(NotificationManager::class.java).cancel(call.id, Calls.NOTIFICATION_ID)
    BotEvents.post(ctx, call.id, "declined") { pending.finish() }
  }
}

private val CallBlack = Color(0xFF000000)
private val CallSurface = Color(0x14FFFFFF)
private val CallText = Color(0xF2FFFFFF)
private val CallMuted = Color(0x80FFFFFF)
private val CallAccent = Color(0xFF584AC0)
private val AnswerGreen = Color(0xFF3FA266)
private val DeclineRed = Color(0xFFDF3338)

class IncomingCallActivity : ComponentActivity() {
  private var call by mutableStateOf<IncomingCall?>(null)
  private var status by mutableStateOf<String?>(null)
  private var ended = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    accept(intent)
    setContent { call?.let { CallScreen(it, status, ::answer, ::decline) } }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    accept(intent)
  }

  private fun accept(i: Intent) {
    val c = IncomingCall.from(i) ?: return finish()
    if (c.deadline < System.currentTimeMillis()) return finish()
    call = c; ended = false
    if (i.action == Calls.ACTION_ANSWER) answer()
  }

  private fun answer() {
    val c = call ?: return
    if (!ended) { ended = true; Calls.end(this, c, "answered") }
    val km = getSystemService(KeyguardManager::class.java)
    if (!km.isKeyguardLocked) return handOff(c)
    status = "Unlock to talk"
    km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
      override fun onDismissSucceeded() { handOff(c) }
      override fun onDismissCancelled() { status = "Unlock cancelled. Tap Answer to try again" }
      override fun onDismissError() { status = "Couldn't unlock. Tap Answer to try again" }
    })
  }

  private fun handOff(c: IncomingCall) {
    if (Calls.launchGrokBot(this, c.agentId) == null) status = "Grok Bot is not installed" else finish()
  }

  private fun decline() {
    call?.let { if (!ended) { ended = true; Calls.end(this, it, "declined") } }
    finish()
  }

  @Composable
  private fun CallScreen(c: IncomingCall, status: String?, onAnswer: () -> Unit, onDecline: () -> Unit) {
    LaunchedEffect(c.id) { delay((c.deadline - System.currentTimeMillis()).coerceAtLeast(0)); if (!ended) finish() }
    val pulse = rememberInfiniteTransition(label = "ring").animateFloat(1f, 1.35f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "pulse")
    val fade = rememberInfiniteTransition(label = "fade").animateFloat(0.5f, 0f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "alpha")
    Box(Modifier.fillMaxSize().background(CallBlack).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 32.dp, vertical = 48.dp)) {
      Column(Modifier.align(Alignment.TopCenter).padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Incoming call", color = CallMuted, fontSize = 15.sp, letterSpacing = 0.5.sp)
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
          Box(Modifier.size(120.dp).scale(pulse.value).border(2.dp, CallAccent.copy(alpha = fade.value), CircleShape))
          Box(Modifier.size(120.dp).background(CallSurface, CircleShape).border(1.dp, Color(0x33FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
            Text(c.caller.take(1).uppercase(), color = CallText, fontSize = 52.sp, fontWeight = FontWeight.Medium)
          }
        }
        Text(c.caller, color = CallText, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(c.reason, color = CallMuted, fontSize = 18.sp, textAlign = TextAlign.Center, lineHeight = 24.sp)
        status?.let { Text(it, color = CallText, fontSize = 15.sp) }
      }
      Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        RoundAction(Icons.Filled.CallEnd, "Decline", DeclineRed, onDecline)
        RoundAction(Icons.Filled.Call, "Answer", AnswerGreen, onAnswer)
      }
    }
  }

  @Composable
  private fun RoundAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
      FilledIconButton(onClick = onClick, modifier = Modifier.size(76.dp), shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White)) {
        Icon(icon, label, Modifier.size(34.dp))
      }
      Text(label, color = CallText, fontSize = 14.sp)
    }
  }
}
