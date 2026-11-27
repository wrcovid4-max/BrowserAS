import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.thebrowser"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.thebrowser"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        // GeckoView ships native libraries for four CPU types (hundreds of MB in a debug APK, so installs crawl or stall).
        // Almost every phone is arm64. Add "x86_64" here if you use an emulator on an Intel/AMD PC.
        ndk { abiFilters += listOf("arm64-v8a") }
        // Optional proxy URL only. No API key is ever baked into the binary.
        buildConfigField("String", "GEMINI_PROXY_URL", "\"${localProps.getProperty("GEMINI_PROXY_URL", "")}\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine-chromium"))
    // Passkeys in WebView go through Credential Manager (WebView looks for it on the app classpath).
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    if (providers.gradleProperty("hydrogen.gecko").orNull != "false") implementation(project(":engine-gecko"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.pdfbox.android) // saves PDF edits (annotations, forms, page changes); viewing uses the platform PdfRenderer
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.icons.core)
}
