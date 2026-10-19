plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.torrenttunes.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.torrenttunes.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    // Playback — ExoPlayer reads the resolved stream URLs directly, so no
    // local server and no extra datasource adapter are involved.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)

    // InnerTube parsing is pure Kotlin and is exactly the logic worth pinning
    // without a phone: it is the part that breaks when YouTube changes schema.
    //
    // The real org.json is required — Android's copy is a stubbed jar in unit
    // tests and throws "not mocked" on every call.
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
