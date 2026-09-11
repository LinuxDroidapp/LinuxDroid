# LinuxDroid Production Migration — Baseline Document

## 1. Executive Summary

This document establishes the authoritative technical baseline for the LinuxDroid project prior to the final production migration. LinuxDroid is a rootless Linux userspace environment running on Android, utilizing PRoot for filesystem virtualization and process isolation, backed by a native embedded Wayland compositor (libweston-17) running inside the Android host.

## 2. Component Classifications

All components are classified according to the migration charter:

| Subsystem / Component | Classification | Notes & Invariants |
| :--- | :--- | :--- |
| **PRoot Engine** (`vendor/proot`) | **FROZEN** | Production PRoot with static talloc, static shmem, ARM64 TBI pointer untagging, and seccomp filtering. Untouched. |
| **CLI Runtime & Terminal** | **FROZEN** | `TerminalSession`, `PtyBridge`, `RuntimeManager`, and PTY process lifecycle. Protected baseline. |
| **Native Bridge & Display** | **FROZEN** | `GuiSurfaceView`, `NativeBridge`, `DisplayBridge`, `GuiHost`, and Android presentation engine. |
| **Host Logging & Diagnostics** | **FROZEN** | Centralized `LinuxDroidLogger`, `LogFileManager`, and diagnostic categories. |
| **Guest Init** (`GuestInit.kt`) | **ADAPT** | Update `/sbin/linuxdroid-init` GUI branch to strictly hand off to LDDM, removing arbitrary bypasses. |
| **Desktop Session** | **ADAPT** | Ensure session startup delegates deterministically to GuestInit in `StartMode.GUI`. |
| **GUI State Model** | **ADAPT** | Expand `GuiState` enum to include `STARTING` and `RUNNING` alongside existing states. |
| **Virtual Keyboard** | **ADAPT** | Implement full Linux desktop key cluster (Esc, Tab, Ctrl, Alt, Super, F1-F12, terminal shortcuts) on Android UI. |
| **LDDM** (`vendor/LDDM`) | **MIGRATE** | Decouple from spawning `/usr/bin/weston` child process; check host native libweston-17 Wayland socket readiness; supervise LDDE. |
| **GUI Installer** | **MIGRATE** | Remove obsolete `apt install weston` and `weston.ini` logic; validate LDDM/LDDE integration idempotently. |
| **Build Configuration** | **MIGRATE** | Update `libs.versions.toml` to minSdk 36, compileSdk 37, targetSdk 37, and NDK r29 stable (`29.0.14206865`). |
| **Rootfs** | **OUT OF SCOPE** | Externally built custom ARM64 Linux rootfs. LinuxDroid merely mounts and launches it. |

---

## 3. Detailed Component Audit

### 3.1 PRoot Implementation & Patches
- **Source**: Directly vendored under `vendor/proot`.
- **Key Capabilities**:
  - Rootless isolation (`-0`, `-r <rootfs>`, `--kill-on-exit`, `--link2symlink`).
  - Statically linked Samba talloc (`talloc STATIC`) and Android shared memory (`android-shmem STATIC`).
  - Strict 64-bit ARM64 pointer sanitization (`UNTAG_ADDRESS` macro masking top 8 bits for Android TBI compatibility).
  - Production binary artifacts in `app/src/main/assets/proot/arm64-v8a/` (`proot` and `loader`).
  - 16 KB ELF segment alignment confirmed (`0x4000` LOAD alignment).

### 3.2 Current CLI Boot Path
1. User requests terminal session or executes command in CLI mode (`StartMode.CLI`).
2. Host preboot (`HostPreboot.kt`) creates runtime directory bindings:
   - Dynamic mounts: `/dev`, `/proc`, `/sys`, `/tmp`, `/dev/pts`, `/dev/shm`.
3. PRoot command constructed by `ProotCommandBuilder`:
   - Executable: `proot -0 --kill-on-exit --link2symlink -r <rootfs> -b ... -w <cwd> /sbin/linuxdroid-init <command>`.
4. Process launched inside pseudo-terminal (`NativeBridge.createPtyProcess`).
5. `/sbin/linuxdroid-init` evaluates `LINUXDROID_START_MODE=CLI`.
6. Handoff to user's configured shell (`$SHELL -l`).
7. Result: Clean, isolated, responsive interactive Linux CLI session.

### 3.3 Current GUI Boot Path & The Single-Compositor Model
- **Architectural Requirement**: There must be **ONE** production compositor: LinuxDroid native embedded **libweston-17**.
- **Host GUI Path**:
  - `GuiSurfaceView` (Android UI) acquires Android `Surface`.
  - JNI call to `NativeBridge.onSurfaceCreated()`.
  - `DisplayBridge` acquires `ANativeWindow_fromSurface()`.
  - `GuiHost` initializes embedded `libweston-17` with `linuxdroid_backend`.
  - EGL / GLES renderer initializes with `AHardwareBuffer` presentation pool.
  - Wayland display socket is created at `$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY` (e.g. `/run/user/1000/wayland-0`).
- **Guest GUI Path**:
  - `DesktopSession` launches PRoot with `StartMode.GUI`.
  - `/sbin/linuxdroid-init` evaluates `LINUXDROID_START_MODE=GUI`.
  - Handoff directly to `/usr/bin/lddm`.
  - LDDM detects that the native libweston-17 Wayland socket is ready.
  - LDDM starts and supervises `ldde` (LinuxDroid Desktop Environment).
  - LDDE and guest graphical applications connect as Wayland clients to `wayland-0`.

### 3.4 Native Library Loading Path
- Single entrypoint: `com.linuxdroid.native_bridge.NativeBridge`.
- Loads `liblinuxdroid_bridge.so`.
- Dynamic dependencies in `jniLibs/arm64-v8a/`:
  - `libweston-17.so`
  - `libgl-renderer.so`
  - `libwayland-server.so`
  - `libwayland-client.so`
  - `libwayland-cursor.so`
  - `libpixman-1.so`
  - `libxkbcommon.so`
  - `libdrm.so`
  - `libffi.so`
- All libraries have been verified with 16 KB page-size segment alignment.

### 3.5 Build & Toolchain State
- Version catalog: `gradle/libs.versions.toml`.
- Baseline SDK: compileSdk 36, minSdk 36, targetSdk 36.
- Target SDK: compileSdk 37, minSdk 36, targetSdk 37.
- Baseline NDK: 30.0.16138531 (NDK r30 RC).
- Target NDK: 29.0.14206865 (NDK r29 stable).

