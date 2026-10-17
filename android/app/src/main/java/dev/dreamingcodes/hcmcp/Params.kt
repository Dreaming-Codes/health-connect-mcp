package dev.dreamingcodes.hcmcp

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

fun JSONObject.zone(): ZoneId = try { ZoneId.of(optString("timezone", "Europe/Amsterdam")) } catch (e: Exception) { throw BadParams("invalid timezone") }

fun JSONObject.instantMs(key: String): Long? = optString(key).takeIf { it.isNotEmpty() }?.let {
  try { OffsetDateTime.parse(it).toInstant().toEpochMilli() } catch (e: Exception) { throw BadParams("invalid $key") }
}

fun iso(i: Instant, tz: ZoneId): String = i.atZone(tz).toOffsetDateTime().toString()

fun iso(epochMs: Long, tz: ZoneId): String = iso(Instant.ofEpochMilli(epochMs), tz)
