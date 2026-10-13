package dev.dreamingcodes.hcmcp

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import java.time.*

/** Deterministic fake records for emulator testing (debug builds only; release lacks WRITE permissions). */
object TestData {
  private val tz = ZoneId.of("Europe/Amsterdam")
  private fun t(d: String, hm: String) = LocalDateTime.parse("${d}T$hm").atZone(tz)
  private fun md(id: String) = Metadata.manualEntry(clientRecordId = "hcbridge-test-$id", clientRecordVersion = 1)

  suspend fun insert(c: HealthConnectClient) {
    val recs = mutableListOf<Record>()
    listOf("2026-10-10" to 6000L, "2026-10-11" to 9500L, "2026-10-12" to 12000L).forEach { (d, n) ->
      val s = t(d, "08:00"); val e = t(d, "20:00")
      recs += StepsRecord(s.toInstant(), s.offset, e.toInstant(), e.offset, n, md("steps-$d"))
    }
    listOf("2026-10-10" to listOf(60L, 70L, 80L), "2026-10-11" to listOf(55L, 65L, 75L), "2026-10-12" to listOf(90L, 100L, 110L)).forEach { (d, bpms) ->
      val s = t(d, "10:00")
      recs += HeartRateRecord(s.toInstant(), s.offset, s.plusMinutes(5).toInstant(), s.offset,
        bpms.mapIndexed { i, b -> HeartRateRecord.Sample(s.plusMinutes(i.toLong()).toInstant(), b) }, md("hr-$d"))
    }
    val n1s = t("2026-10-10", "23:00"); val n1e = t("2026-10-11", "07:00")
    recs += SleepSessionRecord(n1s.toInstant(), n1s.offset, n1e.toInstant(), n1e.offset, metadata = md("sleep-2026-10-10"), title = "Night 1",
      stages = listOf(
        SleepSessionRecord.Stage(n1s.toInstant(), n1s.plusHours(2).toInstant(), SleepSessionRecord.STAGE_TYPE_DEEP),
        SleepSessionRecord.Stage(n1s.plusHours(2).toInstant(), n1s.plusHours(6).toInstant(), SleepSessionRecord.STAGE_TYPE_LIGHT),
        SleepSessionRecord.Stage(n1s.plusHours(6).toInstant(), n1e.toInstant(), SleepSessionRecord.STAGE_TYPE_REM)))
    val n2s = t("2026-10-11", "23:30"); val n2e = t("2026-10-12", "06:30")
    recs += SleepSessionRecord(n2s.toInstant(), n2s.offset, n2e.toInstant(), n2e.offset, metadata = md("sleep-2026-10-11"), title = "Night 2")
    val rs = t("2026-10-12", "07:00")
    recs += ExerciseSessionRecord(rs.toInstant(), rs.offset, rs.plusMinutes(45).toInstant(), rs.offset, metadata = md("run-2026-10-12"),
      exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, title = "Morning run")
    listOf("2026-10-10" to 75.0, "2026-10-12" to 74.4).forEach { (d, kg) ->
      val w = t(d, "07:00"); recs += WeightRecord(w.toInstant(), w.offset, Mass.kilograms(kg), md("weight-$d"))
    }
    val l = t("2026-10-12", "12:30")
    recs += NutritionRecord(l.toInstant(), l.offset, l.plusMinutes(30).toInstant(), l.offset, metadata = md("lunch-2026-10-12"),
      name = "Test lunch", energy = Energy.kilocalories(650.0), protein = Mass.grams(35.0),
      totalCarbohydrate = Mass.grams(70.0), totalFat = Mass.grams(20.0))
    c.insertRecords(recs)
  }
}
