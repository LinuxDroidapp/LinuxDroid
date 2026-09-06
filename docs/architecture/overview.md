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
│        LinuxDroid Native & Submodule Stack (vendor/)   │
│   • vendor/proot (PRoot engine, loader, Android fixes) │
│   • vendor/LDDM (LinuxDroid Display Manager)           │
│   • vendor/LDDE (LinuxDroid Desktop Environment)       │
│   • vendor/wayland (libwayland-server/client/cursor)   │
│   • vendor/weston (libweston-17, gl-renderer plugin)   │
│   • vendor/wayland-protocols (wayland protocol specs)  │
│   • vendor/pixman (ARM NEON optimized pixel pipeline)  │
└───────────────────────────┬────────────────────────────┘
                            │ ptrace / syscall interception & Wayland IPC
┌───────────────────────────▼────────────────────────────┐
│          Persistent Linux rootfs and applications      │
│          (/bin/sh, apt, dpkg, Debian arm64)            │
└────────────────────────────────────────────────────────┘
```

Core native and Linux stack components are maintained in dedicated repositories under `https://github.com/LinuxDroidapp/*` and consumed as Git submodules in `vendor/`. For detailed submodule specifications and commit pinning guidelines, see [docs/vendor/submodules.md](../vendor/submodules.md).

## 3. Key Design Tenets
1. **Unconditional Persistence:** Rootfs directories (`<filesDir>/environments/<id>/rootfs`) are never touched or purged on stop, crash, or application restart.
2. **Wayland First:** Wayland is the primary graphical pipeline, connecting to Android's `ANativeWindow` display surface.
3. **Decoupled Lifecycle:** Android UI lifecycle is separated from the Linux runtime lifecycle via foreground services (`LinuxSessionService`).
4. **Host Compatibility Layer:** High-frequency rendering and audio bypass Java/JNI loops, executing directly across native platform boundaries.
