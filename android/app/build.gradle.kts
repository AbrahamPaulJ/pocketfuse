import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.github.takahirom.roborazzi")
}

// Release signing comes from a properties file OUTSIDE the repo, named by an environment variable
// (POCKETFUSE_SIGNING_PROPERTIES). Without it the release build is unsigned, never broken, so a
// fresh clone still builds.
val signingProps = Properties().apply {
    System.getenv("POCKETFUSE_SIGNING_PROPERTIES")?.let { path ->
        val file = File(path)
        if (file.isFile) file.inputStream().use { load(it) }
    }
}

android {
    namespace = "io.github.abrahampaulj.pocketfuse"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.abrahampaulj.pocketfuse"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"
        // libwebrtc and MediaPipe ship native code per ABI; every phone this targets is arm64.
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        // MediaPipe memory-maps its .task model straight from the APK.
        noCompress += "task"
    }

    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = File(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // A separate app id, so the signed release and the test build install side by side.
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Roborazzi renders screens through Robolectric, which needs the real resources.
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // HTTP and WebSockets against the FaceFusion API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Live stream: libwebrtc with VP8 / VP9 / AV1
    implementation("io.getstream:stream-webrtc-android:1.3.8")

    // Camera for pairing (QR) and the puppet
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Puppet: face landmarks + blendshapes on the phone
    implementation("com.google.mediapipe:tasks-vision:0.10.20")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Screens rendered to PNG on the JVM, no device: tools/build_android.ps1 recordRoborazziDebug
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.26.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.26.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // End-to-end tests that run ON the phone against a real FaceFusion server (tools/e2e_phone.ps1)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
