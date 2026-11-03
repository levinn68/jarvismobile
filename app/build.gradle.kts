plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.jarvis.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.jarvis.mobile.assistant"
        minSdk = 23
        targetSdk = 35
        versionCode = 10
        versionName = "0.9.1"
        buildConfigField("String", "SUPABASE_URL", "\"https://agwaxaejnnszunccmftm.supabase.co\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${System.getenv("SUPABASE_ANON_KEY")?.takeIf { it.isNotBlank() } ?: "sb_publishable_BtFlJ2XJc4SW4kNSmIZ8bA_WcTQgFq-"}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${System.getenv("GOOGLE_WEB_CLIENT_ID") ?: ""}\"")
    }
    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("JARVIS_KEYSTORE_FILE")
            if (!storeFilePath.isNullOrBlank()) storeFile = file(storeFilePath)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release"); isMinifyEnabled = false } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    constraints {
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.1.20")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.1.20")
    }
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    // QR encoding only (no camera scanner dependency).
    implementation("com.google.zxing:core:3.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
