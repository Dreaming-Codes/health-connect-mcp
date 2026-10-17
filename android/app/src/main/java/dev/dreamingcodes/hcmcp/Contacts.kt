package dev.dreamingcodes.hcmcp

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.PhoneLookup
import org.json.JSONArray
import org.json.JSONObject

class ContactsHandler(private val ctx: Context) : MethodGroup {
  override val methods = setOf("search_contacts")
  private val cr = ctx.contentResolver

  companion object {
    private val NUMBER_LIKE = Regex("^\\+?[0-9 ()-]{3,}$")
    fun granted(ctx: Context) = ctx.has(Manifest.permission.READ_CONTACTS)
  }

  override suspend fun handle(method: String, p: JSONObject): Any {
    if (!granted(ctx)) throw PhoneFailure("contacts_permission_missing", "contacts permission is not granted; open the app and tap Allow contacts")
    val query = p.optString("query").trim().ifEmpty { throw BadParams("query required") }
    val limit = p.optInt("limit", 10).coerceIn(1, 50)
    val matches = if (NUMBER_LIKE.matches(query)) lookup(PhoneLookup.CONTENT_FILTER_URI, query, PhoneLookup._ID, PhoneLookup.DISPLAY_NAME)
      else lookup(Contacts.CONTENT_FILTER_URI, query, Contacts._ID, Contacts.DISPLAY_NAME_PRIMARY)
    return JSONObject().put("contacts", JSONArray(matches.take(limit).map { (id, name) ->
      JSONObject().put("id", id).put("name", name).put("phones", JSONArray(phones(id))).put("emails", JSONArray(emails(id)))
    }))
  }

  private fun lookup(base: Uri, query: String, idCol: String, nameCol: String): List<Pair<Long, String?>> =
    cr.query(Uri.withAppendedPath(base, Uri.encode(query)), arrayOf(idCol, nameCol), null, null, null)?.use { c ->
      buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }.distinctBy { it.first }
    } ?: emptyList()

  private fun phones(id: Long) = cr.query(Phone.CONTENT_URI, arrayOf(Phone.NUMBER, Phone.TYPE, Phone.LABEL), "${Phone.CONTACT_ID}=?", arrayOf(id.toString()), null)?.use { c ->
    buildList { while (c.moveToNext()) add(JSONObject().put("number", c.getString(0)).put("type", Phone.getTypeLabel(ctx.resources, c.getInt(1), c.getString(2)).toString())) }
  } ?: emptyList()

  private fun emails(id: Long) = cr.query(Email.CONTENT_URI, arrayOf(Email.ADDRESS, Email.TYPE, Email.LABEL), "${Email.CONTACT_ID}=?", arrayOf(id.toString()), null)?.use { c ->
    buildList { while (c.moveToNext()) add(JSONObject().put("address", c.getString(0)).put("type", Email.getTypeLabel(ctx.resources, c.getInt(1), c.getString(2)).toString())) }
  } ?: emptyList()
}

class ContactNames(private val ctx: Context) {
  private val enabled = ContactsHandler.granted(ctx)
  private val cache = HashMap<String, String?>()

  fun nameFor(address: String?): String? {
    if (!enabled || address.isNullOrBlank()) return null
    return cache.getOrPut(address) {
      ctx.contentResolver.query(Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)), arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }
  }
}
