plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

val bundledAndroidNodeArchive = fileTree("src/main/assets/toolchains") {
    include("openclaw-node-v*-android-arm64.tar.xz")
}.files.singleOrNull()
    ?: error("Expected exactly one bundled Android Node archive under app/src/main/assets/toolchains")
val generatedAndroidNodeJniLibsDir = layout.buildDirectory.dir("generated/openclaw-node/jniLibs")
val generatedAndroidNodeJniLibsPath = generatedAndroidNodeJniLibsDir.get().asFile
val prepareBundledAndroidNodeNativeLibs = tasks.register<Exec>("prepareBundledAndroidNodeNativeLibs") {
    inputs.file(bundledAndroidNodeArchive)
    inputs.file(rootProject.file("scripts/prepare_android_node_native_libs.py"))
    outputs.dir(generatedAndroidNodeJniLibsDir)
    commandLine(
        "python3",
        rootProject.file("scripts/prepare_android_node_native_libs.py").absolutePath,
        "--archive",
        bundledAndroidNodeArchive.absolutePath,
        "--output-dir",
        generatedAndroidNodeJniLibsPath.absolutePath,
    )
}

android {
    namespace = "ai.openclaw.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.openclaw.android"
        minSdk = 28
        targetSdk = 36
        versionCode = 10
        versionName = "0.2.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("keystore/release.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val ksFile = rootProject.file("keystore/release.jks")
            if (ksFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
            )
        }
    }

    sourceSets {
        getByName("main").jniLibs.srcDir(generatedAndroidNodeJniLibsPath)
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareBundledAndroidNodeNativeLibs)
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
    implementation(project(":core:core-storage"))
    implementation(project(":core:core-plugins"))

    // Runtime modules
    implementation(project(":runtime:runtime-engine"))
    implementation(project(":runtime:runtime-providers"))
    implementation(project(":runtime:runtime-memory"))
    implementation(project(":runtime:runtime-gateway"))
    implementation(project(":runtime:runtime-devices"))
    implementation(project(":runtime:runtime-cron"))

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
    implementation(project(":channels:channel-whatsapp"))
    implementation(project(":channels:channel-line"))
    implementation(project(":channels:channel-msteams"))
    implementation(project(":channels:channel-mattermost"))
    implementation(project(":channels:channel-nostr"))
    implementation(project(":channels:channel-webchat"))

    // AndroidX + Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3.explicit)
    implementation(libs.compose.material3.adaptive.nav.suite)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation("org.tukaani:xz:1.9")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
