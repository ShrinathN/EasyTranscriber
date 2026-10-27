import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.easytranscriber"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.easytranscriber"
        // BluetoothHidDevice was added in API 28 (Android 9).
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"

        ndk {
            // sherpa-onnx ships native libraries for several ABIs; phones need only these.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Release signing key: keystore.properties + keystore/ (not in version control; back them up).
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use(::load) }
    }
    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // sherpa-onnx and onnxruntime-android both ship ONNX Runtime 1.28.x; one copy serves both.
        jniLibs.pickFirsts += "**/libonnxruntime.so"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Extracts downloaded .tar.bz2 model archives.
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Prebuilt sherpa-onnx (Apache-2.0). Fetch with scripts/fetch-deps.sh.
    implementation(files("libs/sherpa-onnx-2.0.0.aar"))
    // ONNX Runtime Java API (MIT) for ASR4ALL, which sherpa-onnx doesn't support. Same minor
    // version as the runtime inside sherpa-onnx, so the shared libonnxruntime.so fits both.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Desktop ONNX Runtime and org.json for running the ASR4ALL engine in JVM tests.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.28.0")
    testImplementation("org.json:json:20240303")
}
