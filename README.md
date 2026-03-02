# kotlin-openclaw

## Goal
Rebuild OpenClaw in Kotlin as a modular, Android-first codebase with pluggable core, runtime, and channel modules.

## Agent note
If an agent is working in this repository, clone the latest upstream OpenClaw into a temporary folder for reference:

```bash
mkdir -p tmp
git clone https://github.com/openclaw/openclaw tmp/openclaw-reference
```

## Android Studio Build Config

- Open the project root in Android Studio.
- Set `Gradle JDK` to Java 17.
- Use shared run configs from `.run/`:
  - `Build App Debug` -> runs `:app:assembleDebug`
  - `Install App Debug` -> runs `:app:installDebug`
  - `Test App Unit (Debug)` -> runs `:app:testDebugUnitTest`
- Configure Codex OAuth in-app: `Settings` -> `Codex OAuth` -> `Sign In With Codex`.
