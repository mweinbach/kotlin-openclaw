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

Current Android app releases also bundle that archive directly inside the APK under `app/src/main/assets/toolchains/`, so managed Node install works without any network fetch in the normal path. The GitHub release asset remains the fallback/dev source and the publishing target for the local bundle scripts.

For the current baked-in Android bundle, the app hardcodes the exact GitHub release asset URL and SHA-256 for `openclaw-node-v25.3.0-android-arm64.tar.xz`, so normal installs do not need a separate checksum lookup or manual dashboard configuration.

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

The Android bundle intentionally does not include `git`. The current Termux `git` recipe enables Tcl/Tk and Perl features, which drags in a large desktop-oriented dependency tree (`fontconfig`, X11 macros/protocols, Subversion, and more) that does not make sense for the on-device OpenClaw runtime.

The repository no longer auto-builds or auto-publishes this bundle in GitHub Actions. Build and publish happen from your local machine with the scripts in `scripts/`.

To build the bundle locally:

1. Ensure your machine has `docker`, `gh`, `git`, `python3`, and the archive tools required by `scripts/build_android_node_bundle.sh`.
2. Run `./scripts/publish_android_node_bundle.sh`.

Useful overrides:
- `TERMUX_REF=master`
- `RELEASE_TAG=toolchain-node-android-arm64`
- `COREPACK_VERSION=0.34.6`
- `GITHUB_REPOSITORY=mweinbach/kotlin-openclaw`
- `TERMUX_PKG_MAKE_PROCESSES=2`
- `SKIP_TERMUX_BUILD=1` to package and upload from an already-built `termux-packages` work tree

The local publisher script:
- clones `termux-packages`
- patches `TERMUX_APP__PACKAGE_NAME` to `ai.openclaw.android`
- starts the local Termux builder container and installs a working `ninja-build` into it
- builds the required runtime packages for `aarch64`
- repackages them into a rootfs-style bundle rooted at `files/usr`
- adds a pinned `corepack` payload
- uploads the versioned bundle assets plus the stable manifest to GitHub Releases with `gh release upload --clobber`

The publisher defaults `TERMUX_DOCKER_RUN_EXTRA_ARGS` to a privileged container run because the local Termux Docker flow uses `fuse-overlayfs`. Override that env var if you need a different Docker runtime setup.

The packager lives at `scripts/build_android_node_bundle.sh`. The local publisher lives at `scripts/publish_android_node_bundle.sh`.
