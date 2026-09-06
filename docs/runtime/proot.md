# LinuxDroid — PRoot Runtime Integration

## 1. Ownership & Architecture

The authoritative PRoot implementation for LinuxDroid is maintained in the dedicated repository:

```text
LinuxDroidapp/proot (https://github.com/LinuxDroidapp/proot)
```

It is integrated into LinuxDroid as a Git submodule:

```text
vendor/proot/
```

LinuxDroid builds and packages PRoot directly from `vendor/proot/` using the modern toolchain stack:
- **Gradle**: 9.7.1 (`./gradlew`, no Maven)
- **NDK**: 30 (`30.0.16138531`, Clang 23.1.0)
- **CMake**: 4.4.3
- **Target SDK**: Android 16+ (API 36 / 37)
- **Target ABI**: `arm64-v8a` (with support for `armeabi-v7a`, `x86`, `x86_64`)

```text
LinuxDroidapp/proot (submodule @ vendor/proot)
        │
        ├── CMakeLists.txt & build.gradle.kts
        │   └── :vendor:proot:assembleAndroidDist
        │
        ├── Standalone Executables (Assets):
        │   ├── assets/proot/arm64-v8a/proot (statically linked talloc + android-shmem)
        │   ├── assets/proot/arm64-v8a/loader
        │   └── assets/proot/arm64-v8a/MANIFEST.txt (SHA-256 verification manifest)
        │
        └── Carrier Shared Libraries (JNI):
            ├── jniLibs/arm64-v8a/libproot.so
            ├── jniLibs/arm64-v8a/libproot_loader.so
            ├── jniLibs/arm64-v8a/libtalloc.so
            └── jniLibs/arm64-v8a/libandroid-shmem.so
```

---

## 2. Technical Compatibility Features

### A. Standalone Binary Execution (Zero Host Library Pollution)
`HostPreboot` intentionally clears `LD_LIBRARY_PATH` and `LD_PRELOAD` prior to spawning PRoot from `context.filesDir/runtime/arm64-v8a/proot` to prevent host environment pollution. To guarantee immediate standalone execution without relying on `/system/bin/linker64` resolving non-standard search paths:
- `talloc` and `android-shmem` are compiled and linked **statically** into the standalone `proot` executable.
- The binary only links against standard Bionic system libraries: `liblog.so`, `libandroid.so`, `libm.so`, `libdl.so`, `libc.so`.
- 16KB ELF page size alignment (`-Wl,-z,max-page-size=16384`) is enforced for Android 15/16.

### B. ARM64 Tagged Pointer (TBI) Address Normalization
On Android 16 and devices using the Scudo hardened memory allocator, 64-bit pointers contain non-zero top-byte tags (e.g. `0xb400007c4165ec40`). Kernel syscalls like `ptrace(PTRACE_PEEKDATA, ...)` and `process_vm_readv` reject tagged addresses with `EINVAL` or `EFAULT`.
- `vendor/proot/src/arch.h` provides `normalize_tracee_address` and `UNTAG_ADDRESS` masking `0x00FFFFFFFFFFFFFFULL`.
- Normalization is consistently applied across `ptrace.c`, `mem.c` (`write_data`, `writev_data`, `read_data`, `read_string`, `peek_word`, `poke_word`), and `aoxp.c`.

### C. Seccomp Voided Syscall Handling
Sylirre's sysexit handling preserves voided syscall semantics (`PR_void`) without regressing to `ENOSYS`.

---

## 3. Build & Synchronization Pipeline

The root Gradle build automates the PRoot distribution lifecycle:
1. `:vendor:proot:assembleAndroidDist`: Compiles PRoot via CMake 4.4.3 and NDK 30, generates ELF binaries and the SHA-256 `MANIFEST.txt` under `vendor/proot/dist/android/<abi>/`.
2. `:app:syncProotArtifacts`: Automatically synchronizes distribution artifacts into `app/src/main/assets/proot/arm64-v8a/` and `app/src/main/jniLibs/arm64-v8a/` before building the APK.
3. Provenance is tracked in `app/src/main/assets/components_provenance.json` and inspected by `ComponentProvenanceManager`.

---

## 4. Documented Launch Shape

The command builder produces commands structured as follows:

```bash
<runtime-dir>/proot \
  --rootfs=<env-id>/rootfs/          # Rootfs filesystem root
  --root-id                          # Emulate UID/GID as 0
  --cwd=<workingDirectory>           # Starting working directory
  --bind=/dev                        # Host /dev binding
  --bind=/proc                       # Host /proc binding
  --bind=/sys                        # Host /sys binding
  --bind=/dev/urandom:/dev/random    # Entropy node compatibility
  --link2symlink                     # Hardlink emulation
  /bin/sh                            # Target guest shell/command
```
