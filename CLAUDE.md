# CLAUDE.md

## Project Overview

**kotlin-openclaw** is a Kotlin/Android rebuild of the OpenClaw AI agent framework. It's a modular, Android-first codebase with pluggable core, runtime, and channel modules. The TypeScript reference implementation lives at `/users/mweinbach/projects/openclaw/`.

## Architecture

Three module layers, all included from `settings.gradle.kts`:

- **core/** — Pure Kotlin/JVM libraries (no Android dependency)
  - `core-model` `core-config` `core-session` `core-routing` `core-acp` `core-skills` `core-agent` `core-security` `core-plugins` `core-storage`
- **runtime/** — Android libraries for agent execution
  - `runtime-engine` (AgentRunner, ContextGuard, SystemPromptBuilder)
  - `runtime-providers` (Anthropic, OpenAI, Gemini, Ollama LLM providers)
  - `runtime-memory` `runtime-gateway` `runtime-cron` `runtime-discovery` `runtime-devices`
- **channels/** — Messaging platform integrations (Telegram, Discord, Slack, Signal, Matrix, IRC, SMS, WhatsApp, Line, MS Teams, Mattermost, Nostr, Google Chat, Webchat)
- **app/** — Android application (Jetpack Compose UI)

## Key Files

- `runtime/runtime-engine/src/main/kotlin/ai/openclaw/runtime/engine/AgentRunner.kt` — Core agent loop (two-phase: stream LLM response, then execute tools)
- `runtime/runtime-engine/src/main/kotlin/ai/openclaw/runtime/engine/ContextGuard.kt` — Token budget management, pair-aware history compaction
- `runtime/runtime-engine/src/main/kotlin/ai/openclaw/runtime/engine/SystemPromptBuilder.kt` — System prompt assembly
- `runtime/runtime-providers/src/main/kotlin/ai/openclaw/runtime/providers/` — LLM provider implementations
- `app/src/main/kotlin/ai/openclaw/android/AgentEngine.kt` — Android-side agent orchestration
- `core/core-agent/src/main/kotlin/ai/openclaw/core/agent/LlmProvider.kt` — Core interfaces (LlmProvider, LlmRequest, LlmMessage, LlmStreamEvent)

## Build & Test

```bash
# Debug build
./gradlew :app:assembleDebug

# Release build (needs keystore env vars: KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)
./gradlew :app:assembleRelease

# Run unit tests
./gradlew :app:testDebugUnitTest

# Compile-check a specific module
./gradlew :runtime:runtime-providers:compileDebugKotlin
```

- Java 17 (Temurin) required
- Gradle JVM heap: `-Xmx4g` for release builds (R8 needs it)
- Version catalog: `gradle/libs.versions.toml`

## Tech Stack

- Kotlin 2.2.21, AGP 9.0.0-alpha05, compileSdk/targetSdk 36, minSdk 28
- Jetpack Compose (BOM 2026.02.00), Material 3 1.5.0-alpha15
- kotlinx.serialization for JSON, OkHttp 5 for HTTP, Ktor for webhook server
- Room for local storage, BouncyCastle for crypto
- Testing: JUnit 4, Turbine, Robolectric, Compose UI testing

## CI

GitHub Actions at `.github/workflows/build.yml` — runs tests then builds signed release APK on push/PR to master.

## Conventions

- LLM providers implement `LlmProvider` interface, stream via `Flow<LlmStreamEvent>`
- SSE parsing uses shared `SseReader.kt` (`BufferedReader.sseEvents()` extension)
- Agent tool loop is two-phase: collect all tool calls from LLM stream, then execute all tools
- Tool result messages must include both `toolCallId` and `name`
- Context compaction preserves assistant+tool message pairs atomically
- Package prefix: `ai.openclaw`
