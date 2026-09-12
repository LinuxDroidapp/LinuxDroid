#!/bin/bash
# =============================================================================
# LinuxDroid — In-Guest Local Rootfs Setup Script
# =============================================================================
# Executes entirely inside the Linux environment via PRoot.
# Configures a minimal/base Linux userspace (e.g. Ubuntu Base ARM64) into a fully
# functional LinuxDroid CLI and graphical (LDDM/LDDE) environment.
# =============================================================================

set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${PATH:-}"

LOG_PREFIX=">>> [SETUP-ROOTFS]"
COMPLETION_MARKER="/etc/linuxdroid/rootfs-ready"
STATE_FILE="/etc/linuxdroid/setup-state"
CONFIG_FILE="/etc/linuxdroid/install.conf"
SECRET_FILE="/etc/linuxdroid/.install.secret"
PACKAGES_DIR="/root/linuxdroid/packages"

log_info()  { echo "${LOG_PREFIX} [INFO] $*"; }
log_step()  { echo "${LOG_PREFIX} [STAGE] $*"; }
log_pass()  { echo "${LOG_PREFIX} [PASS] $*"; }
log_warn()  { echo "${LOG_PREFIX} [WARN] $*" >&2; }
log_error() { echo "${LOG_PREFIX} [ERROR] $*" >&2; }
log_fatal() {
    echo "${LOG_PREFIX} [FATAL] $*" >&2
    mkdir -p /etc/linuxdroid
    echo "STATE=ROOTFS_SETUP_FAILED" > "${STATE_FILE}"
    echo "ERROR=$*" >> "${STATE_FILE}"
    echo "FAILED_AT=$(date -u +"%Y-%m-%dT%H:%M:%SZ")" >> "${STATE_FILE}"
    rm -f "${COMPLETION_MARKER}" 2>/dev/null || true
    exit 1
}

trap 'rc=$?; if [ $rc -ne 0 ]; then log_fatal "Script exited abnormally with code $rc"; fi' EXIT

echo "================================================================================"
echo " LinuxDroid: In-Guest Rootfs Setup"
echo " Timestamp: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
echo " Working directory: $(pwd)"
echo "================================================================================"

mkdir -p /etc/linuxdroid
echo "STATE=ROOTFS_SETUP_RUNNING" > "${STATE_FILE}"
echo "STARTED_AT=$(date -u +"%Y-%m-%dT%H:%M:%SZ")" >> "${STATE_FILE}"

# -----------------------------------------------------------------------------
# 1. Distribution Detection
# -----------------------------------------------------------------------------
log_step "Detecting Linux distribution from /etc/os-release..."
if [ ! -f /etc/os-release ]; then
    log_fatal "Unsupported rootfs: /etc/os-release is missing."
fi

# Source os-release
. /etc/os-release

DISTRO_ID="${ID:-unknown}"
DISTRO_NAME="${NAME:-Unknown Linux}"
DISTRO_VERSION="${VERSION_ID:-}"
DISTRO_CODENAME="${VERSION_CODENAME:-${UBUNTU_CODENAME:-}}"

log_info "Detected distribution: ${DISTRO_NAME} (ID=${DISTRO_ID}, Codename=${DISTRO_CODENAME}, Version=${DISTRO_VERSION})"

case "${DISTRO_ID}" in
    ubuntu|debian|kali)
        log_pass "Distribution '${DISTRO_ID}' is supported by LinuxDroid."
        ;;
    *)
        log_fatal "Unsupported rootfs: Distribution '${DISTRO_ID}' is not supported."
        ;;
esac

# -----------------------------------------------------------------------------
# 2. Architecture Validation
# -----------------------------------------------------------------------------
log_step "Validating architecture..."
HOST_ARCH="$(uname -m)"
log_info "Detected kernel architecture: ${HOST_ARCH}"

DPKG_ARCH=""
if command -v dpkg >/dev/null 2>&1; then
    DPKG_ARCH="$(dpkg --print-architecture 2>/dev/null || true)"
    log_info "Detected dpkg architecture: ${DPKG_ARCH}"
fi

if [[ "${HOST_ARCH}" != "aarch64" && "${HOST_ARCH}" != "arm64" ]]; then
    if [[ -n "${DPKG_ARCH}" && "${DPKG_ARCH}" != "arm64" ]]; then
        log_fatal "Unsupported architecture: ${HOST_ARCH} / ${DPKG_ARCH}. LinuxDroid requires aarch64/arm64."
    fi
fi
log_pass "Architecture validated: ARM64 (aarch64)."

# -----------------------------------------------------------------------------
# 3. Rootfs Structure Validation
# -----------------------------------------------------------------------------
log_step "Validating basic rootfs filesystem layout..."
for dir in /bin /etc /usr /var; do
    if [ ! -d "${dir}" ]; then
        log_fatal "Rootfs validation failed: missing essential directory '${dir}'."
    fi
done
log_pass "Basic rootfs structure verified."

# -----------------------------------------------------------------------------
# 4. DNS & Repository Configuration
# -----------------------------------------------------------------------------
log_step "Configuring networking DNS and APT safeguards..."
mkdir -p /etc
if [ ! -s /etc/resolv.conf ] || ! grep -q "nameserver" /etc/resolv.conf 2>/dev/null; then
    cat > /etc/resolv.conf << 'EOF'
nameserver 8.8.8.8
nameserver 1.1.1.1
EOF
    log_info "Configured fallback nameservers in /etc/resolv.conf"
fi

# Configure policy-rc.d to prevent service daemons from attempting init launches inside PRoot
cat > /usr/sbin/policy-rc.d << 'EOF'
#!/bin/sh
exit 101
EOF
chmod +x /usr/sbin/policy-rc.d

# Configure APT sources for Ubuntu if missing or minimal
if [ "${DISTRO_ID}" = "ubuntu" ]; then
    CODENAME="${DISTRO_CODENAME}"
    if [ -z "${CODENAME}" ]; then
        CODENAME="resolute" # Ubuntu 26.04 ARM64 default
    fi

    SOURCES_LIST="/etc/apt/sources.list"
    SOURCES_DIR="/etc/apt/sources.list.d"
    HAS_SOURCES=false

    if [ -s "${SOURCES_LIST}" ] && grep -qE "^deb " "${SOURCES_LIST}" 2>/dev/null; then
        HAS_SOURCES=true
    elif [ -d "${SOURCES_DIR}" ] && find "${SOURCES_DIR}" -type f -name "*.sources" 2>/dev/null | grep -q .; then
        HAS_SOURCES=true
    fi

    if [ "${HAS_SOURCES}" = false ]; then
        log_info "Populating default Ubuntu ports repositories for '${CODENAME}'..."
        cat > "${SOURCES_LIST}" << EOF
deb http://ports.ubuntu.com/ubuntu-ports/ ${CODENAME} main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports/ ${CODENAME}-updates main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports/ ${CODENAME}-security main restricted universe multiverse
EOF
    fi
fi

# Recover from interrupted dpkg states if any locks are leftover
rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend /var/cache/apt/archives/lock 2>/dev/null || true
dpkg --configure -a 2>/dev/null || true

# -----------------------------------------------------------------------------
# 5. Update Package Indexes
# -----------------------------------------------------------------------------
log_step "Updating APT package indexes..."
apt-get update || {
    log_warn "apt-get update encountered errors. Retrying with --fix-missing..."
    apt-get update --fix-missing || log_fatal "Failed to update package indexes."
}
log_pass "Package indexes updated."

# -----------------------------------------------------------------------------
# 6. Install Essential Linux Packages
# -----------------------------------------------------------------------------
log_step "Installing essential base system utilities..."
BASE_PACKAGES=(
    sudo
    curl
    wget
    ca-certificates
    passwd
    tzdata
    locales
    fonts-dejavu-core
)

apt-get install -y --no-install-recommends "${BASE_PACKAGES[@]}" || {
    log_warn "Initial base install failed; attempting dependency repair..."
    apt-get install -f -y
    apt-get install -y --no-install-recommends "${BASE_PACKAGES[@]}" || log_fatal "Failed to install base system utilities."
}
log_pass "Essential base utilities installed."

# -----------------------------------------------------------------------------
# 7. User and Environment Configuration
# -----------------------------------------------------------------------------
log_step "Configuring user account and sudo privileges..."

TARGET_USER="user"
if [ -f "${CONFIG_FILE}" ]; then
    CONF_U="$(grep -E '^(USERNAME|username)=' "${CONFIG_FILE}" | cut -d= -f2 | tr -d ' "\r\n' || true)"
    if [ -n "${CONF_U}" ]; then
        TARGET_USER="${CONF_U}"
    fi
fi

log_info "Configuring primary user account: '${TARGET_USER}'"

if ! id -u "${TARGET_USER}" >/dev/null 2>&1; then
    useradd -m -s /bin/bash -U "${TARGET_USER}" || useradd -m -s /bin/bash "${TARGET_USER}"
    log_pass "Created user '${TARGET_USER}'."
else
    log_info "User '${TARGET_USER}' already exists."
fi

# Configure sudo access without password prompt
mkdir -p /etc/sudoers.d
SUDOERS_FILE="/etc/sudoers.d/01linuxdroid-${TARGET_USER}"
echo "${TARGET_USER} ALL=(ALL:ALL) NOPASSWD:ALL" > "${SUDOERS_FILE}"
chmod 0440 "${SUDOERS_FILE}"
log_pass "Sudoers configured for '${TARGET_USER}'."

# Apply password if secret file exists
if [ -f "${SECRET_FILE}" ] && [ -s "${SECRET_FILE}" ]; then
    PASS="$(cat "${SECRET_FILE}")"
    echo "${TARGET_USER}:${PASS}" | chpasswd 2>/dev/null || true
    echo "root:${PASS}" | chpasswd 2>/dev/null || true
    rm -f "${SECRET_FILE}" 2>/dev/null || true
    log_pass "Applied user password."
fi

# -----------------------------------------------------------------------------
# 10. Wayland & LinuxDroid Integration Configuration
# -----------------------------------------------------------------------------
log_step "Configuring Wayland runtime and LinuxDroid configuration files..."

# Prepare standard runtime and desktop directories
USER_UID="$(id -u "${TARGET_USER}" 2>/dev/null || echo 1000)"
mkdir -p "/run/user/${USER_UID}" "/run/lddm" "/run/ldde" "/tmp"
chmod 0700 "/run/user/${USER_UID}" 2>/dev/null || true
chown -R "${TARGET_USER}:${TARGET_USER}" "/run/user/${USER_UID}" 2>/dev/null || true
chmod 1777 /tmp 2>/dev/null || true

# Write /etc/linuxdroid/lddm.conf
cat > /etc/linuxdroid/lddm.conf << EOF
[lddm]
weston_socket = wayland-0
session_user = ${TARGET_USER}
autostart = true
session_dir = /run/lddm
EOF
chmod 0644 /etc/linuxdroid/lddm.conf

# Write /etc/linuxdroid/desktop.conf
cat > /etc/linuxdroid/desktop.conf << 'EOF'
[desktop]
shell = default
theme = default
EOF
chmod 0644 /etc/linuxdroid/desktop.conf

# Ensure ldde-session symlink exists
if [ -x /usr/bin/ldde ] && [ ! -e /usr/bin/ldde-session ]; then
    ln -sf /usr/bin/ldde /usr/bin/ldde-session
fi

log_pass "Integration files (lddm.conf, desktop.conf) configured."

# -----------------------------------------------------------------------------
# 11. Post-Installation Cleanup
# -----------------------------------------------------------------------------
log_step "Performing system cleanup..."
rm -f /usr/sbin/policy-rc.d 2>/dev/null || true
apt-get clean 2>/dev/null || true
log_pass "Cleanup complete."

# -----------------------------------------------------------------------------
# 12. Validation & Completion Marker
# -----------------------------------------------------------------------------
log_step "Verifying complete installation..."

# Core CLI validation is mandatory
if [ ! -x /bin/sh ] && [ ! -x /usr/bin/sh ]; then
    log_fatal "Validation failed: shell (/bin/sh) missing."
fi
if [ ! -x /sbin/linuxdroid-init ] && [ ! -x /usr/sbin/linuxdroid-init ]; then
    log_fatal "Validation failed: /sbin/linuxdroid-init (or /usr/sbin/linuxdroid-init) missing."
fi

# Create completion markers
cat > "${COMPLETION_MARKER}" << EOF
STATUS=ROOTFS_READY
COMPLETED_AT=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
DISTRO=${DISTRO_ID}
CODENAME=${DISTRO_CODENAME}
ARCH=${HOST_ARCH}
USER=${TARGET_USER}
LDDM_INSTALLED=false
LDDE_INSTALLED=false
EOF
chmod 0644 "${COMPLETION_MARKER}"

# Also create ROOTFS_READY and POST_INSTALL_COMPLETE for existing component compatibility
cp -f "${COMPLETION_MARKER}" /etc/linuxdroid/ROOTFS_READY
cp -f "${COMPLETION_MARKER}" /etc/linuxdroid/POST_INSTALL_COMPLETE

echo "STATE=ROOTFS_READY" > "${STATE_FILE}"
echo "COMPLETED_AT=$(date -u +"%Y-%m-%dT%H:%M:%SZ")" >> "${STATE_FILE}"

log_pass "Installation validated! Completion marker created at ${COMPLETION_MARKER}."

echo "================================================================================"
echo " LinuxDroid: In-Guest Setup Succeeded!"
echo " CLI environment is ready."
echo "================================================================================"

trap - EXIT
exit 0

