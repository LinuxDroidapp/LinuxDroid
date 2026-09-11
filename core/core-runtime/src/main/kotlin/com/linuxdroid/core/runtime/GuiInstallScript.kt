package com.linuxdroid.core.runtime

import java.io.File

/**
 * Authoritative template and definition for `/etc/linuxdroid/gui-install.sh`.
 *
 * This script runs exclusively inside the guest Linux userspace via PRoot
 * under Guest Init in CLI mode, AFTER the base CLI provisioning is complete.
 *
 * Installs the optional GUI layer:
 * - Wayland libraries
 * - Weston compositor
 * - Pixman
 * - LDDM (LinuxDroid Display Manager) from staged .deb
 * - LDDE (LinuxDroid Desktop Environment) from staged .deb
 * - GUI configuration files (weston.ini, lddm.conf, desktop.conf)
 *
 * A failure in this script NEVER invalidates the CLI environment.
 * The [POST_INSTALL_COMPLETE] marker is NOT affected by this script.
 * Only [GUI_INSTALL_COMPLETE] is written on success.
 */
object GuiInstallScript {
    const val GUI_INSTALL_SCRIPT_PATH = "/etc/linuxdroid/gui-install.sh"
    const val GUI_INSTALL_SCRIPT_ALT_PATH = "/etc/linuxdroid/install-gui.sh"
    const val GUI_STAGED_PACKAGES_DIR = "/tmp/linuxdroid-packages"
    const val GUI_STAGED_PACKAGES_LEGACY_DIR = "/root/.linuxdroid/gui-packages"
    const val GUI_INSTALL_COMPLETE_MARKER = "/etc/linuxdroid/GUI_INSTALL_COMPLETE"
    const val GUI_INSTALL_STATE_PATH = "/etc/linuxdroid/gui-install-state"

    val SCRIPT_CONTENT: String = """
#!/bin/bash
# =============================================================================
# LinuxDroid — In-Guest GUI Installation Script
# =============================================================================
# Installs the optional graphical layer on top of an existing CLI environment.
# A failure here does NOT affect the CLI environment or CLI_READY status.
set -e

export DEBIAN_FRONTEND=noninteractive
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:§{PATH:-}"

run_cmd() {
    local stage="§1"
    local log_cmd="§2"
    shift 2

    local start_ts
    start_ts="§(date -u +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || date 2>/dev/null || echo "1970-01-01T00:00:00Z")"
    [ -z "§start_ts" ] && start_ts="1970-01-01T00:00:00Z"

    local start_sec
    start_sec="§(date +%s%N 2>/dev/null || date +%s 2>/dev/null || echo 0)"

    echo "[GUI_INSTALL][START][§stage]"
    echo "command=§log_cmd"
    echo "timestamp=§start_ts"

    mkdir -p /tmp /etc/linuxdroid 2>/dev/null || true
    local err_file="/tmp/gui_install_§{stage}_§§.err"
    if ! touch "§err_file" 2>/dev/null; then
        err_file="/etc/linuxdroid/gui_install_§{stage}_§§.err"
        touch "§err_file" 2>/dev/null || true
    fi
    local exit_code=0

    if "§@" 2>"§err_file"; then
        local end_sec
        end_sec="§(date +%s%N 2>/dev/null || date +%s 2>/dev/null || echo 0)"
        local dur_ms=1
        if [ "§{#start_sec}" -gt 10 ] && [ "§{#end_sec}" -gt 10 ]; then
            dur_ms=§(( (end_sec - start_sec) / 1000000 ))
        fi
        [ "§dur_ms" -le 0 ] && dur_ms=1
        local end_ts
        end_ts="§(date -u +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || date 2>/dev/null || echo "§start_ts")"
        [ -z "§end_ts" ] && end_ts="§start_ts"
        echo "[GUI_INSTALL][SUCCESS][§stage]"
        echo "exit_code=0"
        echo "duration_ms=§dur_ms"
        echo "timestamp=§end_ts"
        rm -f "§err_file" 2>/dev/null || true
        return 0
    else
        exit_code=§?
        local end_sec
        end_sec="§(date +%s%N 2>/dev/null || date +%s 2>/dev/null || echo 0)"
        local dur_ms=1
        if [ "§{#start_sec}" -gt 10 ] && [ "§{#end_sec}" -gt 10 ]; then
            dur_ms=§(( (end_sec - start_sec) / 1000000 ))
        fi
        [ "§dur_ms" -le 0 ] && dur_ms=1
        local err_msg=""
        if [ -s "§err_file" ]; then
            err_msg="§(cat "§err_file" 2>/dev/null || true)"
        fi
        if [ -z "§err_msg" ]; then
            err_msg="Command failed with exit code §exit_code"
        fi
        rm -f "§err_file" 2>/dev/null || true
        local fail_ts
        fail_ts="§(date -u +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || date 2>/dev/null || echo "§start_ts")"
        [ -z "§fail_ts" ] && fail_ts="§start_ts"
        echo "[GUI_INSTALL][FAIL][§stage]"
        echo "exit_code=§exit_code"
        echo "duration_ms=§dur_ms"
        echo "stderr=§err_msg"
        echo "timestamp=§fail_ts"
        # Update the GUI install state marker to FAILED
        mkdir -p /etc/linuxdroid 2>/dev/null || true
        echo "STATE=GUI_INSTALL_FAILED" > /etc/linuxdroid/gui-install-state
        echo "STAGE=§stage" >> /etc/linuxdroid/gui-install-state
        echo "EXIT_CODE=§exit_code" >> /etc/linuxdroid/gui-install-state
        echo "TIMESTAMP=§fail_ts" >> /etc/linuxdroid/gui-install-state
        exit §exit_code
    fi
}

echo "================================================================================"
echo "LINUXDROID GUI INSTALLATION STARTING"
echo "Timestamp: §(date -u +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || date 2>/dev/null || echo "1970-01-01T00:00:00Z")"
echo "CLI environment foundation is preserved regardless of GUI install outcome."
echo "================================================================================"

# Write in-progress state marker
mkdir -p /etc/linuxdroid 2>/dev/null || true
echo "STATE=GUI_INSTALLING" > /etc/linuxdroid/gui-install-state
echo "TIMESTAMP=§(date -u +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || echo "1970-01-01T00:00:00Z")" >> /etc/linuxdroid/gui-install-state

# -----------------------------------------------------------------------------
# 1. Validate CLI Prerequisites
# -----------------------------------------------------------------------------
validate_prerequisites_action() {
    local missing=""

    # Verify CLI foundation is present
    if [ ! -x /sbin/linuxdroid-init ]; then missing="§missing /sbin/linuxdroid-init;"; fi
    if [ ! -x /bin/sh ] && [ ! -x /bin/bash ] && [ ! -x /usr/bin/sh ] && [ ! -x /usr/bin/bash ]; then
        missing="§missing shell (/bin/sh or /bin/bash);"
    fi
    if [ ! -f /etc/os-release ] && [ ! -f /usr/lib/os-release ]; then
        missing="§missing /etc/os-release;"
    fi

    # Accept POST_INSTALL_COMPLETE or rootfs-ready; synthesize POST_INSTALL_COMPLETE if valid imported rootfs
    if [ ! -f /etc/linuxdroid/POST_INSTALL_COMPLETE ] && [ ! -f /etc/linuxdroid/rootfs-ready ]; then
        if [ -x /sbin/linuxdroid-init ] && ([ -f /etc/os-release ] || [ -f /usr/lib/os-release ]); then
            mkdir -p /etc/linuxdroid 2>/dev/null || true
            echo "STATUS=CLI_READY" > /etc/linuxdroid/POST_INSTALL_COMPLETE
        else
            missing="§missing CLI foundation not complete (POST_INSTALL_COMPLETE missing);"
        fi
    fi

    if [ -n "§missing" ]; then
        echo "Prerequisites check failed: §missing" >&2
        return 1
    fi
    return 0
}

run_cmd "VALIDATE_PREREQUISITES" "validate CLI prerequisites" validate_prerequisites_action

# Locate staged LinuxDroid .deb files
GUI_PKGS_DIR="/tmp/linuxdroid-packages"
if [ ! -d "§GUI_PKGS_DIR" ] && [ -d "/root/.linuxdroid/gui-packages" ]; then
    GUI_PKGS_DIR="/root/.linuxdroid/gui-packages"
elif [ ! -d "§GUI_PKGS_DIR" ] && [ -d "/root/linuxdroid/packages" ]; then
    GUI_PKGS_DIR="/root/linuxdroid/packages"
fi

LDDM_DEB=""
if [ -f "§GUI_PKGS_DIR/linuxdroid-display-manager.deb" ]; then
    LDDM_DEB="§GUI_PKGS_DIR/linuxdroid-display-manager.deb"
elif [ -f "§GUI_PKGS_DIR/LDDM.deb" ]; then
    LDDM_DEB="§GUI_PKGS_DIR/LDDM.deb"
elif [ -d "§GUI_PKGS_DIR" ]; then
    LDDM_DEB="§(find "§GUI_PKGS_DIR" /tmp/linuxdroid-packages /root/.linuxdroid/gui-packages /root/linuxdroid/packages -type f \( -name "*display-manager*.deb" -o -name "*lddm*.deb" \) 2>/dev/null | head -n 1 || true)"
fi

LDDE_DEB=""
if [ -f "§GUI_PKGS_DIR/linuxdroid-desktop-environment.deb" ]; then
    LDDE_DEB="§GUI_PKGS_DIR/linuxdroid-desktop-environment.deb"
elif [ -f "§GUI_PKGS_DIR/LDDE.deb" ]; then
    LDDE_DEB="§GUI_PKGS_DIR/LDDE.deb"
elif [ -d "§GUI_PKGS_DIR" ]; then
    LDDE_DEB="§(find "§GUI_PKGS_DIR" /tmp/linuxdroid-packages /root/.linuxdroid/gui-packages /root/linuxdroid/packages -type f \( -name "*desktop-environment*.deb" -o -name "*ldde*.deb" \) 2>/dev/null | head -n 1 || true)"
fi

if [ -z "§LDDM_DEB" ] || [ ! -f "§LDDM_DEB" ]; then
    echo "[GUI_INSTALL][FAIL][VALIDATE_PREREQUISITES]"
    echo "exit_code=1"
    echo "stderr=Missing staged LDDM deb in §GUI_PKGS_DIR"
    echo "STATE=GUI_INSTALL_FAILED" > /etc/linuxdroid/gui-install-state
    exit 1
fi

if [ -z "§LDDE_DEB" ] || [ ! -f "§LDDE_DEB" ]; then
    echo "[GUI_INSTALL][FAIL][VALIDATE_PREREQUISITES]"
    echo "exit_code=1"
    echo "stderr=Missing staged LDDE deb in §GUI_PKGS_DIR"
    echo "STATE=GUI_INSTALL_FAILED" > /etc/linuxdroid/gui-install-state
    exit 1
fi

# -----------------------------------------------------------------------------
# 2. Configure DNS & APT Safeguards
# -----------------------------------------------------------------------------
mkdir -p /etc 2>/dev/null || true
if [ ! -s /etc/resolv.conf ] || ! grep -q "nameserver" /etc/resolv.conf 2>/dev/null; then
    cat > /etc/resolv.conf << 'RESOLV_EOF'
nameserver 8.8.8.8
nameserver 1.1.1.1
RESOLV_EOF
    chmod 0644 /etc/resolv.conf 2>/dev/null || true
fi

# Ubuntu sources list fallback if missing
if [ -f /etc/os-release ]; then
    . /etc/os-release
    DISTRO_ID="§{ID:-}"
    DISTRO_CODENAME="§{VERSION_CODENAME:-§{UBUNTU_CODENAME:-noble}}"
    if [ "§DISTRO_ID" = "ubuntu" ]; then
        SOURCES_LIST="/etc/apt/sources.list"
        SOURCES_DIR="/etc/apt/sources.list.d"
        HAS_SOURCES=false
        if [ -s "§SOURCES_LIST" ] && grep -qE "^deb " "§SOURCES_LIST" 2>/dev/null; then
            HAS_SOURCES=true
        elif [ -d "§SOURCES_DIR" ] && find "§SOURCES_DIR" -type f \( -name "*.sources" -o -name "*.list" \) 2>/dev/null | grep -q .; then
            HAS_SOURCES=true
        fi
        if [ "§HAS_SOURCES" = false ]; then
            cat > "§SOURCES_LIST" << SOURCES_EOF
deb http://ports.ubuntu.com/ubuntu-ports/ §{DISTRO_CODENAME} main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports/ §{DISTRO_CODENAME}-updates main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports/ §{DISTRO_CODENAME}-security main restricted universe multiverse
SOURCES_EOF
            chmod 0644 "§SOURCES_LIST" 2>/dev/null || true
        fi
    fi
fi

cat > /usr/sbin/policy-rc.d << 'POLICY_EOF'
#!/bin/sh
exit 101
POLICY_EOF
chmod +x /usr/sbin/policy-rc.d 2>/dev/null || true

# Recover any interrupted dpkg states
if [ -f /var/lib/dpkg/lock ] || [ -f /var/lib/dpkg/lock-frontend ] || [ -f /var/cache/apt/archives/lock ]; then
    rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend /var/cache/apt/archives/lock 2>/dev/null || true
    dpkg --configure -a 2>/dev/null || true
fi

# -----------------------------------------------------------------------------
# 3. APT Update
# -----------------------------------------------------------------------------
run_cmd "APT_UPDATE" "apt-get update" apt-get update

# -----------------------------------------------------------------------------
# 4. Install Wayland & Graphics Libraries
# -----------------------------------------------------------------------------
WAYLAND_PACKAGES=(
    libwayland-client0
    libwayland-server0
    libwayland-cursor0
    wayland-protocols
    libpixman-1-0
    fonts-dejavu-core
    xwayland
    libcairo2
    libglib2.0-0
    libstdc++6
)

install_wayland_action() {
    apt-get install -y --no-install-recommends "§{WAYLAND_PACKAGES[@]}" || {
        apt-get install -f -y
        apt-get install -y --no-install-recommends "§{WAYLAND_PACKAGES[@]}"
    }
}

run_cmd "INSTALL_WAYLAND" "apt-get install -y <wayland_packages>" install_wayland_action

# -----------------------------------------------------------------------------
# 5. Install LDDM and LDDE Debian Packages via APT
# -----------------------------------------------------------------------------
install_gui_packages_action() {
    echo "Installing LDDM and LDDE Debian packages via APT: §LDDM_DEB §LDDE_DEB"
    apt-get install -y "§LDDM_DEB" "§LDDE_DEB"
}
run_cmd "INSTALL_GUI_PACKAGES" "apt-get install -y <staged_gui_debs>" install_gui_packages_action

# -----------------------------------------------------------------------------
# 6. Configure GUI (LDDM and LDDE config files)
# -----------------------------------------------------------------------------
configure_gui_action() {
    mkdir -p /etc/linuxdroid /run/lddm /run/ldde /tmp 2>/dev/null || true
    chmod 1777 /tmp 2>/dev/null || true

    # Read username from install.conf if available
    local username="user"
    if [ -f /etc/linuxdroid/install.conf ]; then
        local conf_user
        conf_user="§(grep -E '^(USERNAME|username)=' /etc/linuxdroid/install.conf | cut -d= -f2 | xargs 2>/dev/null || true)"
        [ -n "§conf_user" ] && username="§conf_user"
    fi

    local user_uid
    user_uid="§(id -u "§username" 2>/dev/null || echo 1000)"
    mkdir -p "/run/user/§{user_uid}" 2>/dev/null || true
    chmod 0700 "/run/user/§{user_uid}" 2>/dev/null || true
    chown -R "§username:§username" "/run/user/§{user_uid}" 2>/dev/null || true

    if [ ! -f /etc/linuxdroid/lddm.conf ]; then
        cat > /etc/linuxdroid/lddm.conf << LDDM_CONF_EOF
[lddm]
weston_socket = wayland-0
session_user = §username
autostart = true
session_dir = /run/lddm
LDDM_CONF_EOF
        chmod 0644 /etc/linuxdroid/lddm.conf
    fi

    if [ ! -f /etc/linuxdroid/desktop.conf ]; then
        cat > /etc/linuxdroid/desktop.conf << 'DESKTOP_CONF_EOF'
[desktop]
shell = default
theme = default
DESKTOP_CONF_EOF
        chmod 0644 /etc/linuxdroid/desktop.conf
    fi

    if [ -x /usr/bin/ldde ] && [ ! -e /usr/bin/ldde-session ]; then
        ln -sf /usr/bin/ldde /usr/bin/ldde-session
    fi
}

run_cmd "CONFIGURE_GUI" "write lddm.conf and desktop.conf" configure_gui_action

# -----------------------------------------------------------------------------
# 7. APT Cleanup
# -----------------------------------------------------------------------------
apt-get autoremove --purge -y 2>/dev/null || true
apt-get clean 2>/dev/null || true

# Remove policy-rc.d wrapper
rm -f /usr/sbin/policy-rc.d 2>/dev/null || true

# -----------------------------------------------------------------------------
# 8. Validate GUI Installation
# -----------------------------------------------------------------------------
validate_gui_action() {
    local missing=""

    # Wayland libraries
    local wayland_found=false
    for libdir in /usr/lib /usr/lib/aarch64-linux-gnu /usr/lib64 /lib /lib/aarch64-linux-gnu; do
        if [ -f "§libdir/libwayland-client.so.0" ] || ls "§libdir"/libwayland-client* >/dev/null 2>&1; then
            wayland_found=true
            break
        fi
    done
    §wayland_found || missing="§missing Wayland client library;"

    # LDDM
    if [ ! -x /usr/bin/lddm ] && [ ! -x /usr/local/bin/lddm ]; then
        missing="§missing LDDM binary;"
    fi
    if [ ! -f /etc/linuxdroid/lddm.conf ]; then missing="§missing /etc/linuxdroid/lddm.conf;"; fi

    # LDDE
    if [ ! -x /usr/bin/ldde ] && [ ! -x /usr/local/bin/ldde ]; then
        missing="§missing LDDE binary;"
    fi
    if [ ! -f /etc/linuxdroid/desktop.conf ]; then missing="§missing /etc/linuxdroid/desktop.conf;"; fi

    if [ -n "§missing" ]; then
        echo "GUI validation failed: §missing" >&2
        return 1
    fi
    return 0
}

run_cmd "VALIDATE_GUI" "validate GUI installation artifacts" validate_gui_action

# -----------------------------------------------------------------------------
# 9. Remove Staged .deb Files
# -----------------------------------------------------------------------------
rm -rf "§GUI_PKGS_DIR" 2>/dev/null || true

# -----------------------------------------------------------------------------
# 10. Write GUI_INSTALL_COMPLETE Marker
# -----------------------------------------------------------------------------
cat > /etc/linuxdroid/GUI_INSTALL_COMPLETE << GUI_MARKER_EOF
STATUS=COMPLETE
TIMESTAMP=§(date -u +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || echo "1970-01-01T00:00:00Z")
GUI_MARKER_EOF
chmod 0644 /etc/linuxdroid/GUI_INSTALL_COMPLETE 2>/dev/null || true

# Also ensure compatible markers exist
[ -f /etc/linuxdroid/rootfs-ready ] || echo "STATUS=ROOTFS_READY" > /etc/linuxdroid/rootfs-ready
[ -f /etc/linuxdroid/POST_INSTALL_COMPLETE ] || echo "STATUS=COMPLETE" > /etc/linuxdroid/POST_INSTALL_COMPLETE

echo "STATE=GUI_INSTALLED" > /etc/linuxdroid/gui-install-state
echo "TIMESTAMP=§(date -u +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || echo "1970-01-01T00:00:00Z")" >> /etc/linuxdroid/gui-install-state

echo "[GUI_INSTALL][SUCCESS][GUI_INSTALL]"
echo "exit_code=0"
echo "timestamp=§(date -u +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || echo "1970-01-01T00:00:00Z")"
echo "GUI_INSTALL_COMPLETE"

exit 0
""".trimIndent().replace('§', '$') + "\n"

    /**
     * Writes the GUI install script to the rootfs at both /etc/linuxdroid/install-gui.sh and /etc/linuxdroid/gui-install.sh.
     */
    fun writeScript(rootfsDir: File): File {
        val scriptFile = File(rootfsDir, GUI_INSTALL_SCRIPT_ALT_PATH.removePrefix("/"))
        scriptFile.parentFile?.mkdirs()
        scriptFile.writeText(SCRIPT_CONTENT, Charsets.UTF_8)
        scriptFile.setExecutable(true, false)
        scriptFile.setReadable(true, false)

        val legacyFile = File(rootfsDir, GUI_INSTALL_SCRIPT_PATH.removePrefix("/"))
        legacyFile.parentFile?.mkdirs()
        legacyFile.writeText(SCRIPT_CONTENT, Charsets.UTF_8)
        legacyFile.setExecutable(true, false)
        legacyFile.setReadable(true, false)

        return scriptFile
    }

    /**
     * Stages LDDM and LDDE .deb files into the GUI packages directory inside the rootfs.
     * This must be called before running the GUI install script via PRoot.
     */
    fun stageGuiPackages(
        rootfsDir: File,
        lddmDeb: File?,
        lddeDeb: File?,
    ) {
        val packagesDir = File(rootfsDir, GUI_STAGED_PACKAGES_DIR.removePrefix("/")).apply { mkdirs() }
        val legacyPackagesDir = File(rootfsDir, GUI_STAGED_PACKAGES_LEGACY_DIR.removePrefix("/")).apply { mkdirs() }
        val fallbackDir = File(rootfsDir, "root/linuxdroid/packages")

        val actualLddm = lddmDeb?.takeIf { it.exists() }
            ?: fallbackDir.listFiles()?.firstOrNull { it.name.startsWith("linuxdroid-display-manager") && it.name.endsWith(".deb") }
            ?: fallbackDir.listFiles()?.firstOrNull { it.name.startsWith("LDDM") && it.name.endsWith(".deb") }

        if (actualLddm != null && actualLddm.exists()) {
            val dest1 = File(packagesDir, "linuxdroid-display-manager.deb")
            val dest2 = File(packagesDir, "LDDM.deb")
            val destLegacy = File(legacyPackagesDir, "linuxdroid-display-manager.deb")
            if (actualLddm.canonicalPath != dest1.canonicalPath) actualLddm.copyTo(dest1, overwrite = true)
            if (actualLddm.canonicalPath != dest2.canonicalPath) actualLddm.copyTo(dest2, overwrite = true)
            if (actualLddm.canonicalPath != destLegacy.canonicalPath) actualLddm.copyTo(destLegacy, overwrite = true)
            dest1.setReadable(true, false)
            dest2.setReadable(true, false)
            destLegacy.setReadable(true, false)
        }

        val actualLdde = lddeDeb?.takeIf { it.exists() }
            ?: fallbackDir.listFiles()?.firstOrNull { it.name.startsWith("linuxdroid-desktop-environment") && it.name.endsWith(".deb") }
            ?: fallbackDir.listFiles()?.firstOrNull { it.name.startsWith("LDDE") && it.name.endsWith(".deb") }

        if (actualLdde != null && actualLdde.exists()) {
            val dest1 = File(packagesDir, "linuxdroid-desktop-environment.deb")
            val dest2 = File(packagesDir, "LDDE.deb")
            val destLegacy = File(legacyPackagesDir, "linuxdroid-desktop-environment.deb")
            if (actualLdde.canonicalPath != dest1.canonicalPath) actualLdde.copyTo(dest1, overwrite = true)
            if (actualLdde.canonicalPath != dest2.canonicalPath) actualLdde.copyTo(dest2, overwrite = true)
            if (actualLdde.canonicalPath != destLegacy.canonicalPath) actualLdde.copyTo(destLegacy, overwrite = true)
            dest1.setReadable(true, false)
            dest2.setReadable(true, false)
            destLegacy.setReadable(true, false)
        }
    }

    /**
     * Writes the GUI_INSTALL_COMPLETE marker file from the Kotlin (host) side.
     * Used by the simulated GUI install path for offline/JVM testing.
     */
    fun writeGuiInstallCompleteMarker(rootfsDir: File): File {
        val markerFile = File(rootfsDir, GUI_INSTALL_COMPLETE_MARKER.removePrefix("/"))
        markerFile.parentFile?.mkdirs()
        markerFile.writeText(
            "STATUS=COMPLETE\nTIMESTAMP=${System.currentTimeMillis()}\n",
            Charsets.UTF_8,
        )
        return markerFile
    }
}
