plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.isight"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.isight"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0-phase30"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The .tflite model must be stored UNCOMPRESSED inside the APK, or
    // AssetFileDescriptor-based mmap loading (see YoloDetector) fails with
    // "This file can not be opened as a file descriptor; it is probably
    // compressed". This is the standard, documented fix.
    androidResources {
        noCompress += "tflite"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // ARCore SDK for Android (session, pose tracking, depth, planes).
    implementation("com.google.ar:core:1.56.0")

    // LiteRT (formerly TensorFlow Lite) interpreter for on-device YOLO
    // inference. Pinned to the 1.x line deliberately: LiteRT 2.x replaces
    // org.tensorflow.lite.Interpreter with a new CompiledModel API, which is
    // a bigger surface change we don't want mid-project. 1.4.1 still ships
    // the classic Interpreter API with no code changes from plain TFLite.
    implementation("com.google.ai.edge.litert:litert:1.4.1")

    // GPU delegate (Phase 29 performance) - opt-in, see YoloDetector's
    // useGpuDelegate parameter for why it defaults to off. Same 1.x version
    // as the core interpreter above: GPU delegate support for the
    // Interpreter API is exclusive to LiteRT's V1 package line.
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
