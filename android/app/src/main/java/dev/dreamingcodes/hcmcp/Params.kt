package dev.dreamingcodes.hcmcp

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun JSONObject.zone(): ZoneId = try { ZoneId.of(optString("timezone", "Europe/Amsterdam")) } catch (e: Exception) { throw BadParams("invalid timezone") }

fun JSONObject.instantMs(key: String): Long? = optString(key).takeIf { it.isNotEmpty() }?.let {
  try { OffsetDateTime.parse(it).toInstant().toEpochMilli() } catch (e: Exception) { throw BadParams("invalid $key") }
}

private val ISO_MS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSxxx")

fun iso(i: Instant, tz: ZoneId): String = ISO_MS.format(i.atZone(tz))

fun iso(epochMs: Long, tz: ZoneId): String = iso(Instant.ofEpochMilli(epochMs), tz)
