#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

BUILD_DIR="${ROOT_DIR}/build-release"
OUTPUT_DIR="${BUILD_DIR}/packages"
TARGET_ARCH=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --build-dir)
            BUILD_DIR="$2"
            shift 2
            ;;
        --output-dir)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --arch)
            TARGET_ARCH="$2"
            shift 2
            ;;
        -h|--help)
            echo "Usage: $0 [--build-dir <dir>] [--output-dir <dir>] [--arch <arm64|amd64>]"
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

if [ -z "${TARGET_ARCH}" ]; then
    if command -v dpkg-architecture >/dev/null 2>&1; then
        TARGET_ARCH="$(dpkg-architecture -qDEB_HOST_ARCH)"
    else
        UNAME_M="$(uname -m)"
        case "${UNAME_M}" in
            aarch64|arm64) TARGET_ARCH="arm64" ;;
            x86_64|amd64) TARGET_ARCH="amd64" ;;
            *) TARGET_ARCH="${UNAME_M}" ;;
        esac
    fi
fi

# Extract authoritative version from CMakeLists.txt
VERSION_MAJOR="$(grep -E 'VERSION [0-9]+\.[0-9]+\.[0-9]+' "${ROOT_DIR}/CMakeLists.txt" | head -n1 | sed -E 's/.*VERSION ([0-9]+)\.([0-9]+)\.([0-9]+).*/\1/')"
VERSION_MINOR="$(grep -E 'VERSION [0-9]+\.[0-9]+\.[0-9]+' "${ROOT_DIR}/CMakeLists.txt" | head -n1 | sed -E 's/.*VERSION ([0-9]+)\.([0-9]+)\.([0-9]+).*/\2/')"
VERSION_PATCH="$(grep -E 'VERSION [0-9]+\.[0-9]+\.[0-9]+' "${ROOT_DIR}/CMakeLists.txt" | head -n1 | sed -E 's/.*VERSION ([0-9]+)\.([0-9]+)\.([0-9]+).*/\3/')"
VERSION="${VERSION_MAJOR}.${VERSION_MINOR}.${VERSION_PATCH}"

echo "========================================================="
echo "Building Debian Package for linuxdroid-display-manager"
echo "Version:      ${VERSION}"
echo "Architecture: ${TARGET_ARCH}"
echo "Build Dir:    ${BUILD_DIR}"
echo "Output Dir:   ${OUTPUT_DIR}"
echo "========================================================="

mkdir -p "${OUTPUT_DIR}"
STAGING_DIR="${BUILD_DIR}/package-staging"
rm -rf "${STAGING_DIR}"
mkdir -p "${STAGING_DIR}"

# 1. Install via CMake into staging
DESTDIR="${STAGING_DIR}" cmake --install "${BUILD_DIR}"

# 2. Setup standard Debian paths
DOC_DIR="${STAGING_DIR}/usr/share/doc/linuxdroid-display-manager"
mkdir -p "${DOC_DIR}"

# Copy docs
cp "${ROOT_DIR}/README.md" "${DOC_DIR}/" || true
cp "${ROOT_DIR}/packaging/debian/copyright" "${DOC_DIR}/"

# Generate changelog.Debian.gz
DEB_DATE="$(LC_ALL=C date -R)"
sed -e "s/@PROJECT_VERSION@/${VERSION}/g" \
    -e "s/@DEB_DATE@/${DEB_DATE}/g" \
    "${ROOT_DIR}/packaging/debian/changelog.in" > "${DOC_DIR}/changelog.Debian"
gzip -9 -n -f "${DOC_DIR}/changelog.Debian"

# Ensure symlink /usr/bin/linuxdroid-display-manager -> lddm
mkdir -p "${STAGING_DIR}/usr/bin"
ln -sf lddm "${STAGING_DIR}/usr/bin/linuxdroid-display-manager"

# Ensure primary configuration in /etc/linuxdroid/lddm.conf
mkdir -p "${STAGING_DIR}/etc/linuxdroid"
if [ ! -f "${STAGING_DIR}/etc/linuxdroid/lddm.conf" ]; then
    cp "${ROOT_DIR}/config/lddm.conf.example" "${STAGING_DIR}/etc/linuxdroid/lddm.conf"
fi
cp "${ROOT_DIR}/config/lddm.conf.defaults" "${STAGING_DIR}/etc/linuxdroid/lddm.conf.defaults"

# Provide fallback symlink /etc/lddm/lddm.conf -> /etc/linuxdroid/lddm.conf
mkdir -p "${STAGING_DIR}/etc/lddm"
ln -sf /etc/linuxdroid/lddm.conf "${STAGING_DIR}/etc/lddm/lddm.conf"

# Remove developer-only CMake package files and static libraries from binary package if present
# (Debian packages separate liblddm-dev from runtime binary package)
rm -rf "${STAGING_DIR}/usr/local/include" "${STAGING_DIR}/usr/include" 2>/dev/null || true
rm -rf "${STAGING_DIR}/usr/local/lib/cmake" "${STAGING_DIR}/usr/lib/cmake" 2>/dev/null || true
rm -f "${STAGING_DIR}/usr/local/lib/liblddm_core.a" "${STAGING_DIR}/usr/lib/liblddm_core.a" 2>/dev/null || true

# If cmake installed to /usr/local, normalize to /usr
if [ -d "${STAGING_DIR}/usr/local" ]; then
    mkdir -p "${STAGING_DIR}/usr"
    if [ -d "${STAGING_DIR}/usr/local/bin" ]; then
        mkdir -p "${STAGING_DIR}/usr/bin"
        cp -a "${STAGING_DIR}/usr/local/bin/." "${STAGING_DIR}/usr/bin/"
    fi
    if [ -d "${STAGING_DIR}/usr/local/share" ]; then
        mkdir -p "${STAGING_DIR}/usr/share"
        cp -a "${STAGING_DIR}/usr/local/share/." "${STAGING_DIR}/usr/share/"
    fi
    if [ -d "${STAGING_DIR}/usr/local/etc" ]; then
        mkdir -p "${STAGING_DIR}/etc"
        cp -a "${STAGING_DIR}/usr/local/etc/." "${STAGING_DIR}/etc/"
    fi
    rm -rf "${STAGING_DIR}/usr/local"
fi

# 3. Create DEBIAN administrative directory
DEBIAN_DIR="${STAGING_DIR}/DEBIAN"
mkdir -p "${DEBIAN_DIR}"

sed -e "s/@PROJECT_VERSION@/${VERSION}/g" \
    -e "s/@DEB_ARCH@/${TARGET_ARCH}/g" \
    "${ROOT_DIR}/packaging/debian/control.in" > "${DEBIAN_DIR}/control"

grep -v '^[[:space:]]*$' "${ROOT_DIR}/packaging/debian/conffiles" > "${DEBIAN_DIR}/conffiles"
cp "${ROOT_DIR}/packaging/debian/postinst" "${DEBIAN_DIR}/"
cp "${ROOT_DIR}/packaging/debian/prerm" "${DEBIAN_DIR}/"
cp "${ROOT_DIR}/packaging/debian/postrm" "${DEBIAN_DIR}/"

# 4. Generate md5sums
(
    cd "${STAGING_DIR}"
    find . -type f ! -path "./DEBIAN/*" -printf '%P\0' | sort -z | xargs -r0 md5sum > "${DEBIAN_DIR}/md5sums"
)

# 5. Fix permissions according to Debian Policy
find "${STAGING_DIR}" -type d -exec chmod 0755 {} +
find "${STAGING_DIR}" -type f -exec chmod 0644 {} +
chmod 0755 "${STAGING_DIR}/usr/bin/lddm" || true
chmod 0755 "${DEBIAN_DIR}/postinst"
chmod 0755 "${DEBIAN_DIR}/prerm"
chmod 0755 "${DEBIAN_DIR}/postrm"

# 6. Build .deb package with dpkg-deb
DEB_FILE="${OUTPUT_DIR}/linuxdroid-display-manager_${VERSION}_${TARGET_ARCH}.deb"
dpkg-deb --build --root-owner-group "${STAGING_DIR}" "${DEB_FILE}"

echo "========================================================="
echo "Debian Package Built Successfully: ${DEB_FILE}"
echo "========================================================="

# 7. Quality check & inspection
dpkg-deb -I "${DEB_FILE}"
echo "--- Package Contents ---"
dpkg-deb -c "${DEB_FILE}"

exit 0

