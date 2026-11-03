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
        versionCode = 8
        versionName = "0.8.0"\n        buildConfigField("String", "SUPABASE_URL", "\\"https://agwaxaejnnszunccmftm.supabase.co\\"")\n        buildConfigField("String", "SUPABASE_ANON_KEY", "\\"${System.getenv("SUPABASE_ANON_KEY") ?: ""}\\"")\n        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\\"${System.getenv("GOOGLE_WEB_CLIENT_ID") ?: ""}\\"")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("JARVIS_KEYSTORE_FILE")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
            }
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures {\n        compose = true\n        buildConfig = true\n    }\n\n    defaultConfig {\n        buildConfigField("String", "SUPABASE_URL", "\\"https://agwaxaejnnszunccmftm.supabase.co\\"")\n        buildConfigField("String", "SUPABASE_ANON_KEY", "\\"${System.getenv("SUPABASE_ANON_KEY") ?: ""}\\"")\n        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\\"${System.getenv("GOOGLE_WEB_CLIENT_ID") ?: ""}\\"")\n    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")\n    implementation("androidx.credentials:credentials:1.5.0")\n    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")\n    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
