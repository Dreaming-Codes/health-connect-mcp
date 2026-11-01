package dev.dreamingcodes.hcmcp

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup

class ContactNames(private val ctx: Context) {
  private val enabled = Capabilities.CONTACTS.all { ctx.has(it) }
  private val cache = HashMap<String, String?>()

  fun nameFor(address: String?): String? {
    if (!enabled || address.isNullOrBlank()) return null
    return cache.getOrPut(address) {
      ctx.contentResolver.query(Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)), arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }
  }
}
