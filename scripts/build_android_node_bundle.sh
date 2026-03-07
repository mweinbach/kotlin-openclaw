#!/usr/bin/env bash
set -euo pipefail

TERMUX_PACKAGES_DIR="${TERMUX_PACKAGES_DIR:-termux-packages}"
OUTPUT_DIR="${OUTPUT_DIR:-dist/android-node}"
ANDROID_PACKAGE_NAME="${ANDROID_PACKAGE_NAME:-ai.openclaw.android}"
TARGET_ARCH="${TARGET_ARCH:-aarch64}"
COREPACK_VERSION="${COREPACK_VERSION:-0.34.6}"
GITHUB_REPOSITORY="${GITHUB_REPOSITORY:-mweinbach/kotlin-openclaw}"
RELEASE_TAG="${RELEASE_TAG:-toolchain-node-android-arm64}"
MANIFEST_NAME="${MANIFEST_NAME:-openclaw-node-android-arm64.json}"
RUNTIME_PACKAGES="${RUNTIME_PACKAGES:-nodejs npm libc++ openssl c-ares libicu libsqlite zlib ca-certificates resolv-conf}"

PACKAGE_PREFIX="data/data/${ANDROID_PACKAGE_NAME}/files"
ROOTFS_PREFIX="/data/data/${ANDROID_PACKAGE_NAME}/files"
RELEASE_BASE_URL="https://github.com/${GITHUB_REPOSITORY}/releases/download/${RELEASE_TAG}"

require_command() {
    local command_name="$1"
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "Missing required command: $command_name" >&2
        exit 1
    fi
}

require_command bsdtar
require_command curl
require_command python3
require_command shasum
require_command tar

find_deb() {
    local package_name="$1"
    local matches=()
    local preferred=(
        "$TERMUX_PACKAGES_DIR"/output/"${package_name}"_*_"${TARGET_ARCH}".deb
        "$TERMUX_PACKAGES_DIR"/output/"${package_name}"_*_all.deb
    )

    for pattern in "${preferred[@]}"; do
        local candidate
        for candidate in $pattern; do
            if [[ -f "$candidate" ]]; then
                matches+=("$candidate")
            fi
        done
    done

    if [[ "${#matches[@]}" -eq 0 ]]; then
        echo "Could not find a built .deb for package '${package_name}' in ${TERMUX_PACKAGES_DIR}/output" >&2
        exit 1
    fi

    printf '%s\n' "${matches[@]}" | sort | tail -n 1
}

extract_package_payload() {
    local deb_path="$1"
    local destination="$2"
    local package_name
    package_name="$(basename "$deb_path")"
    local scratch
    scratch="$(mktemp -d)"
    mkdir -p "$destination"

    bsdtar -xf "$deb_path" -C "$scratch"
    local data_archive
    data_archive="$(find "$scratch" -maxdepth 1 -type f -name 'data.tar.*' | head -n 1)"
    if [[ -z "$data_archive" ]]; then
        echo "Package ${package_name} is missing data.tar.*" >&2
        exit 1
    fi

    local extracted_root="$scratch/payload"
    mkdir -p "$extracted_root"
    bsdtar -xf "$data_archive" -C "$extracted_root"

    local source_root="$extracted_root/$PACKAGE_PREFIX"
    if [[ ! -d "$source_root" ]]; then
        echo "Package ${package_name} does not contain ${PACKAGE_PREFIX}" >&2
        exit 1
    fi

    cp -a "$source_root"/. "$destination"/
    rm -rf "$scratch"
}

ensure_wrapper() {
    local target_path="$1"
    local contents="$2"
    mkdir -p "$(dirname "$target_path")"
    cat >"$target_path" <<EOF
$contents
EOF
    chmod 0755 "$target_path"
}

fetch_corepack() {
    local destination_root="$1"
    local registry_json
    registry_json="$(mktemp)"
    local tarball
    tarball="$(mktemp)"

    curl -fsSL "https://registry.npmjs.org/corepack/${COREPACK_VERSION}" -o "$registry_json"
    python3 - "$registry_json" "$tarball" <<'PY'
import base64
import hashlib
import json
import pathlib
import sys
import urllib.request

metadata_path = pathlib.Path(sys.argv[1])
tarball_path = pathlib.Path(sys.argv[2])
metadata = json.loads(metadata_path.read_text())
dist = metadata.get("dist") or {}
url = dist.get("tarball")
integrity = dist.get("integrity")
if not url or not integrity:
    raise SystemExit("corepack metadata is missing dist.tarball or dist.integrity")

with urllib.request.urlopen(url) as response:
    tarball_path.write_bytes(response.read())

algorithm, expected = integrity.split("-", 1)
if algorithm != "sha512":
    raise SystemExit(f"Unsupported integrity algorithm: {algorithm}")
actual = base64.b64encode(hashlib.sha512(tarball_path.read_bytes()).digest()).decode()
if actual != expected:
    raise SystemExit("corepack integrity verification failed")
PY

    local extract_dir
    extract_dir="$(mktemp -d)"
    bsdtar -xf "$tarball" -C "$extract_dir"
    mkdir -p "$destination_root/usr/lib/node_modules"
    rm -rf "$destination_root/usr/lib/node_modules/corepack"
    cp -a "$extract_dir/package" "$destination_root/usr/lib/node_modules/corepack"
    rm -rf "$extract_dir" "$registry_json" "$tarball"
}

main() {
    local output_dir
    output_dir="$(python3 -c 'import os,sys; print(os.path.abspath(sys.argv[1]))' "$OUTPUT_DIR")"
    mkdir -p "$output_dir"

    local scratch_dir
    scratch_dir="$(mktemp -d)"
    local rootfs_dir="$scratch_dir/rootfs"
    mkdir -p "$rootfs_dir"

    local package_name
    for package_name in $RUNTIME_PACKAGES; do
        extract_package_payload "$(find_deb "$package_name")" "$rootfs_dir"
    done

    fetch_corepack "$rootfs_dir"

    mkdir -p "$rootfs_dir/home" "$rootfs_dir/usr/tmp"
    rm -rf \
        "$rootfs_dir/usr/share/doc" \
        "$rootfs_dir/usr/share/info" \
        "$rootfs_dir/usr/share/man" \
        "$rootfs_dir/usr/var/cache"

    ensure_wrapper "$rootfs_dir/usr/bin/sh" '#!/system/bin/sh
exec /system/bin/sh "$@"'
    ensure_wrapper "$rootfs_dir/usr/bin/env" '#!/system/bin/sh
exec /system/bin/env "$@"'
    ensure_wrapper "$rootfs_dir/usr/bin/corepack" '#!/system/bin/sh
basedir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
exec "$basedir/node" "$basedir/../lib/node_modules/corepack/dist/corepack.js" "$@"'

    if [[ ! -e "$rootfs_dir/usr/bin/npm" ]]; then
        ensure_wrapper "$rootfs_dir/usr/bin/npm" '#!/system/bin/sh
basedir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
exec "$basedir/node" "$basedir/../lib/node_modules/npm/bin/npm-cli.js" "$@"'
    fi

    if [[ ! -e "$rootfs_dir/usr/bin/npx" ]]; then
        ensure_wrapper "$rootfs_dir/usr/bin/npx" '#!/system/bin/sh
basedir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
exec "$basedir/node" "$basedir/../lib/node_modules/npm/bin/npx-cli.js" "$@"'
    fi

    local node_deb
    node_deb="$(basename "$(find_deb nodejs)")"
    local node_package_version="${node_deb#nodejs_}"
    node_package_version="${node_package_version%_${TARGET_ARCH}.deb}"
    local node_version="${node_package_version%%-*}"
    local bundle_version="v${node_version}"
    local asset_name="${ASSET_NAME:-openclaw-node-${bundle_version}-android-arm64.tar.xz}"
    local sha256_name="${SHA256_NAME:-${asset_name}.sha256}"
    local download_url="${RELEASE_BASE_URL}/${asset_name}"
    local sha256_url="${RELEASE_BASE_URL}/${sha256_name}"

    local archive_path="$output_dir/$asset_name"
    tar -C "$rootfs_dir" -cJf "$archive_path" .

    local archive_sha
    archive_sha="$(shasum -a 256 "$archive_path" | awk '{print $1}')"
    printf '%s  %s\n' "$archive_sha" "$asset_name" >"$output_dir/$sha256_name"

    python3 - "$output_dir/$MANIFEST_NAME" <<PY
import json
import pathlib
manifest = {
    "schemaVersion": 1,
    "generatedAt": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
    "androidPackageName": "${ANDROID_PACKAGE_NAME}",
    "platform": {"os": "android", "arch": "arm64"},
    "rootfsPrefix": "${ROOTFS_PREFIX}",
    "nodeVersion": "${bundle_version}",
    "nodePackageVersion": "${node_package_version}",
    "corepackVersion": "${COREPACK_VERSION}",
    "releaseTag": "${RELEASE_TAG}",
    "assetName": "${asset_name}",
    "sha256AssetName": "${sha256_name}",
    "downloadUrl": "${download_url}",
    "sha256Url": "${sha256_url}",
    "sha256": "${archive_sha}",
    "packages": "${RUNTIME_PACKAGES}".split(),
}
pathlib.Path(__import__("sys").argv[1]).write_text(json.dumps(manifest, indent=2) + "\n")
PY

    rm -rf "$scratch_dir"

    echo "Created:"
    echo "  $archive_path"
    echo "  $output_dir/$sha256_name"
    echo "  $output_dir/$MANIFEST_NAME"
}

main "$@"
