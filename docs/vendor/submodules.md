# LinuxDroid Core Stack — Git Submodules Specification

LinuxDroid consumes its core native and Linux graphical stack components directly from dedicated, maintained repositories integrated as Git submodules under `vendor/`.

This architecture guarantees reproducible builds, source ownership, hermetic native toolchains, and strict isolation between host Android runtime and guest Linux environments.

---

## 1. Submodule Registry & Pinned Revisions

Every core component is strictly pinned to an authoritative commit SHA in `.gitmodules` and Git index. Builds never track floating branches.

| Component | Repository URL | Vendor Path | Pinned Commit SHA | Primary Role in Stack |
| :--- | :--- | :--- | :--- | :--- |
| **PRoot** | `https://github.com/LinuxDroidapp/proot` | `vendor/proot` | `caadcae0e7697ec29f02e231a3a88866561aacd0` | User-space chroot/bind virtualization, syscall emulation, ptrace & seccomp sandboxing |
| **LDDM** | `https://github.com/LinuxDroidapp/LDDM` | `vendor/LDDM` | `aa6c3d38f874244bcd60162889a914637e4ddf46` | LinuxDroid Display Manager (Wayland login greeter and session manager) |
| **LDDE** | `https://github.com/LinuxDroidapp/LDDE` | `vendor/LDDE` | `9ee575e963d6d1ff4086fc16fb119daf6ead6db2` | LinuxDroid Desktop Environment (lightweight graphical shell and workspace) |
| **Wayland** | `https://github.com/LinuxDroidapp/wayland` | `vendor/wayland` | `381af21cf84f13be0ca24aed756a9cded3290d49` | Core Wayland IPC protocol library (`libwayland-server`, `libwayland-client`, `libwayland-cursor`) |
| **Weston** | `https://github.com/LinuxDroidapp/weston` | `vendor/weston` | `9669073fe8f411ef3e9f40a36d0ec9aa68362fa2` | Reference Wayland compositor (`libweston-17`), Android GLES renderer plugin (`gl-renderer`) |
| **wayland-protocols** | `https://github.com/LinuxDroidapp/wayland-protocols` | `vendor/wayland-protocols` | `afb614d5fcbd02d261a6ae91920aa91cf3915a8a` | Wayland protocol XML specifications (xdg-shell, linux-dmabuf, presentation-time, etc.) |
| **pixman** | `https://github.com/LinuxDroidapp/pixman` | `vendor/pixman` | `cc03b56c7b2b2e06199bb9b115af55f5b42b12ba` | Low-level pixel manipulation library optimized with ARM NEON SIMD (`libpixman-1.so`) |

---

## 2. Cloning & Initializing

To clone the repository with all submodules initialized:

```bash
git clone --recurse-submodules https://github.com/LinuxDroidapp/LinuxDroid.git
cd LinuxDroid
```

For an existing checkout where submodules have not yet been checked out:

```bash
git submodule update --init --recursive
```

To verify that all submodules match their expected pinned commits without dirty modifications:

```bash
git submodule status
```

---

## 3. Integration & Build Architecture

### 3.1 Strict Source Tree Isolation
- Submodule repositories inside `vendor/*` are maintained as clean, unmodified source trees.
- Build systems (Meson, CMake, NDK) must not write build artifacts, generated headers, or in-place patches into `vendor/*`.
- For builds requiring patching or out-of-tree generation (e.g. `native/weston/build_wayland_stack.sh`), source trees are synchronized to staging directories (`native/weston/src/*`) or generated into build-specific binary directories (`CMAKE_CURRENT_BINARY_DIR`).

### 3.2 Pixman Dependency Graph
- **Authoritative Source**: Built directly from `vendor/pixman` with ARM NEON acceleration (`-Da64-neon=enabled`) for target `arm64-v8a`.
- **Consumers**:
  - `vendor/weston` (`libweston-17`) links against `libpixman-1.so` via pkg-config.
  - `native/bridge` (`linuxdroid_bridge.so`, `gl-renderer.so`, `pixman_renderer_test`) compiles with `${WESTON_PREFIX}/include/pixman-1` and links against `libpixman-1.so`.
  - Android application packages `libpixman-1.so` into `app/src/main/jniLibs/arm64-v8a/`.
- Neither `wayland`, `wayland-protocols`, `LDDM`, nor `LDDE` consume pixman directly.

### 3.3 PRoot Android/ARM64 Patch Baseline
The pinned PRoot revision (`caadcae0e7697ec29f02e231a3a88866561aacd0`) incorporates essential Android compatibility modifications:
- `PTRACE_PEEKDATA` memory read workaround for Bionic ptrace behavior.
- ARM64 Top-Byte-Ignore (TBI) pointer handling in syscall translation.
- Seccomp exit `SIGSYS` trap handler and graceful fallback.
- Guest syscall translation for Android kernel sandboxing.
- Ashmem-backed emulation for SYSV IPC shared memory.

---

## 4. Submodule Maintenance Workflow

When updating a submodule to a new upstream release or bug fix commit:

1. **Navigate to Submodule Directory**:
   ```bash
   cd vendor/<component>
   git fetch origin
   git checkout <target-commit-sha>
   ```

2. **Verify Toolchain and ABI Compatibility**:
   - Ensure the updated source builds with NDK 30 (Clang 23.1.0) and target API 36/37.
   - Run automated unit and integration tests.

3. **Record Updated Provenance**:
   - Update `native/weston/dependencies.json`.
   - Update `app/src/main/assets/components_provenance.json`.
   - Update component-specific provenance files (e.g. `pixman_provenance.json`, `weston_provenance.json`).

4. **Commit Submodule Reference in LinuxDroid**:
   ```bash
   cd /workspaces/LinuxDroid
   git add vendor/<component> native/weston/dependencies.json app/src/main/assets/components_provenance.json
   git commit -m "chore(vendor): bump <component> to <target-commit-sha>"
   ```
