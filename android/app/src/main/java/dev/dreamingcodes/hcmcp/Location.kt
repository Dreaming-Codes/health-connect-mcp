package dev.dreamingcodes.hcmcp

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.Instant
import kotlin.coroutines.resume

class LocationHandler(private val ctx: Context) : MethodGroup {
  override val methods = setOf("get_location")
  private val lm = ctx.getSystemService(LocationManager::class.java)

  override suspend fun handle(method: String, p: JSONObject): Any {
    if (!Capabilities.location(ctx)) throw PhoneFailure("location_permission_missing", "location permission is not granted; open the app and tap Allow location")
    if (!Capabilities.backgroundLocation(ctx)) throw PhoneFailure("background_location_missing", "location is only allowed while the app is open; set it to Allow all the time")
    if (!lm.isLocationEnabled) throw PhoneFailure("location_disabled", "location is turned off on the phone")
    val maxAgeMs = p.optLong("max_age_s", 120).coerceIn(0, 86_400) * 1000
    val timeoutMs = p.optLong("timeout_s", 10).coerceIn(1, 12) * 1000
    val last = lastKnown()
    if (last != null && ageMs(last) <= maxAgeMs) return describe(last, "last_known")
    val current = withTimeoutOrNull(timeoutMs) { current() }
    return when {
      current != null -> describe(current, "current")
      last != null -> describe(last, "last_known")
      else -> throw PhoneFailure("location_unavailable", "no location fix within ${timeoutMs / 1000}s and no cached fix")
    }
  }

  private fun providers(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
    add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER)
  }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

  @Suppress("MissingPermission")
  private fun lastKnown(): Location? = providers().mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }

  @Suppress("MissingPermission", "DEPRECATION")
  private suspend fun current(): Location? {
    val provider = providers().firstOrNull() ?: return null
    return suspendCancellableCoroutine { cont ->
      if (Build.VERSION.SDK_INT >= 30) {
        val cancel = CancellationSignal()
        cont.invokeOnCancellation { cancel.cancel() }
        lm.getCurrentLocation(provider, cancel, ctx.mainExecutor) { cont.resume(it) }
      } else {
        val listener = android.location.LocationListener { if (cont.isActive) cont.resume(it) }
        cont.invokeOnCancellation { lm.removeUpdates(listener) }
        lm.requestSingleUpdate(provider, listener, ctx.mainLooper)
      }
    }
  }

  private fun ageMs(l: Location) = System.currentTimeMillis() - l.time

  private fun describe(l: Location, source: String) = JSONObject()
    .put("latitude", l.latitude).put("longitude", l.longitude)
    .put("accuracy_m", if (l.hasAccuracy()) l.accuracy.toDouble() else null)
    .put("altitude_m", if (l.hasAltitude()) l.altitude else null)
    .put("speed_mps", if (l.hasSpeed()) l.speed.toDouble() else null)
    .put("bearing_deg", if (l.hasBearing()) l.bearing.toDouble() else null)
    .put("time", Instant.ofEpochMilli(l.time).toString()).put("age_s", ageMs(l) / 1000)
    .put("provider", l.provider).put("source", source)
}
