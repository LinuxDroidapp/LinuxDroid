#!/usr/bin/env bash
# =============================================================================
# LinuxDroid — PRoot & Loader ARM64 Production Builder & Stager
# =============================================================================
# Builds standalone proot and freestanding static loader from vendor/proot/
# using the Android NDK r29 toolchain with 16 KB page alignment.
# Stages artifacts to build/linuxdroid/proot/, updates MANIFEST.txt, and
# syncs to app/src/main/assets/proot/arm64-v8a/ and app/src/main/jniLibs/arm64-v8a/.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

PROOT_SRC_DIR="${ROOT_DIR}/vendor/proot"
BUILD_DIR="${PROOT_SRC_DIR}/build-arm64"
STAGE_DIR="${ROOT_DIR}/build/linuxdroid/proot"
NATIVE_LIBS_STAGE_DIR="${ROOT_DIR}/build/linuxdroid/native-libs"
ASSETS_PROOT_DIR="${ROOT_DIR}/app/src/main/assets/proot/arm64-v8a"
JNILIBS_DIR="${ROOT_DIR}/app/src/main/jniLibs/arm64-v8a"

echo "========================================================================"
echo " LinuxDroid: Building ARM64 PRoot & Loader"
echo "========================================================================"

# 1. Locate Android NDK
NDK_ROOT="${ANDROID_NDK_ROOT:-${NDK_ROOT:-}}"
if [[ -z "$NDK_ROOT" || ! -d "$NDK_ROOT" ]]; then
    for candidate in \
        "/home/codespace/Android/Sdk/ndk/29.0.14206865" \
        "/home/codespace/Android/Sdk/ndk/30.0.16138531" \
        "${ANDROID_HOME:-/nonexistent}/ndk/29.0.14206865" \
        "$HOME/Android/Sdk/ndk/29.0.14206865" \
        $(ls -d "${ANDROID_HOME:-/nonexistent}/ndk/"* 2>/dev/null | sort -V | tail -n1) \
        $(ls -d "$HOME/Android/Sdk/ndk/"* 2>/dev/null | sort -V | tail -n1)
    do
        if [[ -n "$candidate" && -d "$candidate" ]]; then
            NDK_ROOT="$candidate"
            break
        fi
    done
fi

[[ -n "$NDK_ROOT" && -d "$NDK_ROOT" ]] || {
    echo "ERROR: Android NDK not found. Please set ANDROID_NDK_ROOT." >&2
    exit 1
}

CMAKE_TOOLCHAIN="${NDK_ROOT}/build/cmake/android.toolchain.cmake"
[[ -f "$CMAKE_TOOLCHAIN" ]] || {
    echo "ERROR: CMake toolchain file not found: $CMAKE_TOOLCHAIN" >&2
    exit 1
}

echo ">>> Using NDK: ${NDK_ROOT}"
echo ">>> PRoot Source: ${PROOT_SRC_DIR}"

mkdir -p "${STAGE_DIR}" "${NATIVE_LIBS_STAGE_DIR}" "${ASSETS_PROOT_DIR}" "${JNILIBS_DIR}"

# 2. Configure with CMake
echo ">>> Configuring PRoot with CMake..."
cmake -S "${PROOT_SRC_DIR}" -B "${BUILD_DIR}" \
    -DCMAKE_TOOLCHAIN_FILE="${CMAKE_TOOLCHAIN}" \
    -DANDROID_ABI="arm64-v8a" \
    -DANDROID_PLATFORM="android-35" \
    -DCMAKE_BUILD_TYPE=Release

# 3. Build Targets
echo ">>> Building PRoot targets..."
cmake --build "${BUILD_DIR}" --parallel "$(nproc)"

# 4. Verify outputs exist
PROOT_BIN="${BUILD_DIR}/proot-bin"
LOADER_BIN="${BUILD_DIR}/prootloader-bin"
LIBPROOT_SO="${BUILD_DIR}/libproot.so"
LIBPROOT_LOADER_SO="${BUILD_DIR}/libproot_loader.so"

for out in "$PROOT_BIN" "$LOADER_BIN" "$LIBPROOT_SO" "$LIBPROOT_LOADER_SO"; do
    [[ -f "$out" ]] || {
        echo "ERROR: Expected build output missing: $out" >&2
        exit 1
    }
done

# 5. Stage to build/linuxdroid/proot/
echo ">>> Staging artifacts to ${STAGE_DIR}..."
cp -f "${PROOT_BIN}" "${STAGE_DIR}/proot"
cp -f "${LOADER_BIN}" "${STAGE_DIR}/loader"
cp -f "${LIBPROOT_SO}" "${STAGE_DIR}/libproot.so"
cp -f "${LIBPROOT_LOADER_SO}" "${STAGE_DIR}/libproot_loader.so"

cp -f "${LIBPROOT_SO}" "${NATIVE_LIBS_STAGE_DIR}/libproot.so"
cp -f "${LIBPROOT_LOADER_SO}" "${NATIVE_LIBS_STAGE_DIR}/libproot_loader.so"

chmod 0755 "${STAGE_DIR}/proot" "${STAGE_DIR}/loader"

# 6. Generate MANIFEST.txt
sha256_hash() {
    sha256sum "$1" | awk '{print $1}'
}

PROOT_SHA="$(sha256_hash "${STAGE_DIR}/proot")"
LOADER_SHA="$(sha256_hash "${STAGE_DIR}/loader")"
LIBPROOT_SHA="$(sha256_hash "${STAGE_DIR}/libproot.so")"
LIBLOADER_SHA="$(sha256_hash "${STAGE_DIR}/libproot_loader.so")"

# Commit resolution
RESOLVED_COMMIT="release"
if [[ -d "${PROOT_SRC_DIR}/.git" ]] || git -C "${ROOT_DIR}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    RESOLVED_COMMIT="$(git -C "${ROOT_DIR}" log -n 1 --format="%H" -- "${PROOT_SRC_DIR}" || echo "release")"
fi

cat > "${STAGE_DIR}/MANIFEST.txt" << EOF
LinuxDroid-PRoot v5.1.107.92
commit:  ${RESOLVED_COMMIT}
ABI:     arm64-v8a
arch:    aarch64
sha256:
  proot:              ${PROOT_SHA}
  loader:             ${LOADER_SHA}
  libproot.so:        ${LIBPROOT_SHA}
  libproot_loader.so: ${LIBLOADER_SHA}
EOF

echo ">>> Staged Manifest:"
cat "${STAGE_DIR}/MANIFEST.txt"

# 7. Sync to app assets and jniLibs
echo ">>> Syncing to app packaging directories..."
cp -f "${STAGE_DIR}/proot" "${ASSETS_PROOT_DIR}/proot"
cp -f "${STAGE_DIR}/loader" "${ASSETS_PROOT_DIR}/loader"
cp -f "${STAGE_DIR}/MANIFEST.txt" "${ASSETS_PROOT_DIR}/MANIFEST.txt"
chmod 0755 "${ASSETS_PROOT_DIR}/proot" "${ASSETS_PROOT_DIR}/loader"

cp -f "${STAGE_DIR}/libproot.so" "${JNILIBS_DIR}/libproot.so"
cp -f "${STAGE_DIR}/libproot_loader.so" "${JNILIBS_DIR}/libproot_loader.so"

# 8. Verify 16 KB Page Alignment
echo ">>> Verifying 16 KB page alignment for PRoot artifacts..."
python3 -c '
import subprocess, sys

def check_file(path):
    out = subprocess.check_output(["llvm-readelf", "-l", path], text=True)
    aligns = [int(line.split()[-1], 16) for line in out.splitlines() if line.strip().startswith("LOAD")]
    if not aligns:
        print(f"ERROR: No LOAD segments found in {path}", file=sys.stderr)
        sys.exit(1)
    for a in aligns:
        if a < 0x4000:
            print(f"ERROR: {path} has alignment {hex(a)} < 0x4000", file=sys.stderr)
            sys.exit(1)
    print(f"  OK (16 KB aligned): {path} ({[hex(a) for a in aligns]})")

for f in sys.argv[1:]:
    check_file(f)
' "${STAGE_DIR}/proot" "${STAGE_DIR}/loader" "${STAGE_DIR}/libproot.so" "${STAGE_DIR}/libproot_loader.so"

echo "========================================================================"
echo " PRoot & Loader ARM64 Build Complete!"
echo "========================================================================"
