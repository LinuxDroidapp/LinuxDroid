# LinuxDroid Core Stack — Vendored Components Specification

LinuxDroid integrates its core components directly under `vendor/` as vendored source trees.

This architecture guarantees reproducible builds, source ownership, hermetic native toolchains, 16 KB ELF page alignment, and strict isolation between host Android runtime and guest Linux environments.

---

## 1. Vendored Components Registry & Revisions

Every LinuxDroid-owned component is maintained under `vendor/`:

| Component | Source Repository | Vendor Path | Pinned Commit SHA | Primary Role in Stack |
| :--- | :--- | :--- | :--- | :--- |
| **PRoot** | `https://github.com/LinuxDroidapp/proot` | `vendor/proot` | `caadcae0e7697ec29f02e231a3a88866561aacd0` | User-space rootless virtualization, syscall emulation, ptrace & seccomp sandboxing |
| **LDDM** | `https://github.com/LinuxDroidapp/LDDM` | `vendor/LDDM` | `aa6c3d38f874244bcd60162889a914637e4ddf46` | LinuxDroid Display Manager (Supervises LDDE against native host compositor) |
| **LDDE** | `https://github.com/LinuxDroidapp/LDDE` | `vendor/LDDE` | `9ee575e963d6d1ff4086fc16fb119daf6ead6db2` | LinuxDroid Desktop Environment (Wayland graphical shell and desktop workspace) |

---

## 2. Host Embedded Compositor Stack (Built from Source)

The single production compositor is host-side embedded **libweston-17** executing within `GuiHost`. Embedded Weston, Wayland, and Pixman are cross-compiled directly from source for Android `arm64-v8a` with 16 KB page alignment using `native/weston/build_wayland_stack.sh`:

| Component | Repository / Upstream | Target ABI | Role |
| :--- | :--- | :--- | :--- |
| **libweston-17** | `https://gitlab.freedesktop.org/wayland/weston.git` | `arm64-v8a` (Android NDK) | Embedded Wayland compositor |
| **libwayland-server / client** | `https://gitlab.freedesktop.org/wayland/wayland.git` | `arm64-v8a` (Android NDK) | Core Wayland IPC protocol libraries |
| **libpixman-1** | `https://gitlab.freedesktop.org/pixman/pixman.git` | `arm64-v8a` (Android NDK) | Pixel manipulation (NEON accelerated) |
| **libdrm** | `https://gitlab.freedesktop.org/mesa/drm.git` | `arm64-v8a` (Android NDK) | DRM format helpers |
| **libxkbcommon** | `https://github.com/xkbcommon/libxkbcommon.git` | `arm64-v8a` (Android NDK) | Keyboard layout & translation engine |
| **libffi** | `https://github.com/libffi/libffi.git` | `arm64-v8a` (Android NDK) | Foreign function interface |

All compiled shared libraries reside in `app/src/main/jniLibs/arm64-v8a/` and are packaged into the Android application.

---

## 3. Component Build & Packaging Pipeline

1. **PRoot & Loader**: Built by `scripts/build-proot.sh` using NDK r29 CMake. Generates `MANIFEST.txt` and stages to `app/src/main/assets/proot/arm64-v8a/` and `app/src/main/jniLibs/arm64-v8a/`.
2. **Wayland & Weston Stack**: Built by `native/weston/build_wayland_stack.sh` using Meson cross-compilation with NDK r29 Clang. Enforces `-Wl,-z,max-page-size=16384`.
3. **LDDM & LDDE Debian Packages**: Cross-compiled by `scripts/build-packages.sh` for `arm64` Linux using `aarch64-linux-gnu-gcc` and packaged via `dpkg-deb` into `app/src/main/assets/packages/`.
4. **Master Orchestrator**: `scripts/build-runtime.sh` runs all builds, stages to `build/linuxdroid/`, and executes `scripts/validate-artifacts.sh`.

---

## 4. PRoot Android/ARM64 Compatibility Baseline

The vendored PRoot revision incorporates essential Android compatibility modifications:
- `PTRACE_PEEKDATA` memory read workaround for Bionic ptrace behavior.
- ARM64 Top-Byte-Ignore (TBI) pointer handling in syscall translation.
- Seccomp exit `SIGSYS` trap handler and graceful fallback.
- Guest syscall translation for Android kernel sandboxing.
- Ashmem-backed emulation for SYSV IPC shared memory.
- 16 KB ELF page alignment (`-Wl,-z,max-page-size=16384`) for Android 16+ compatibility.
