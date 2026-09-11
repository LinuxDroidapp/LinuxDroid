# LinuxDroid Finalized Production Architecture

## 1. Architectural Overview

LinuxDroid provides a production-grade, rootless Linux environment on Android. The finalized architecture enforces a strict separation of concerns across the Android host, LinuxDroid native layer, PRoot virtualization, and the guest Linux userspace.

```
+-------------------------------------------------------------------------+
|                              Android Host                               |
|                                                                         |
|  Android UI (Compose) ──> GuiSurfaceView ──> Surface                    |
|                                                │                        |
|                                                ▼                        |
|                          NativeBridge.onSurfaceCreated()                |
|                                                │                        |
|                                                ▼                        |
|                          DisplayBridge ──> ANativeWindow                |
|                                                │                        |
|                                                ▼                        |
|                                         LinuxDroid GuiHost              |
|                                                │                        |
|                     ┌──────────────────────────┴──────────────────────┐ |
|                     ▼                                                 ▼ |
|         Embedded libweston-17                               EGL/GLES    |
|            Wayland Server                              Android Presentation
|       ($XDG_RUNTIME_DIR/wayland-0)                     (AHardwareBuffer)|
|                     │                                                 │ |
+─────────────────────┼─────────────────────────────────────────────────┼─+
                      │ (UNIX Domain Socket)                            │
                      ▼                                                 ▼
+───────────────────────────────────────────+         +───────────────────+
|               Linux Guest                 |         |   SurfaceFlinger  |
|                                           |         |         │         |
|  PRoot (rootless isolation)               |         |         ▼         |
|     │                                     |         |  Physical Display |
|     ▼                                     |         +───────────────────+
|  Custom ARM64 Linux Rootfs                |
|     │                                     |
|     ▼                                     |
|  /sbin/linuxdroid-init (GUI Mode)         |
|     │                                     |
|     ▼                                     |
|  LDDM (Session & Lifecycle Supervisor)    |
|     │                                     |
|     ├─► Waits for native compositor ready |
|     │                                     |
|     ▼                                     |
|  LDDE (Desktop Environment)               |
|     │                                     |
|     ▼                                     |
|  Wayland Client Applications              |
+───────────────────────────────────────────+
```

---

## 2. Single Compositor Rule

**There is exactly ONE production compositor in LinuxDroid:**
LinuxDroid native embedded **libweston-17** executing inside the Android host process.

### Rules:
1. LDDM in the guest rootfs must **NEVER** launch `/usr/bin/weston` or any other guest compositor.
2. The Wayland display socket (`wayland-0`) is created and owned exclusively by the native `GuiHost` via `wl_display_add_socket`.
3. LDDM verifies the presence and usability of `wayland-0` via non-blocking socket connection test (`WestonReadinessDetector`) and proceeds to start `LDDE`.
4. If the GUI fails to start or crashes, the PRoot container and CLI shell remain completely functional and unaffected.

---

## 3. Detailed Subsystem Boundaries

### 3.1 Android Host Platform
- **Ownership**: Physical display, windowing lifecycle, touch/motion input dispatch, audio output, camera/sensors, and hardware permissions.
- **Responsibilities**:
  - Hosts `GuiSurfaceView` and delivers `Surface` events.
  - Passes touch gestures, keyboard input, and lifecycle events to `NativeBridge`.

### 3.2 LinuxDroid Native Layer (`native/bridge`)
- **Ownership**: `libweston-17`, Wayland display server, EGL/GLES rendering pipeline, Android presentation pool (`AHardwareBuffer`), and native PTY process spawning.
- **Responsibilities**:
  - Initializes libweston compositor with `linuxdroid_backend`.
  - Binds presentation output to `ANativeWindow`.
  - Exposes Wayland socket to guest via shared `/run/user/<uid>` or `/tmp` directory.
  - Translates Android keycodes and touch events into standard Linux evdev keycodes and Wayland seat events.

### 3.3 PRoot Virtualization Layer (`vendor/proot`)
- **Ownership**: Rootless Linux process virtualization, system call interception via ptrace, pathname translation, and pseudo-root (`-0`) execution.
- **Responsibilities**:
  - Isolated chroot-like environment pointing to `rootfsPath`.
  - Dynamic host bindings (`/dev`, `/proc`, `/sys`, `/tmp`, `/dev/pts`, `/dev/shm`).
  - Strict preservation of ARM64 TBI pointer addresses.

### 3.4 Custom ARM64 Linux Userspace
- **Ownership**: Linux binaries (`/bin`, `/sbin`, `/usr`), package ecosystem, system utilities, and default shell.
- **Responsibilities**:
  - Authoritative rootfs containing pre-installed graphical dependencies (Wayland client libraries, fonts, XKB configurations, LDDM, LDDE).
  - Entrypoint executed upon container launch: `/sbin/linuxdroid-init`.

### 3.5 LDDM (LinuxDroid Display Manager)
- **Ownership**: Guest graphical session management, user login, desktop supervision, and failure recovery.
- **Responsibilities**:
  - Checks native compositor readiness.
  - Spawns and supervises LDDE (`/usr/bin/ldde`).
  - Monitors LDDE health and restarts it if configured, without killing PRoot or the native compositor.

### 3.6 LDDE (LinuxDroid Desktop Environment)
- **Ownership**: Workspace management, taskbar, application launcher, and window chrome.
- **Responsibilities**:
  - Acts as a pure Wayland client communicating over `WAYLAND_DISPLAY=wayland-0`.

