pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kotlin-openclaw"

// Core modules (pure Kotlin/JVM)
include(":core:core-model")
include(":core:core-config")
include(":core:core-session")
include(":core:core-routing")
include(":core:core-acp")
include(":core:core-skills")
include(":core:core-agent")
include(":core:core-security")
include(":core:core-plugins")
include(":core:core-storage")

// Runtime modules (Android libraries)
include(":runtime:runtime-engine")
include(":runtime:runtime-providers")
include(":runtime:runtime-memory")
include(":runtime:runtime-gateway")
include(":runtime:runtime-cron")
include(":runtime:runtime-discovery")
include(":runtime:runtime-devices")

// Channel modules (Android libraries)
include(":channels:channel-core")
include(":channels:channel-telegram")
include(":channels:channel-discord")
include(":channels:channel-slack")
include(":channels:channel-signal")
include(":channels:channel-matrix")
include(":channels:channel-googlechat")
include(":channels:channel-irc")
include(":channels:channel-sms")
include(":channels:channel-whatsapp")
include(":channels:channel-line")
include(":channels:channel-msteams")
include(":channels:channel-mattermost")
include(":channels:channel-nostr")
include(":channels:channel-webchat")

// Android application
include(":app")
