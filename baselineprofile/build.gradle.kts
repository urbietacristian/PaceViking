plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.example.paceviking.baselineprofile"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // Profile collection needs API 28; the app itself still ships to 24,
        // and the profile generated here applies to all of them.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // Collect on whatever device or emulator is attached. The emulator has to
    // be a rooted (AOSP, no Google Play) image, or collection fails.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
