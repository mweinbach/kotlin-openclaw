plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "ai.openclaw.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.openclaw.android"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Core modules
    implementation(project(":core:core-model"))
    implementation(project(":core:core-config"))
    implementation(project(":core:core-session"))
    implementation(project(":core:core-routing"))
    implementation(project(":core:core-acp"))
    implementation(project(":core:core-skills"))
    implementation(project(":core:core-agent"))
    implementation(project(":core:core-security"))
    implementation(project(":core:core-plugins"))

    // Runtime modules
    implementation(project(":runtime:runtime-engine"))
    implementation(project(":runtime:runtime-providers"))
    implementation(project(":runtime:runtime-memory"))
    implementation(project(":runtime:runtime-gateway"))

    // Channel modules
    implementation(project(":channels:channel-core"))
    implementation(project(":channels:channel-telegram"))
    implementation(project(":channels:channel-discord"))
    implementation(project(":channels:channel-slack"))
    implementation(project(":channels:channel-signal"))
    implementation(project(":channels:channel-matrix"))
    implementation(project(":channels:channel-googlechat"))
    implementation(project(":channels:channel-irc"))
    implementation(project(":channels:channel-sms"))

    // AndroidX + Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso.core)
    debugImplementation(libs.compose.ui.tooling)
}
