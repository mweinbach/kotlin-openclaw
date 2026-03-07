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

## Android Toolchain Bundle

The app consumes a dedicated Android arm64 toolchain bundle from GitHub Releases under the stable release tag `toolchain-node-android-arm64`.

Assets:
- `openclaw-node-v<node-version>-android-arm64.tar.xz`
- `openclaw-node-v<node-version>-android-arm64.tar.xz.sha256`
- `openclaw-node-android-arm64.json`

This bundle is built from `termux-packages`, but it is compiled against the OpenClaw Android package name `ai.openclaw.android`, not `com.termux`. That matters because the Termux `node` binary hardcodes the prefix path `/data/data/<package>/files/usr`, so the bundle must match the app package name to run correctly on-device.

The bundle currently includes:
- `node`
- `npm`
- `npx`
- `corepack`
- `rg` via the Termux `ripgrep` package

The repository no longer auto-builds or auto-publishes this bundle in GitHub Actions. Build and publish happen from your local machine with the scripts in `scripts/`.

To build the bundle locally:

1. Ensure your machine has `docker`, `git`, `python3`, and the archive tools required by `scripts/build_android_node_bundle.sh`.
2. Run `./scripts/publish_android_node_bundle.sh`.

Useful overrides:
- `TERMUX_REF=master`
- `RELEASE_TAG=toolchain-node-android-arm64`
- `COREPACK_VERSION=0.34.6`
- `GITHUB_REPOSITORY=mweinbach/kotlin-openclaw`

The local publisher script:
- clones `termux-packages`
- patches `TERMUX_APP__PACKAGE_NAME` to `ai.openclaw.android`
- builds the required runtime packages for `aarch64`
- repackages them into a rootfs-style bundle rooted at `files/usr`
- adds a pinned `corepack` payload
- uploads the versioned bundle assets plus the stable manifest to GitHub Releases with `gh release upload --clobber`

The packager lives at `scripts/build_android_node_bundle.sh`. The local publisher lives at `scripts/publish_android_node_bundle.sh`.
