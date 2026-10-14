import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose"); id("com.google.gms.google-services") }
val ksProps = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
android {
  namespace = "dev.dreamingcodes.hcmcp"
  compileSdk = 36
  defaultConfig { applicationId = "dev.dreamingcodes.hcmcp"; minSdk = 28; targetSdk = 35; versionCode = 5; versionName = "0.3.2" }
  signingConfigs {
    create("release") {
      if (ksProps.isNotEmpty()) {
        storeFile = rootProject.file(ksProps.getProperty("storeFile")); storePassword = ksProps.getProperty("storePassword")
        keyAlias = ksProps.getProperty("keyAlias"); keyPassword = ksProps.getProperty("keyPassword")
      }
    }
  }
  buildTypes {
    release { isMinifyEnabled = false; if (ksProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release") }
  }
  buildFeatures { buildConfig = true; compose = true }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
}
dependencies {
  implementation("androidx.health.connect:connect-client:1.1.0")
  implementation("androidx.security:security-crypto:1.1.0")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
  implementation("com.google.firebase:firebase-messaging")
  implementation("androidx.activity:activity-compose:1.9.3")
  implementation(platform("androidx.compose:compose-bom:2024.12.01"))
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
