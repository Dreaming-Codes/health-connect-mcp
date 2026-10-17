package dev.dreamingcodes.hcmcp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.ZoneId
import kotlin.coroutines.resume

class LocationHandler(private val ctx: Context) : MethodGroup {
  override val methods = mapOf("get_location" runs ::locate)
  private val lm = ctx.getSystemService(LocationManager::class.java)

  private suspend fun locate(p: JSONObject): JSONObject {
    Requirement.Location.check(ctx)
    Requirement.BackgroundLocation.check(ctx)
    if (!lm.isLocationEnabled) throw PhoneFailure("location_disabled", "location is turned off on the phone")
    val maxAgeMs = p.optLong("max_age_s", 120).coerceIn(0, 86_400) * 1000
    val timeoutMs = p.optLong("timeout_s", 10).coerceIn(1, 12) * 1000
    val tz = p.zone()
    val last = lastKnown()
    if (last != null && ageMs(last) <= maxAgeMs) return describe(last, "last_known", tz)
    val current = withTimeoutOrNull(timeoutMs) { current() }
    return when {
      current != null -> describe(current, "current", tz)
      last != null -> describe(last, "last_known", tz)
      else -> throw PhoneFailure("location_unavailable", "no location fix within ${timeoutMs / 1000}s and no cached fix")
    }
  }

  private fun providers(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
    add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER)
  }.filter { permitted(it) && runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

  // Before Android 12, the GPS provider throws SecurityException for an app with only approximate location.
  private fun permitted(provider: String) =
    Build.VERSION.SDK_INT >= 31 || provider != LocationManager.GPS_PROVIDER || ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

  private fun lastKnown(): Location? = providers().mapNotNull { provider ->
    try { lm.getLastKnownLocation(provider) } catch (e: SecurityException) { throw locationDenied() }
  }.maxByOrNull { it.time }

  @Suppress("DEPRECATION")
  private suspend fun current(): Location? {
    val provider = providers().firstOrNull() ?: return null
    return suspendCancellableCoroutine { cont ->
      try {
        if (Build.VERSION.SDK_INT >= 30) {
          val cancel = CancellationSignal()
          cont.invokeOnCancellation { cancel.cancel() }
          lm.getCurrentLocation(provider, cancel, ctx.mainExecutor) { cont.resume(it) }
        } else {
          val listener = android.location.LocationListener { if (cont.isActive) cont.resume(it) }
          cont.invokeOnCancellation { lm.removeUpdates(listener) }
          lm.requestSingleUpdate(provider, listener, ctx.mainLooper)
        }
      } catch (e: SecurityException) {
        throw locationDenied()
      }
    }
  }

  private fun locationDenied() = PhoneFailure("location_permission_missing", "location permission was revoked or does not cover this provider; open the app and tap Allow location")

  private fun ageMs(l: Location) = System.currentTimeMillis() - l.time

  private fun describe(l: Location, source: String, tz: ZoneId) = JSONObject()
    .put("latitude", l.latitude).put("longitude", l.longitude)
    .put("accuracy_m", if (l.hasAccuracy()) l.accuracy.toDouble() else null)
    .put("altitude_m", if (l.hasAltitude()) l.altitude else null)
    .put("speed_mps", if (l.hasSpeed()) l.speed.toDouble() else null)
    .put("bearing_deg", if (l.hasBearing()) l.bearing.toDouble() else null)
    .put("time", iso(l.time, tz)).put("age_s", ageMs(l) / 1000)
    .put("provider", l.provider).put("source", source)
}
