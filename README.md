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

Current Android app releases also bundle that archive directly inside the APK under `app/src/main/assets/toolchains/`, so managed Node install avoids network fetch in the normal path. The GitHub release asset remains the fallback/dev source and the publishing target for the local bundle scripts.

That bundled archive is not sufficient by itself on modern Android app targets. Android 10+ blocks `exec()` from writable app home directories for apps targeting API 29+, so this repo now treats the asset bundle as the source payload only:
- the Termux-style prefix tree is still extracted into `files/usr` for JS modules, certs, caches, and config
- Gradle materializes APK-backed native entrypoints and shared libraries into `jniLibs/arm64-v8a` during the build
- the managed runtime executes Node and ripgrep from `applicationInfo.nativeLibraryDir` instead of `files/usr/bin`
- shell shims map `npm`, `npx`, `corepack`, and `rg` back onto that APK-backed Node binary plus the extracted JS payload

Those packaged ELF binaries must also be extracted onto disk at install time. Keep `packaging.jniLibs.useLegacyPackaging = true` enabled in `app/build.gradle.kts`; AGP will derive the effective `extractNativeLibs` setting for the packaged app from that build configuration.

For the current baked-in Android bundle, the app hardcodes the exact GitHub release asset URL and SHA-256 for `openclaw-node-v25.3.0-android-arm64.tar.xz`, so normal installs do not need a separate checksum lookup or manual dashboard configuration.

Assets:
- `openclaw-node-v<node-version>-android-arm64.tar.xz`
- `openclaw-node-v<node-version>-android-arm64.tar.xz.sha256`
- `openclaw-node-android-arm64.json`

This bundle is built from `termux-packages`, and it is compiled against the OpenClaw Android package name `ai.openclaw.android`, not `com.termux`. That still matters because the Termux `node` binary hardcodes the prefix path `/data/data/<package>/files/usr`. Matching the package name is necessary so the extracted prefix tree lines up with Node's compiled-in defaults, while the actual ELF binaries now run from the APK native library directory.

The bundle currently includes:
- `node`
- `npm`
- `npx`
- `corepack`
- `rg` via the Termux `ripgrep` package

The Android bundle intentionally does not include `git`. The current Termux `git` recipe enables Tcl/Tk and Perl features, which drags in a large desktop-oriented dependency tree (`fontconfig`, X11 macros/protocols, Subversion, and more) that does not make sense for the on-device OpenClaw runtime.

The repository no longer auto-builds or auto-publishes this bundle in GitHub Actions. Build and publish happen from your local machine with the scripts in `scripts/`.

The Android app build also runs `scripts/prepare_android_node_native_libs.py`, which extracts the bundled archive into generated `jniLibs` entries for the packaged Node/ripgrep binaries and their shared-library dependencies.

Because those APK-native binaries are version-coupled to the app build, Android managed Node custom `downloadUrl` or `baseUrl` overrides are no longer a safe runtime-only escape hatch on `targetSdk >= 29`. Rebuild the app with matching generated `jniLibs` if you need a different Android Node bundle version.

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
