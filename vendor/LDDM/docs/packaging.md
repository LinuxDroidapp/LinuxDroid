# LDDM Production Packaging Guide

## 1. Overview

The **LinuxDroid Display Manager (LDDM)** is distributed as a native Linux Debian package:

```text
Package: linuxdroid-display-manager
Version: 0.1.0 (authoritative from project VERSION)
Section: x11
Priority: optional
```

LDDM packaging is designed strictly around Linux-native conventions. It introduces zero Android-specific paths, NDK headers, APK bindings, or PRoot assumptions. The resulting package installs seamlessly into standard Debian, Ubuntu, and Kali Linux rootfs environments across **ARM64** and **AMD64** architectures.

---

## 2. Architectural Role in LinuxDroid

```text
LinuxDroid Android App
        ↓
LinuxDroid Runtime
        ↓
Guest Init
        ↓
/usr/bin/linuxdroid-display-manager (or /usr/bin/lddm)
        ↓
Session (/etc/linuxdroid/lddm.conf)
        ├── Process Supervisor
        ├── Weston Manager (Compositor)
        ├── LDDE Manager (Desktop Environment)
        └── Recovery Manager (Fault Recovery)
```

LDDM owns graphical session orchestration, process supervision, and fault recovery. Packaging only installs, configures, and establishes the runtime environment for that functionality.

---

## 3. Installation Layout

The package establishes a minimal, clean Linux filesystem hierarchy:

```text
/usr/bin/
    ├── lddm                           # Core executable binary
    └── linuxdroid-display-manager     # Symbolic link -> lddm

/etc/linuxdroid/
    ├── lddm.conf                      # Primary system configuration (Debian conffile)
    └── lddm.conf.defaults             # Reference built-in defaults

/etc/lddm/
    └── lddm.conf                      # Compatibility symlink -> ../linuxdroid/lddm.conf

/usr/share/doc/linuxdroid-display-manager/
    ├── README.md                      # Project documentation
    ├── copyright                      # Machine-readable copyright (MIT)
    ├── changelog.Debian.gz            # Debian changelog
    ├── architecture.md                # Subsystem architecture
    ├── configuration.md               # Configuration reference
    ├── lifecycle.md                   # State machine specification
    ├── logging.md                     # Logging architecture
    ├── process.md                     # Process supervisor specification
    ├── weston.md                      # Weston compositor integration
    ├── ldde.md                        # LDDE integration specification
    ├── recovery.md                    # Recovery subsystem architecture
    └── packaging.md                   # Packaging and deployment guide
```

### Package-Owned vs. Runtime-Owned Files

| Category | File Paths | Ownership & Lifespan |
| :--- | :--- | :--- |
| **Package-Owned** | `/usr/bin/lddm`, `/etc/linuxdroid/lddm.conf`, `/usr/share/doc/...` | Created, upgraded, and removed by package manager. |
| **Runtime-Owned** | `/run/lddm/`, `/run/user/<uid>/`, `wayland-0`, PID files, logs | Created dynamically at session startup; deleted on shutdown. Never packaged into `.deb`. |

---

## 4. Configuration System & Conffiles

### System Configuration Location
Primary configuration is read from:
```text
/etc/linuxdroid/lddm.conf
```
With automated fallback support for:
```text
/etc/lddm/lddm.conf
```
And user-specific overrides:
```text
~/.config/lddm/lddm.conf
```

### Conffiles Protection
`/etc/linuxdroid/lddm.conf` is registered in `DEBIAN/conffiles`. When a user modifies `/etc/linuxdroid/lddm.conf`, package upgrades will **never silently overwrite** user modifications. `dpkg` will prompt or preserve user changes according to Debian policy.

### Configuration Migration Framework (`lddm::config::ConfigMigrator`)
LDDM includes a configuration versioning and migration engine:
* **Schema Versioning**: Stored in `[meta]\nversion = 1`.
* **Automatic Detection**: Detects legacy unversioned configs (v0) and current schema (v1).
* **Deterministic Migration**: Injects version metadata, preserves existing keys and custom values verbatim, and logs all migration steps.
* **CLI Command**:
  ```bash
  lddm --migrate-config /path/to/old.conf [/path/to/new.conf]
  ```

---

## 5. Maintainer Scripts & Lifecycle Semantics

### `postinst`
* Configures permissions (`0755` for directories/binaries, `0644` for configs and docs).
* Ensures compatibility symlinks exist (`/usr/bin/linuxdroid-display-manager -> lddm`, `/etc/lddm/lddm.conf -> /etc/linuxdroid/lddm.conf`).
* **Does not start graphical sessions** automatically.

### `prerm`
* Checks if LDDM is currently active and issues a warning notice.
* Does not terminate running sessions abruptly.

### `postrm`
* **`remove`**: Removes binaries and documentation. Preserves `/etc/linuxdroid/lddm.conf` and user configuration.
* **`purge`**: Removes `/etc/linuxdroid/lddm.conf` and removes empty `/etc/linuxdroid` and `/etc/lddm` directories.

---

## 6. Guest Init Integration Contract

LinuxDroid's runtime uses Guest Init to launch LDDM without requiring systemd or logind:

```text
Guest Init (e.g. init script / custom supervisor)
    ↓
/usr/bin/linuxdroid-display-manager --config /etc/linuxdroid/lddm.conf
    ↓
LDDM Graphical Session
```

### Example Guest Init Script
```bash
#!/bin/sh
# /etc/init.d/lddm or container init entrypoint

export XDG_RUNTIME_DIR="/run/user/$(id -u)"
mkdir -p -m 0700 "$XDG_RUNTIME_DIR"

exec /usr/bin/linuxdroid-display-manager --config /etc/linuxdroid/lddm.conf
```

---

## 7. Dependency Model

The Debian package defines clear component separation:

| Dependency Type | Package Name | Reason |
| :--- | :--- | :--- |
| **Depends** | `libc6 (>= 2.34)`, `libstdc++6 (>= 13)` | Essential C++20 standard library and POSIX runtime. |
| **Recommends** | `weston (>= 10.0)` | Default Wayland compositor managed by LDDM. |
| **Suggests** | `linuxdroid-desktop-environment \| ldde` | Desktop environment launched by LDDM for full desktop sessions. |
| **Provides** | `display-manager` | Virtual package fulfilling Debian display manager contract. |

> [!NOTE]
> `systemd` is explicitly **NOT** a dependency. LDDM runs in minimal container roots, PRoot guest filesystems, and SysVinit/OpenRC environments.

---

## 8. Building the Debian Package

### Prerequisites
* GCC 13+ or Clang 18+ (C++20 compliant)
* CMake 3.20+
* `dpkg-deb` and `gzip`

### Build Commands

```bash
# 1. Configure Release build
cmake -B build-release -DCMAKE_BUILD_TYPE=Release -DLDDM_BUILD_TESTS=ON -DLDDM_WARNINGS_AS_ERRORS=ON

# 2. Build binaries and libraries
cmake --build build-release -j$(nproc)

# 3. Build Debian package via CMake target
cmake --build build-release --target package_deb

# Alternatively, run the packaging script directly:
./packaging/build_package.sh --build-dir build-release --output-dir build-release/packages --arch arm64
```

### Output Package
```text
build-release/packages/linuxdroid-display-manager_0.1.0_amd64.deb
build-release/packages/linuxdroid-display-manager_0.1.0_arm64.deb
```

---

## 9. Validation and Quality Assurance

* **Inspect Package Control Info**:
  ```bash
  dpkg-deb -I build-release/packages/linuxdroid-display-manager_0.1.0_*.deb
  ```
* **Inspect Package File Tree**:
  ```bash
  dpkg-deb -c build-release/packages/linuxdroid-display-manager_0.1.0_*.deb
  ```
* **Run Automated Packaging Tests**:
  ```bash
  ./build-release/tests/test_package_deb
  ./build-release/tests/test_package_upgrade_lifecycle
  ```

