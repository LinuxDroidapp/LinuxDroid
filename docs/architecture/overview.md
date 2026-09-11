# LinuxDroid — Architecture Overview

## 1. Introduction & Core Mission
LinuxDroid is a native Android application that provides a persistent, rootless Linux userspace running directly on Android hardware.

LinuxDroid is **NOT** a VM product, does **NOT** require root/su access, does **NOT** depend on custom kernels or kernel modules, does **NOT** use ISO live-boot semantics, and does **NOT** require BusyBox as a dependency.

## 2. Layered Architecture
```
┌────────────────────────────────────────────────────────┐
│                   Jetpack Compose UI                   │
│   (HomeScreen, EnvironmentList, Terminal, Diagnostics) │
└───────────────────────────┬────────────────────────────┘
                            │ ViewModel StateFlow
┌───────────────────────────▼────────────────────────────┐
│                    ViewModel Layer                     │
│  (EnvironmentViewModel, TerminalViewModel, Diagnostics)│
└───────────────────────────┬────────────────────────────┘
                            │ Domain Calls
┌───────────────────────────▼────────────────────────────┐
│                   Core / Domain Layer                  │
│    (SessionManager, ProcessManager, PackageManager,    │
│     DiagnosticsManager, ResourceManager, Storage)      │
└───────────────────────────┬────────────────────────────┘
                            │ Runtime & Host Abstractions
┌───────────────────────────▼────────────────────────────┐
│             Host Compatibility Layer (core-host)       │
│  (HostGraphics, HostGpu, HostAudio, HostInput, Net)    │
└───────────────────────────┬────────────────────────────┘
                            │ Direct High-Performance C++
┌───────────────────────────▼────────────────────────────┐
│               Native Bridge (liblinuxdroid_bridge)     │
│       Genuine Android JNI and host hardware bridges    │
└───────────────────────────┬────────────────────────────┘
                            │ consumes versioned artifact
┌───────────────────────────▼────────────────────────────┐
│        LinuxDroid Runtime Integration                  │
│ RuntimeAssetsManager · RootfsManager · LaunchPlan      │
│ Bindings · ProcessLauncher · PtyLauncher               │
└───────────────────────────┬────────────────────────────┘
┌───────────────────────────▼────────────────────────────┐
│        LinuxDroid Native & Vendored Stack (vendor/)    │
│   • vendor/proot (PRoot engine, loader, Android fixes) │
│   • vendor/LDDM (LinuxDroid Display Manager)           │
│   • vendor/LDDE (LinuxDroid Desktop Environment)       │
│   • Host Compositor: embedded libweston-17 (GuiHost)   │
│   • Native libs: libwayland, libpixman-1 (NEON), DRM   │
└───────────────────────────┬────────────────────────────┘
                            │ ptrace / syscall interception
┌───────────────────────────▼────────────────────────────┐
│    Persistent Linux rootfs and applications            │
│    (/bin/sh, apt, dpkg, Debian arm64)                  │
│    • /tmp/wayland-0 (Wayland compositor socket)        │
│    • /usr/bin/lddm  (Supervises LDDE session)          │
│    • /usr/bin/ldde  (Desktop environment)              │
│    • Wayland client apps (terminals, desktop tools)    │
└────────────────────────────────────────────────────────┘
```

LinuxDroid-owned components (PRoot, LDDM, LDDE) are directly vendored in `vendor/`. The single production compositor is host-embedded **libweston-17** executing inside Android host `GuiHost`. Embedded libweston-17, Wayland server/client, Pixman (NEON accelerated), and support libraries are built from source natively for Android `arm64-v8a` with 16 KB page alignment and packaged into `app/src/main/jniLibs/arm64-v8a/`. The guest rootfs consumes the native compositor socket via PRoot `/tmp/wayland-0` mount. For detailed specifications, see [docs/vendor/submodules.md](../vendor/submodules.md).


## 3. Key Design Tenets
1. **Unconditional Persistence:** Rootfs directories (`<filesDir>/environments/<id>/rootfs`) are never touched or purged on stop, crash, or application restart.
2. **Wayland First:** Wayland is the primary graphical pipeline, connecting to Android's `ANativeWindow` display surface.
3. **Decoupled Lifecycle:** Android UI lifecycle is separated from the Linux runtime lifecycle via foreground services (`LinuxSessionService`).
4. **Host Compatibility Layer:** High-frequency rendering and audio bypass Java/JNI loops, executing directly across native platform boundaries.
