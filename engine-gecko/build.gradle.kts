plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.thebrowser.engine.gecko"
    compileSdk = 37
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    // GeckoView is Mozilla's embeddable Firefox engine (MPL-2.0). It comes from maven.mozilla.org (see settings.gradle.kts).
    api(libs.geckoview)
}
