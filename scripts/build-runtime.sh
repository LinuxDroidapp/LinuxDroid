#!/usr/bin/env bash
# =============================================================================
# LinuxDroid — Unified Runtime Master Orchestrator
# =============================================================================
# Builds all LinuxDroid native and Linux components in dependency order:
#   1. Toolchain and environment validation
#   2. PRoot and static loader (scripts/build-proot.sh)
#   3. Wayland stack & Weston (native/weston/build_wayland_stack.sh)
#   4. LDDM & LDDE Debian packages (scripts/build-packages.sh)
#   5. Comprehensive artifact & 16 KB alignment validation (scripts/validate-artifacts.sh)
#   6. Android asset and jniLibs staging verification
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "========================================================================"
echo " LinuxDroid: Unified Runtime Build & Packaging Pipeline"
echo " Start time: $(date -u +'%Y-%m-%dT%H:%M:%SZ')"
echo "========================================================================"

# --- Step 1: Toolchain and Environment Validation ---
echo ""
echo "------------------------------------------------------------------------"
echo " [Step 1/5] Validating Host & Cross Toolchain Environment..."
echo "------------------------------------------------------------------------"

# SDK & NDK
export ANDROID_HOME="${ANDROID_HOME:-/home/codespace/Android/Sdk}"
if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
    for candidate in \
        "/home/codespace/Android/Sdk/ndk/29.0.14206865" \
        "/home/codespace/Android/Sdk/ndk/30.0.16138531" \
        "${ANDROID_HOME}/ndk/29.0.14206865" \
        "${ANDROID_HOME}/ndk/30.0.16138531" \
        $(ls -d "${ANDROID_HOME}/ndk/"* 2>/dev/null | sort -V | tail -n1)
    do
        if [[ -n "$candidate" && -d "$candidate" ]]; then
            export ANDROID_NDK_ROOT="$candidate"
            break
        fi
    done
fi

[[ -n "${ANDROID_NDK_ROOT:-}" && -d "${ANDROID_NDK_ROOT}" ]] || {
    echo "ERROR: Android NDK not found. Please set ANDROID_NDK_ROOT." >&2
    exit 1
}

echo ">>> Android SDK:  ${ANDROID_HOME}"
echo ">>> Android NDK:  ${ANDROID_NDK_ROOT}"

# Host & cross tools
REQUIRED_TOOLS=(
    cmake
    meson
    ninja
    git
    python3
    bison
    flex
    pkg-config
    dpkg-deb
    aarch64-linux-gnu-gcc
    aarch64-linux-gnu-g++
)

for tool in "${REQUIRED_TOOLS[@]}"; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "ERROR: Required tool '$tool' not found in PATH!" >&2
        exit 1
    fi
    echo "  Found $tool: $(command -v "$tool")"
done

# --- Step 2: PRoot & Loader Build ---
echo ""
echo "------------------------------------------------------------------------"
echo " [Step 2/5] Building ARM64 PRoot & Freestanding Loader..."
echo "------------------------------------------------------------------------"
"${SCRIPT_DIR}/build-proot.sh"

# --- Step 3: Wayland Stack & Weston Build ---
echo ""
echo "------------------------------------------------------------------------"
echo " [Step 3/5] Building Wayland, Weston & Native Dependencies..."
echo "------------------------------------------------------------------------"
"${ROOT_DIR}/native/weston/build_wayland_stack.sh"

# --- Step 4: LDDM & LDDE Debian Packages Build ---
echo ""
echo "------------------------------------------------------------------------"
echo " [Step 4/5] Building LDDM & LDDE ARM64 Debian Packages..."
echo "------------------------------------------------------------------------"
"${SCRIPT_DIR}/build-packages.sh"

# --- Step 5: Comprehensive Artifact Validation ---
echo ""
echo "------------------------------------------------------------------------"
echo " [Step 5/5] Executing Comprehensive Artifact Validation..."
echo "------------------------------------------------------------------------"
"${SCRIPT_DIR}/validate-artifacts.sh"

echo ""
echo "========================================================================"
echo " LinuxDroid: Unified Runtime Build Complete!"
echo " All components built, staged, packaged, and verified successfully."
echo " End time: $(date -u +'%Y-%m-%dT%H:%M:%SZ')"
echo "========================================================================"

