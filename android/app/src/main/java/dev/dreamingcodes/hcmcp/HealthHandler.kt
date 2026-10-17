package dev.dreamingcodes.hcmcp

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import kotlin.reflect.KClass

class HealthHandler(private val ctx: Context) : MethodGroup {
  private val client by lazy { HealthConnectClient.getOrCreate(ctx) }

  companion object {
    const val BACKGROUND_PERM = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
    fun backgroundAvailable(c: HealthConnectClient) =
      c.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    fun requestedPermissions(background: Boolean): Set<String> =
      READ_PERMS + (if (background) setOf(BACKGROUND_PERM) else emptySet()) + TestData.writePermissions()

    val TYPES: Map<String, KClass<out Record>> = mapOf(
      "Steps" to StepsRecord::class, "HeartRate" to HeartRateRecord::class, "RestingHeartRate" to RestingHeartRateRecord::class,
      "SleepSession" to SleepSessionRecord::class, "ExerciseSession" to ExerciseSessionRecord::class,
      "Distance" to DistanceRecord::class, "ActiveCaloriesBurned" to ActiveCaloriesBurnedRecord::class,
      "TotalCaloriesBurned" to TotalCaloriesBurnedRecord::class, "Weight" to WeightRecord::class,
      "Nutrition" to NutritionRecord::class, "Hydration" to HydrationRecord::class,
    )
    val READ_PERMS: Set<String> = TYPES.values.map { HealthPermission.getReadPermission(it) }.toSet()
    private val SLEEP_STAGES = mapOf(1 to "awake", 2 to "sleeping", 3 to "out_of_bed", 4 to "light", 5 to "deep", 6 to "rem", 7 to "awake_in_bed", 0 to "unknown")
    private val EXERCISE = mapOf(56 to "running", 79 to "walking", 8 to "biking", 9 to "biking_stationary", 74 to "swimming_pool",
      73 to "swimming_open_water", 70 to "strength_training", 80 to "weightlifting", 37 to "hiking", 83 to "yoga", 25 to "elliptical",
      61 to "rowing_machine", 0 to "other_workout", 36 to "high_intensity_interval_training", 57 to "running_treadmill")
  }

  private class Range(val start: Instant, val end: Instant, val tz: ZoneId) {
    val filter get() = TimeRangeFilter.between(start, end)
    val localFilter get() = TimeRangeFilter.between(LocalDateTime.ofInstant(start, tz), LocalDateTime.ofInstant(end, tz))
  }

  private fun range(p: JSONObject): Range {
    fun inst(k: String): Instant = try { OffsetDateTime.parse(p.getString(k)).toInstant() }
      catch (e: Exception) { try { Instant.parse(p.getString(k)) } catch (e2: Exception) { throw BadParams("invalid $k") } }
    val tz = p.zone()
    val r = Range(inst("start"), inst("end"), tz)
    if (!r.end.isAfter(r.start)) throw BadParams("end must be after start")
    return r
  }

  override val methods = mapOf(
    "get_phone_status" runs { status() },
    "get_steps" runs { steps(range(it), it.optString("bucket", "day")) },
    "get_heart_rate" runs { heartRate(range(it), it.optString("bucket", "day")) },
    "get_sleep" runs { sleep(range(it)) },
    "get_exercise_sessions" runs { exercise(range(it)) },
    "get_weight" runs { weight(range(it)) },
    "get_nutrition" runs { nutrition(range(it)) },
    "read_records" runs { readGeneric(it) },
  )

  private suspend fun <T : Record> readAll(type: KClass<T>, f: TimeRangeFilter, limit: Int = 5000): List<T> {
    val out = mutableListOf<T>(); var token: String? = null
    do {
      val resp = client.readRecords(ReadRecordsRequest(type, f, pageSize = 1000, pageToken = token))
      out += resp.records; token = resp.pageToken
    } while (token != null && out.size < limit)
    return out.take(limit)
  }

  private suspend fun status(): JSONObject {
    val bm = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = bm?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).toDouble() / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) }
    val sdk = HealthConnectClient.getSdkStatus(ctx)
    val o = JSONObject().put("app_version", BuildConfig.VERSION_NAME).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
      .put("android_sdk", Build.VERSION.SDK_INT).put("battery", level)
      .put("capabilities", Capabilities.json(ctx))
      .put("health_connect", when (sdk) { HealthConnectClient.SDK_AVAILABLE -> "available"; HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update_required"; else -> "unavailable" })
    if (sdk == HealthConnectClient.SDK_AVAILABLE) {
      val granted = client.permissionController.getGrantedPermissions()
      o.put("granted", JSONArray(granted.filter { it in READ_PERMS }.map { it.substringAfterLast('.') }.sorted()))
      o.put("missing", JSONArray((READ_PERMS - granted).map { it.substringAfterLast('.') }.sorted()))
    }
    return o
  }

  private suspend fun steps(r: Range, bucket: String): JSONObject {
    val o = JSONObject().put("unit", "steps").put("timezone", r.tz.id).put("bucket", bucket)
    val total = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), r.filter))[StepsRecord.COUNT_TOTAL] ?: 0L
    o.put("total", total)
    val arr = JSONArray()
    when (bucket) {
      "day" -> client.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(setOf(StepsRecord.COUNT_TOTAL), r.localFilter, Period.ofDays(1))).forEach {
        arr.put(JSONObject().put("date", it.startTime.toLocalDate().toString()).put("steps", it.result[StepsRecord.COUNT_TOTAL] ?: 0L)) }
      "hour" -> client.aggregateGroupByDuration(AggregateGroupByDurationRequest(setOf(StepsRecord.COUNT_TOTAL), r.filter, Duration.ofHours(1))).forEach {
        arr.put(JSONObject().put("start", iso(it.startTime, r.tz)).put("steps", it.result[StepsRecord.COUNT_TOTAL] ?: 0L)) }
    }
    if (bucket != "none") o.put("buckets", arr)
    return o
  }

  private suspend fun heartRate(r: Range, bucket: String): JSONObject {
    val m = setOf(HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX, HeartRateRecord.MEASUREMENTS_COUNT)
    fun res(a: androidx.health.connect.client.aggregate.AggregationResult) = JSONObject()
      .put("min_bpm", a[HeartRateRecord.BPM_MIN]).put("avg_bpm", a[HeartRateRecord.BPM_AVG]).put("max_bpm", a[HeartRateRecord.BPM_MAX])
      .put("samples", a[HeartRateRecord.MEASUREMENTS_COUNT] ?: 0L)
    val o = JSONObject().put("unit", "bpm").put("timezone", r.tz.id).put("bucket", bucket).put("overall", res(client.aggregate(AggregateRequest(m, r.filter))))
    val arr = JSONArray()
    when (bucket) {
      "day" -> client.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(m, r.localFilter, Period.ofDays(1))).forEach {
        if ((it.result[HeartRateRecord.MEASUREMENTS_COUNT] ?: 0L) > 0) arr.put(res(it.result).put("date", it.startTime.toLocalDate().toString())) }
      "hour" -> client.aggregateGroupByDuration(AggregateGroupByDurationRequest(m, r.filter, Duration.ofHours(1))).forEach {
        if ((it.result[HeartRateRecord.MEASUREMENTS_COUNT] ?: 0L) > 0) arr.put(res(it.result).put("start", iso(it.startTime, r.tz))) }
    }
    if (bucket != "none") o.put("buckets", arr)
    val resting = try { readAll(RestingHeartRateRecord::class, r.filter, 500) } catch (e: SecurityException) { emptyList() }
    o.put("resting", JSONArray(resting.map { JSONObject().put("time", iso(it.time, r.tz)).put("bpm", it.beatsPerMinute) }))
    return o
  }

  private suspend fun sleep(r: Range): JSONObject {
    val arr = JSONArray()
    readAll(SleepSessionRecord::class, r.filter).sortedBy { it.startTime }.forEach { s ->
      val stages = JSONObject()
      s.stages.groupBy { SLEEP_STAGES[it.stage] ?: "unknown" }.forEach { (k, v) -> stages.put(k, v.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }) }
      arr.put(JSONObject().put("start", iso(s.startTime, r.tz)).put("end", iso(s.endTime, r.tz))
        .put("night_of", s.startTime.atZone(r.tz).minusHours(12).toLocalDate().toString())
        .put("duration_min", Duration.between(s.startTime, s.endTime).toMinutes()).put("title", s.title).put("stage_minutes", stages)
        .put("source", s.metadata.dataOrigin.packageName))
    }
    return JSONObject().put("timezone", r.tz.id).put("sessions", arr)
  }

  private suspend fun exercise(r: Range): JSONObject {
    val arr = JSONArray()
    readAll(ExerciseSessionRecord::class, r.filter).sortedBy { it.startTime }.forEach { s ->
      val f = TimeRangeFilter.between(s.startTime, s.endTime)
      val agg = try { client.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
        HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX, StepsRecord.COUNT_TOTAL), f)) } catch (e: Exception) { null }
      arr.put(JSONObject().put("start", iso(s.startTime, r.tz)).put("end", iso(s.endTime, r.tz))
        .put("duration_min", Duration.between(s.startTime, s.endTime).toMinutes())
        .put("type", EXERCISE[s.exerciseType] ?: "type_${s.exerciseType}").put("title", s.title)
        .put("distance_m", agg?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters)
        .put("active_kcal", agg?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories)
        .put("avg_bpm", agg?.get(HeartRateRecord.BPM_AVG)).put("max_bpm", agg?.get(HeartRateRecord.BPM_MAX))
        .put("steps", agg?.get(StepsRecord.COUNT_TOTAL)).put("source", s.metadata.dataOrigin.packageName))
    }
    return JSONObject().put("timezone", r.tz.id).put("sessions", arr)
  }

  private suspend fun weight(r: Range) = JSONObject().put("unit", "kg").put("timezone", r.tz.id).put("records",
    JSONArray(readAll(WeightRecord::class, r.filter).sortedBy { it.time }.map { JSONObject().put("time", iso(it.time, r.tz)).put("kg", it.weight.inKilograms) }))

  private suspend fun nutrition(r: Range): JSONObject {
    val entries = readAll(NutritionRecord::class, r.filter, 1000).sortedBy { it.startTime }
    val days = JSONArray()
    entries.groupBy { it.startTime.atZone(r.tz).toLocalDate() }.toSortedMap().forEach { (d, l) ->
      days.put(JSONObject().put("date", d.toString()).put("kcal", l.sumOf { it.energy?.inKilocalories ?: 0.0 })
        .put("protein_g", l.sumOf { it.protein?.inGrams ?: 0.0 }).put("carbs_g", l.sumOf { it.totalCarbohydrate?.inGrams ?: 0.0 })
        .put("fat_g", l.sumOf { it.totalFat?.inGrams ?: 0.0 }).put("entries", l.size))
    }
    return JSONObject().put("timezone", r.tz.id).put("days", days).put("entries", JSONArray(entries.map { recordJson(it, r.tz) }))
  }

  private suspend fun readGeneric(p: JSONObject): JSONObject {
    val r = range(p); val name = p.optString("recordType")
    val type = TYPES[name] ?: throw BadParams("unsupported recordType $name (supported: ${TYPES.keys.joinToString()})")
    val recs = readAll(type, r.filter, p.optInt("limit", 200).coerceIn(1, 1000))
    return JSONObject().put("recordType", name).put("count", recs.size).put("records", JSONArray(recs.map { recordJson(it, r.tz) }))
  }

  private fun recordJson(rec: Record, tz: ZoneId): JSONObject {
    val o = JSONObject().put("source", rec.metadata.dataOrigin.packageName)
    fun at(t: Instant) = o.put("time", iso(t, tz))
    fun iv(s: Instant, e: Instant) = o.put("start", iso(s, tz)).put("end", iso(e, tz))
    when (rec) {
      is StepsRecord -> iv(rec.startTime, rec.endTime).put("count", rec.count)
      is HeartRateRecord -> iv(rec.startTime, rec.endTime).put("samples", JSONArray(rec.samples.map { JSONObject().put("time", iso(it.time, tz)).put("bpm", it.beatsPerMinute) }))
      is RestingHeartRateRecord -> at(rec.time).put("bpm", rec.beatsPerMinute)
      is SleepSessionRecord -> iv(rec.startTime, rec.endTime).put("stages", JSONArray(rec.stages.map { JSONObject().put("stage", SLEEP_STAGES[it.stage]).put("start", iso(it.startTime, tz)).put("end", iso(it.endTime, tz)) }))
      is ExerciseSessionRecord -> iv(rec.startTime, rec.endTime).put("type", EXERCISE[rec.exerciseType] ?: "type_${rec.exerciseType}").put("title", rec.title)
      is DistanceRecord -> iv(rec.startTime, rec.endTime).put("meters", rec.distance.inMeters)
      is ActiveCaloriesBurnedRecord -> iv(rec.startTime, rec.endTime).put("kcal", rec.energy.inKilocalories)
      is TotalCaloriesBurnedRecord -> iv(rec.startTime, rec.endTime).put("kcal", rec.energy.inKilocalories)
      is WeightRecord -> at(rec.time).put("kg", rec.weight.inKilograms)
      is HydrationRecord -> iv(rec.startTime, rec.endTime).put("liters", rec.volume.inLiters)
      is NutritionRecord -> iv(rec.startTime, rec.endTime).put("name", rec.name).put("meal_type", rec.mealType).put("kcal", rec.energy?.inKilocalories)
        .put("protein_g", rec.protein?.inGrams).put("carbs_g", rec.totalCarbohydrate?.inGrams).put("fat_g", rec.totalFat?.inGrams)
    }
    return o
  }
}
