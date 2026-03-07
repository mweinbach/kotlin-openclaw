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

## Android Node Bundle

The app now has a dedicated GitHub Actions workflow for publishing an Android arm64 Node toolchain bundle to GitHub Releases:

- Workflow: `.github/workflows/android-node-bundle.yml`
- Release tag: `toolchain-node-android-arm64`
- Assets:
  - `openclaw-node-v<node-version>-android-arm64.tar.xz`
  - `openclaw-node-v<node-version>-android-arm64.tar.xz.sha256`
  - `openclaw-node-android-arm64.json`

This bundle is built from `termux-packages`, but it is compiled against the OpenClaw Android package name `ai.openclaw.android`, not `com.termux`. That matters because the Termux `node` binary hardcodes the prefix path `/data/data/<package>/files/usr`, so the bundle must match the app package name to run correctly on-device.

The workflow supports both a manual dispatch and a pushed tag trigger (`android-node-bundle-v*`). It:

- clones `termux-packages`
- patches `TERMUX_APP__PACKAGE_NAME` to `ai.openclaw.android`
- builds the required runtime packages for `aarch64`
- repackages them into a rootfs-style bundle rooted at `files/usr`
- adds a pinned `corepack` payload
- publishes the versioned bundle assets plus a stable manifest with `gh release upload --clobber`

To publish or refresh the bundle:

1. Open the `Android Node Bundle` workflow in GitHub Actions.
2. Run it with the desired `termux_ref` and `release_tag` inputs.
3. Wait for the workflow to upload the refreshed release assets.

To publish without using the Actions UI:

1. Push the current code to `master`.
2. Push a tag named like `android-node-bundle-v2026.03.07.1`.
3. Wait for the `Android Node Bundle` workflow to build and refresh the `toolchain-node-android-arm64` release assets.

The bundle packager lives at `scripts/build_android_node_bundle.sh`. It emits a versioned tarball, a matching SHA-256 sidecar, and a stable manifest JSON so the app can treat GitHub Releases as a CDN endpoint for managed Node installs while still selecting an explicit Node version.
