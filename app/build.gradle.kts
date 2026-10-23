plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "org.bluetoothauto"
    compileSdk = 36
    buildToolsVersion = "35.0.1"
    defaultConfig {
        applicationId = "org.bluetoothauto"
        minSdk = 26
        targetSdk = 34
        // Preserve the BluetoothAuto package/signing identity for an in-place library upgrade.
        versionCode = 20
        versionName = "0.3.0-preview.1"
        testInstrumentationRunner = "org.bluetoothauto.RevisionSmokeInstrumentation"
        ndk { abiFilters += providers.gradleProperty("bluetoothAutoAbi").getOrElse("arm64-v8a") }
    }
    buildFeatures { buildConfig = true }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation(project(":projection"))
    implementation("org.videolan.android:libvlc-all:3.7.7")
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
