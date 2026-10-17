package dev.dreamingcodes.hcmcp

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class SmsBox(val smsType: Int?, val mmsBox: Int?) { all(null, null), inbox(1, 1), sent(2, 2) }

data class SmsThread(val id: Long, val addresses: List<String>, val snippet: String?, val date: Long, val messageCount: Int)

data class Message(val id: Long, val kind: String, val threadId: Long, val address: String?, val date: Long, val box: Int, val read: Boolean, val text: String?)

class MessagesHandler(private val ctx: Context) : MethodGroup {
  override val methods = mapOf(
    "list_threads" runs { requireRead(); listThreads(it) },
    "list_messages" runs { requireRead(); listMessages(it) },
    "send_message" runs { send(it.optString("to").ifEmpty { throw BadParams("to required") }, text(it)) },
    "reply_to_thread" runs { requireRead(); reply(it.optLong("thread_id"), text(it)) },
  )
  private val cr = ctx.contentResolver

  companion object {
    private val CONVERSATIONS: Uri = Uri.parse("content://mms-sms/conversations?simple=true")
    private val CANONICAL: Uri = Uri.parse("content://mms-sms/canonical-addresses")
    private const val MMS_FROM = 137
    private const val MMS_TO = 151
    private const val SENT_WAIT_MS = 8_000L
    private const val DELIVERY_WAIT_MS = 4_000L
    private val BOX_NAMES = mapOf(1 to "inbox", 2 to "sent", 3 to "draft", 4 to "outbox", 5 to "failed", 6 to "queued")
  }

  private fun text(p: JSONObject) = p.optString("text").ifEmpty { throw BadParams("text required") }
  private fun requireRead() = Requirement.ReadSms.check(ctx)

  private fun canonicalAddresses(): Map<Long, String> = cr.query(CANONICAL, arrayOf("_id", "address"), null, null, null)?.use { c ->
    buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1)) }
  } ?: emptyMap()

  private fun threads(selection: String? = null, args: Array<String>? = null, limit: Int = Int.MAX_VALUE): List<SmsThread> {
    val addrs = canonicalAddresses()
    return cr.query(CONVERSATIONS, arrayOf("_id", "recipient_ids", "snippet", "date", "message_count"), selection, args, "date DESC")?.use { c ->
      buildList {
        while (size < limit && c.moveToNext()) {
          val ids = (c.getString(1) ?: "").split(' ').mapNotNull { it.toLongOrNull() }
          add(SmsThread(c.getLong(0), ids.mapNotNull { addrs[it] }, c.getString(2), c.getLong(3), c.getInt(4)))
        }
      }
    } ?: emptyList()
  }

  private fun listThreads(p: JSONObject): JSONObject {
    val tz = p.zone(); val names = ContactNames(ctx)
    val list = threads(limit = p.optInt("limit", 20).coerceIn(1, 100))
    return JSONObject().put("timezone", tz.id).put("threads", JSONArray(list.map { t ->
      JSONObject().put("thread_id", t.id).put("addresses", JSONArray(t.addresses))
        .put("names", JSONArray(t.addresses.map { names.nameFor(it) ?: JSONObject.NULL }))
        .put("group", t.addresses.size > 1).put("snippet", t.snippet).put("date", iso(t.date, tz)).put("message_count", t.messageCount)
    }))
  }

  private fun listMessages(p: JSONObject): JSONObject {
    val tz = p.zone(); val names = ContactNames(ctx)
    val box = runCatching { SmsBox.valueOf(p.optString("box", "all")) }.getOrElse { throw BadParams("invalid box") }
    val limit = p.optInt("limit", 50).coerceIn(1, 500)
    val thread = p.optLong("thread_id", 0).takeIf { it > 0 }
    val digits = p.optString("address").filter { it.isDigit() }.takeLast(7).takeIf { it.isNotEmpty() }
    val since = p.instantMs("since"); val until = p.instantMs("until")
    val messages = (sms(box, thread, digits, since, until, limit) + mms(box, thread, digits, since, until, limit)).sortedByDescending { it.date }.take(limit)
    return JSONObject().put("timezone", tz.id).put("count", messages.size).put("messages", JSONArray(messages.map { m ->
      JSONObject().put("id", m.id).put("kind", m.kind).put("thread_id", m.threadId).put("address", m.address)
        .put("contact_name", names.nameFor(m.address)).put("date", iso(m.date, tz)).put("box", BOX_NAMES[m.box] ?: "other")
        .put("read", m.read).put("text", m.text)
    }))
  }

  private fun where(vararg parts: Pair<String, String>?): Pair<String?, Array<String>> {
    val ps = parts.filterNotNull()
    return (ps.joinToString(" AND ") { it.first }.ifEmpty { null }) to ps.map { it.second }.toTypedArray()
  }

  private fun sms(box: SmsBox, thread: Long?, digits: String?, since: Long?, until: Long?, limit: Int): List<Message> {
    val (sel, args) = where(box.smsType?.let { "type=?" to "$it" }, thread?.let { "thread_id=?" to "$it" },
      digits?.let { "address LIKE ?" to "%$it" }, since?.let { "date>=?" to "$it" }, until?.let { "date<?" to "$it" })
    return cr.query(Telephony.Sms.CONTENT_URI, arrayOf("_id", "thread_id", "address", "date", "type", "read", "body"), sel, args, "date DESC")?.use { c ->
      buildList { while (size < limit && c.moveToNext()) add(Message(c.getLong(0), "sms", c.getLong(1), c.getString(2), c.getLong(3), c.getInt(4), c.getInt(5) == 1, c.getString(6))) }
    } ?: emptyList()
  }

  private fun mms(box: SmsBox, thread: Long?, digits: String?, since: Long?, until: Long?, limit: Int): List<Message> {
    val (sel, args) = where(box.mmsBox?.let { "msg_box=?" to "$it" }, thread?.let { "thread_id=?" to "$it" },
      since?.let { "date>=?" to "${it / 1000}" }, until?.let { "date<?" to "${(it + 999) / 1000}" })
    val rows = cr.query(Telephony.Mms.CONTENT_URI, arrayOf("_id", "thread_id", "date", "msg_box", "read"), sel, args, "date DESC")?.use { c ->
      buildList { while (size < limit && c.moveToNext()) add(Message(c.getLong(0), "mms", c.getLong(1), null, c.getLong(2) * 1000, c.getInt(3), c.getInt(4) == 1, null)) }
    } ?: emptyList()
    return rows.map { it.copy(address = mmsAddress(it.id, it.box), text = mmsText(it.id)) }
      .filter { m -> digits == null || (m.address?.filter { it.isDigit() }?.endsWith(digits) == true) }
  }

  private fun mmsAddress(id: Long, box: Int): String? = cr.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), "type=?",
    arrayOf(if (box == 1) "$MMS_FROM" else "$MMS_TO"), null)?.use { if (it.moveToFirst()) it.getString(0) else null }

  private fun mmsText(id: Long): String? = cr.query(Uri.parse("content://mms/part"), arrayOf("text"), "mid=? AND ct=?", arrayOf("$id", "text/plain"), null)
    ?.use { c -> buildList { while (c.moveToNext()) c.getString(0)?.let { add(it) } }.joinToString("\n").ifEmpty { null } }

  private suspend fun reply(threadId: Long, text: String): JSONObject {
    if (threadId <= 0) throw BadParams("thread_id required")
    val t = threads("_id=?", arrayOf("$threadId"), 1).firstOrNull() ?: throw PhoneFailure("not_found", "no thread with that id")
    if (t.addresses.size != 1) throw PhoneFailure("group_thread", "thread has ${t.addresses.size} recipients; only one-to-one threads can be replied to")
    return send(t.addresses.single(), text).put("thread_id", threadId)
  }

  private suspend fun send(to: String, text: String): JSONObject {
    Requirement.SendSms.check(ctx)
    @Suppress("DEPRECATION")
    val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
    val parts = sms.divideMessage(text)
    val nonce = UUID.randomUUID().toString()
    val sentAction = "dev.dreamingcodes.hcmcp.SMS_SENT.$nonce"; val deliveredAction = "dev.dreamingcodes.hcmcp.SMS_DELIVERED.$nonce"
    val sent = List(parts.size) { CompletableDeferred<Int>() }
    val delivered = List(parts.size) { CompletableDeferred<Boolean>() }
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, i: Intent) {
        val part = i.getIntExtra("part", -1).takeIf { it in parts.indices } ?: return
        if (i.action == sentAction) sent[part].complete(resultCode)
        else delivered[part].complete(i.getByteArrayExtra("pdu")?.let { SmsMessage.createFromPdu(it, i.getStringExtra("format")).status == 0 } ?: false)
      }
    }
    val filter = IntentFilter().apply { addAction(sentAction); addAction(deliveredAction) }
    if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(receiver, filter)
    try {
      fun pi(action: String, part: Int) = PendingIntent.getBroadcast(ctx, part, Intent(action).setPackage(ctx.packageName).putExtra("part", part),
        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
      sms.sendMultipartTextMessage(to, null, parts, ArrayList(parts.indices.map { pi(sentAction, it) }), ArrayList(parts.indices.map { pi(deliveredAction, it) }))
      val codes = withTimeoutOrNull(SENT_WAIT_MS) { sent.awaitAll() }
      val sentState = when { codes == null -> "pending"; codes.all { it == Activity.RESULT_OK } -> "sent"; else -> "failed" }
      val deliveredState = if (sentState != "sent") "pending" else withTimeoutOrNull(DELIVERY_WAIT_MS) { delivered.awaitAll() }
        ?.let { if (it.all { ok -> ok }) "delivered" else "failed" } ?: "pending"
      val out = JSONObject().put("to", to).put("parts", parts.size).put("sent", sentState).put("delivered", deliveredState)
      codes?.firstOrNull { it != Activity.RESULT_OK }?.let { out.put("error_code", it) }
      return out
    } finally { ctx.unregisterReceiver(receiver) }
  }
}
