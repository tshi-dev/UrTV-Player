plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.urtv.player"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.urtv.player"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("PLAYER_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("PLAYER_VERSION_NAME") ?: "0.1.0"
        // SHA-256 of OUR release signing certificate, passed in by CI (never stored in the repository). The app compares it
        // with the certificate it is actually installed with; empty on local and fork builds, which therefore count as unofficial.
        buildConfigField("String", "OFFICIAL_CERT_SHA256", "\"${System.getenv("PLAYER_CERT_SHA256") ?: ""}\"")
        // Only the two shipped languages (drops library string tables).
        resourceConfigurations += listOf("ar", "en")
    }
    // Release signing comes only from CI secrets: the workflow decodes the keystore to a temp file and sets these variables.
    // Nothing here (or anywhere in the repository) holds a key or a password. v1 is off (minSdk 26); v2 + v3 are on.
    signingConfigs {
        val ks = System.getenv("PLAYER_KEYSTORE_FILE")
        if (ks != null) create("release") {
            storeFile = file(ks)
            storePassword = System.getenv("PLAYER_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("PLAYER_KEY_ALIAS")
            keyPassword = System.getenv("PLAYER_KEY_PASSWORD")
            enableV1Signing = false; enableV2Signing = true; enableV3Signing = true
        }
    }
    buildTypes {
        // R8 + resource shrinking on for every build type, so the debug APK is the real size.
        getByName("debug") {
            // a different application id: a debug build can never overwrite or be mistaken for the official app
            applicationIdSuffix = ".debug"; versionNameSuffix = "-debug"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }   // unsigned when the CI secrets are absent
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "DebugProbesKt.bin", "kotlin-tooling-metadata.json", "/kotlin/**", "/META-INF/com/android/build/gradle/app-metadata.properties") }
    testOptions { unitTests { isReturnDefaultValues = true; isIncludeAndroidResources = true; all { it.maxHeapSize = "2g"; it.setForkEvery(1); it.maxParallelForks = 2 } } }     // every test class in its own JVM: static state (the phone server, Compose test clocks) leaked between classes and made the UI tests depend on the order Gradle ran them in
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")   // installs the baseline profile at first launch
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    val media3 = "1.5.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    // Screenshots: Compose UI rendered on the JVM (Robolectric native graphics), no emulator needed.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.6.1")
}

// Optional: point Robolectric at a pre-downloaded android-all jar directory (offline CI/sandboxes).
System.getenv("ROBOLECTRIC_DIR")?.let { dir ->
    tasks.withType<Test>().configureEach {
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", dir)
    }
}
