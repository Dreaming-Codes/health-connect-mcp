package dev.dreamingcodes.hcmcp

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
  private lateinit var status: TextView
  private val hcPerms = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh() }
  private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val pad = (16 * resources.displayMetrics.density).toInt()
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
    val url = EditText(this).apply { hint = "Worker URL (https://…workers.dev)"; setText(Settings.workerUrl(this@MainActivity)); inputType = InputType.TYPE_TEXT_VARIATION_URI }
    val token = EditText(this).apply { hint = "Device token"; setText(Settings.deviceToken(this@MainActivity)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
    status = TextView(this)
    fun btn(label: String, f: () -> Unit) = Button(this).apply { text = label; setOnClickListener { f() } }.also { col.addView(it) }
    col.addView(TextView(this).apply { text = "Health Connect → MCP bridge"; textSize = 20f })
    col.addView(url); col.addView(token)
    btn("Save settings") { Settings.save(this, url.text.toString(), token.text.toString()); toast("Saved"); refresh() }
    btn("Grant Health Connect permissions") { hcPerms.launch(HealthHandler.READ_PERMS + writePerms()) }
    btn("Start bridge") {
      if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
      Settings.setEnabled(this, true); BridgeService.start(this); status.postDelayed({ refresh() }, 1500)
    }
    btn("Stop bridge") { Settings.setEnabled(this, false); BridgeService.stop(this); refresh() }
    if (BuildConfig.DEBUG) btn("Insert test data (debug)") {
      lifecycleScope.launch { try { TestData.insert(HealthConnectClient.getOrCreate(this@MainActivity)); toast("Test data inserted") } catch (e: Exception) { toast("Insert failed: ${e.message}") } }
    }
    btn("Refresh status") { refresh() }
    col.addView(status)
    setContentView(ScrollView(this).apply { addView(col) })
    refresh()
  }

  private fun writePerms(): Set<String> = if (!BuildConfig.DEBUG) emptySet() else setOf(StepsRecord::class, HeartRateRecord::class,
    SleepSessionRecord::class, ExerciseSessionRecord::class, WeightRecord::class, NutritionRecord::class).map { HealthPermission.getWritePermission(it) }.toSet()

  private fun refresh() {
    lifecycleScope.launch {
      val sdk = HealthConnectClient.getSdkStatus(this@MainActivity)
      val granted = if (sdk == HealthConnectClient.SDK_AVAILABLE) HealthConnectClient.getOrCreate(this@MainActivity).permissionController.getGrantedPermissions() else emptySet()
      status.text = "Health Connect: ${if (sdk == HealthConnectClient.SDK_AVAILABLE) "available" else "unavailable ($sdk)"}\n" +
        "Read permissions: ${HealthHandler.READ_PERMS.count { it in granted }}/${HealthHandler.READ_PERMS.size}\n" +
        "Configured: ${Settings.workerUrl(this@MainActivity).isNotBlank() && Settings.deviceToken(this@MainActivity).isNotBlank()}\n" +
        "Bridge: ${BridgeService.state}"
    }
  }
  private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
