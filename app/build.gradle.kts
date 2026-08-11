plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.beatwave.android"
    compileSdk = 35
    // Pinned explicitly: only 28.2.13676358 is actually installed under
    // $ANDROID_HOME/ndk — the AGP-default (26.1.10909125) is an empty/stub
    // directory on this machine and fails NDK detection.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.beatwave.android"
        minSdk = 26 // Oboe + full-duplex AAudio path wants API 26+
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-phase0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        prefab = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Phase 3: ArrangementViewModel (viewModelScope) + the viewModel() Compose helper.
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Oboe, prebuilt via Prefab — avoids vendoring/building Oboe's C++
    // source ourselves. The native module (src/main/cpp) links against it
    // via find_package(oboe) in CMakeLists.txt.
    implementation("com.google.oboe:oboe:1.9.0")

    // Phase 1: JSON persistence for Project/Track/LoopBlock/Sample.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Phase 6 will add media3/MediaSession.

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    // Phase 5: GrantPermissionRule, for auto-granting RECORD_AUDIO in the
    // real-hardware full-duplex capture instrumented test (mandate 11b) --
    // without this the OS permission dialog would steal window focus/block
    // the test instead of running unattended.
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
