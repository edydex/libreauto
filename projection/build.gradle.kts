plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.openandroidauto"
    compileSdk = 36
    buildToolsVersion = "35.0.1"
    val mapFrameMode = providers.gradleProperty("openAutoMapFrameMode").getOrElse("adaptive30")
    require(mapFrameMode in setOf("fixed15", "adaptive30")) { "Use fixed15 or adaptive30 for openAutoMapFrameMode" }
    buildFeatures { buildConfig = true }
    defaultConfig {
        // The host player remains usable on Android 8+. Projection entry points gate API 32.
        minSdk = 26
        buildConfigField("String", "OPEN_AUTO_MAP_FRAME_MODE", "\"$mapFrameMode\"")
        buildConfigField("String", "VERSION_NAME", "\"LibreAuto-projection\"")
        consumerProguardFiles("consumer-rules.pro", "../third_party/brouter/proguard-rules.pro")
    }
    sourceSets.getByName("main").java.srcDir("../third_party/brouter/src/main/java")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
