#!/usr/bin/env bash
set -euo pipefail

TERMUX_REF="${TERMUX_REF:-master}"
RELEASE_TAG="${RELEASE_TAG:-toolchain-node-android-arm64}"
COREPACK_VERSION="${COREPACK_VERSION:-0.34.6}"
ANDROID_PACKAGE_NAME="${ANDROID_PACKAGE_NAME:-ai.openclaw.android}"
TARGET_ARCH="${TARGET_ARCH:-aarch64}"
WORK_DIR="${WORK_DIR:-tmp/android-node-bundle}"
TERMUX_PACKAGES_DIR="${TERMUX_PACKAGES_DIR:-$WORK_DIR/termux-packages}"
OUTPUT_DIR="${OUTPUT_DIR:-$WORK_DIR/dist/android-node}"
GITHUB_REPOSITORY="${GITHUB_REPOSITORY:-mweinbach/kotlin-openclaw}"
CONTAINER_NAME="${CONTAINER_NAME:-termux-package-builder}"
TERMUX_DOCKER_RUN_EXTRA_ARGS="${TERMUX_DOCKER_RUN_EXTRA_ARGS:---privileged --device /dev/fuse --cap-add CAP_SYS_ADMIN --security-opt seccomp=unconfined}"
TERMUX_PKG_MAKE_PROCESSES="${TERMUX_PKG_MAKE_PROCESSES:-2}"
SKIP_TERMUX_BUILD="${SKIP_TERMUX_BUILD:-0}"
RUNTIME_PACKAGES="${RUNTIME_PACKAGES:-nodejs npm ripgrep libc++ openssl c-ares libicu libsqlite zlib ca-certificates resolv-conf pcre2}"

require_command() {
    local command_name="$1"
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "Missing required command: $command_name" >&2
        exit 1
    fi
}

require_command gh
require_command python3

ensure_termux_builder_ninja() {
    docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true

    echo "Starting Termux builder container"
    (
        cd "$TERMUX_PACKAGES_DIR"
        CONTAINER_NAME="$CONTAINER_NAME" \
        TERMUX_DOCKER_RUN_EXTRA_ARGS="$TERMUX_DOCKER_RUN_EXTRA_ARGS" \
        ./scripts/run-docker.sh bash -lc "true"
    )

    if ! docker exec "$CONTAINER_NAME" sh -lc "command -v ninja >/dev/null 2>&1 && command -v clang >/dev/null 2>&1"; then
        echo "Installing builder toolchain helpers in Termux builder container"
        docker exec -u 0 "$CONTAINER_NAME" sh -lc "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y ninja-build clang"
    fi

    echo "Replacing cached Termux ninja with the system ninja"
    docker exec -u 0 "$CONTAINER_NAME" sh -lc "
        install -d -m 0755 /home/builder/.termux-build/_cache/ninja-1.13.2 &&
        install -m 0755 /usr/bin/ninja /home/builder/.termux-build/_cache/ninja-1.13.2/ninja &&
        chown -R builder:builder /home/builder/.termux-build
    "
}

mkdir -p "$WORK_DIR"

if [[ "$SKIP_TERMUX_BUILD" != "1" ]]; then
    require_command docker
    require_command git

    rm -rf "$TERMUX_PACKAGES_DIR"

    echo "Cloning termux-packages at $TERMUX_REF"
    git clone --depth 1 --branch "$TERMUX_REF" https://github.com/termux/termux-packages.git "$TERMUX_PACKAGES_DIR"

    python3 - <<'PY' "$TERMUX_PACKAGES_DIR" "$ANDROID_PACKAGE_NAME"
from pathlib import Path
import sys

termux_packages_dir = Path(sys.argv[1])
android_package_name = sys.argv[2]
path = termux_packages_dir / "scripts" / "properties.sh"
original = 'TERMUX_APP__PACKAGE_NAME="com.termux"'
replacement = f'TERMUX_APP__PACKAGE_NAME="{android_package_name}"'
text = path.read_text()
if original not in text:
    raise SystemExit("Could not find TERMUX_APP__PACKAGE_NAME assignment to patch")
path.write_text(text.replace(original, replacement, 1))
PY

    grep -n 'TERMUX_APP__PACKAGE_NAME=' "$TERMUX_PACKAGES_DIR/scripts/properties.sh"

    python3 - <<'PY' "$TERMUX_PACKAGES_DIR/packages/libicu/build.sh"
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
marker = "termux_step_host_build() {\n"
if marker not in text:
    text += """

termux_step_host_build() {
\tCC=clang CXX=clang++ "$TERMUX_PKG_SRCDIR/configure" ${TERMUX_PKG_EXTRA_HOSTBUILD_CONFIGURE_ARGS}
\tmake -j "$TERMUX_PKG_MAKE_PROCESSES"
}
"""
    path.write_text(text)
PY

    python3 - <<'PY' "$TERMUX_PACKAGES_DIR/packages/ncurses/build.sh"
from pathlib import Path
import sys

path = Path(sys.argv[1])
original = 'https://dist.schmorp.de/rxvt-unicode/Attic/rxvt-unicode-${TERMUX_PKG_VERSION[1]}.tar.bz2'
replacement = 'https://mirrors.omnios.org/rxvt-unicode/rxvt-unicode-${TERMUX_PKG_VERSION[1]}.tar.bz2'
text = path.read_text()
if original in text:
    path.write_text(text.replace(original, replacement, 1))
PY

    python3 - <<'PY' "$TERMUX_PACKAGES_DIR/packages/openssl/build.sh"
from pathlib import Path
import sys

path = Path(sys.argv[1])
original = 'make -j"$TERMUX_PKG_MAKE_PROCESSES" all'
replacement = 'make -j"$TERMUX_PKG_MAKE_PROCESSES" build_sw'
text = path.read_text()
if original in text:
    path.write_text(text.replace(original, replacement, 1))
PY

    ensure_termux_builder_ninja

    echo "Building Termux packages locally"
    (
        cd "$TERMUX_PACKAGES_DIR"
        CONTAINER_NAME="$CONTAINER_NAME" \
        TERMUX_DOCKER_RUN_EXTRA_ARGS="$TERMUX_DOCKER_RUN_EXTRA_ARGS" \
        ./scripts/run-docker.sh bash -lc "cd /home/builder/termux-packages && TERMUX_PKG_MAKE_PROCESSES=$TERMUX_PKG_MAKE_PROCESSES ./build-package.sh -I -C -a $TARGET_ARCH $RUNTIME_PACKAGES"
    )
else
    if [[ ! -d "$TERMUX_PACKAGES_DIR" ]]; then
        echo "TERMUX_PACKAGES_DIR does not exist: $TERMUX_PACKAGES_DIR" >&2
        exit 1
    fi
    echo "Skipping Termux package build and reusing $TERMUX_PACKAGES_DIR"
fi

echo "Packaging Android toolchain bundle"
TERMUX_PACKAGES_DIR="$TERMUX_PACKAGES_DIR" \
OUTPUT_DIR="$OUTPUT_DIR" \
ANDROID_PACKAGE_NAME="$ANDROID_PACKAGE_NAME" \
TARGET_ARCH="$TARGET_ARCH" \
COREPACK_VERSION="$COREPACK_VERSION" \
GITHUB_REPOSITORY="$GITHUB_REPOSITORY" \
RELEASE_TAG="$RELEASE_TAG" \
RUNTIME_PACKAGES="$RUNTIME_PACKAGES" \
./scripts/build_android_node_bundle.sh

ASSET_NAME="$(python3 - <<'PY' "$OUTPUT_DIR/openclaw-node-android-arm64.json"
import json
import pathlib
import sys

manifest = json.loads(pathlib.Path(sys.argv[1]).read_text())
print(manifest["assetName"])
PY
)"
SHA256_NAME="${ASSET_NAME}.sha256"
RELEASE_NOTES_PATH="$OUTPUT_DIR/release-notes.md"

python3 - <<'PY' "$OUTPUT_DIR/openclaw-node-android-arm64.json" "$RELEASE_NOTES_PATH"
import json
import pathlib
import sys

manifest = json.loads(pathlib.Path(sys.argv[1]).read_text())
packages = ", ".join(manifest["packages"])
notes = "\n".join(
    [
        "Managed Android toolchain bundle for kotlin-openclaw.",
        "",
        f"- Generated at: {manifest['generatedAt']}",
        f"- Android package name: {manifest['androidPackageName']}",
        f"- Node version: {manifest['nodeVersion']} ({manifest['nodePackageVersion']})",
        f"- Corepack version: {manifest['corepackVersion']}",
        f"- Rootfs prefix: {manifest['rootfsPrefix']}",
        f"- Runtime packages: {packages}",
    ]
)
pathlib.Path(sys.argv[2]).write_text(notes + "\n")
PY

echo "Publishing release assets to GitHub"
if gh release view "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
    gh release edit "$RELEASE_TAG" \
        --repo "$GITHUB_REPOSITORY" \
        --title "OpenClaw Android Toolchain Bundle (arm64)" \
        --notes-file "$RELEASE_NOTES_PATH"
else
    gh release create "$RELEASE_TAG" \
        --repo "$GITHUB_REPOSITORY" \
        --title "OpenClaw Android Toolchain Bundle (arm64)" \
        --notes-file "$RELEASE_NOTES_PATH"
fi

gh release upload "$RELEASE_TAG" \
    --repo "$GITHUB_REPOSITORY" \
    "$OUTPUT_DIR/$ASSET_NAME" \
    "$OUTPUT_DIR/$SHA256_NAME" \
    "$OUTPUT_DIR/openclaw-node-android-arm64.json" \
    --clobber

echo "Published:"
echo "  $OUTPUT_DIR/$ASSET_NAME"
echo "  $OUTPUT_DIR/$SHA256_NAME"
echo "  $OUTPUT_DIR/openclaw-node-android-arm64.json"
