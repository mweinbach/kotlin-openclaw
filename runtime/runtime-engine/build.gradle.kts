plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ai.openclaw.runtime.engine"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    api(project(":core:core-model"))
    api(project(":core:core-acp"))
    api(project(":core:core-agent"))
    api(project(":core:core-skills"))
    api(project(":core:core-routing"))
    api(project(":core:core-config"))
    api(project(":core:core-session"))
    api(project(":core:core-security"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlin.test)
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
