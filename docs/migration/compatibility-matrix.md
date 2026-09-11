# LinuxDroid Compatibility Matrix & ABI Audit

## 1. Android Platform & Toolchain Matrix

| Parameter | Specification | Verification / Enforcement |
| :--- | :--- | :--- |
| **Minimum SDK** | `36` (Android 16+) | `libs.versions.minSdk = "36"`; enforced in all module Gradle files. |
| **Target SDK** | `37` | `libs.versions.targetSdk = "37"`; ensures compliance with latest platform behavior. |
| **Compile SDK** | `37` | `libs.versions.compileSdk = "37"`. |
| **Primary ABI** | `arm64-v8a` | Exclusive 64-bit ARM64 target. `abiFilters += listOf("arm64-v8a")`. |
| **Production NDK** | `29.0.14206865` (r29 stable) | Pinned in `libs.versions.ndk`. Stable compiler, standard libc++ toolchain. |
| **CMake Version** | `4.4.3` | Pinned in `libs.versions.cmake`. |
| **AGP Version** | `9.3.2` | Gradle Android Plugin. |
| **Java / Kotlin** | Java 21 / Kotlin 2.3.20 | JVM target 17. |

---

## 2. 16 KB Page-Size Native Compatibility Audit

Android 15/16 introduces 16 KB page-size support on modern ARM64 devices. To ensure flawless execution without crashes or ELF loader rejections:

### 2.1 Packaged ELF Segment Alignment
All prebuilt shared objects and binaries in `app/src/main/jniLibs/arm64-v8a/` and `app/src/main/assets/proot/arm64-v8a/` have been audited using `readelf -l`:

| Binary / Library | ELF Class | Architecture | PT_LOAD Alignment | 16 KB Compliant |
| :--- | :--- | :--- | :--- | :--- |
| `proot` | ELF 64-bit | aarch64 | `0x4000` (16 KB) | **YES** |
| `loader` | ELF 64-bit | aarch64 | `0x4000` (16 KB) | **YES** |
| `libweston-17.so` | ELF 64-bit | aarch64 | `0x4000` (16 KB) | **YES** |
| `libgl-renderer.so` | ELF 64-bit | aarch64 | `0x4000` (16 KB) | **YES** |
| `libwayland-server.so` | ELF 64-bit | aarch64 | `0x4000` (16 KB) | **YES** |
| `libwayland-client.so` | ELF 64-bit | aarch64 | `0x4000` (16 KB) | **YES** |
| `libwayland-cursor.so` | ELF 64-bit | aarch64 | `0x4000` (16 KB) | **YES** |
| `libpixman-1.so` | ELF 64-bit | aarch64 | `0x4000` (16 KB) | **YES** |
| `libxkbcommon.so` | ELF 64-bit | aarch64 | `0x4000` (16 KB) | **YES** |
| `libdrm.so` | ELF 64-bit | aarch64 | `0x4000` (16 KB) | **YES** |
| `libffi.so` | ELF 64-bit | aarch64 | `0x4000` (16 KB) | **YES** |
| `libproot.so` | ELF 64-bit | aarch64 | `0x4000` (16 KB) | **YES** |
| `libproot_loader.so` | ELF 64-bit | aarch64 | `0x4000` (16 KB) | **YES** |
| `liblinuxdroid_bootstrap.so` | ELF 64-bit | aarch64 | `0x4000` (16 KB) | **YES** |

### 2.2 Native Code Page-Size Runtime Guarantees
- No code relies on compile-time `PAGE_SIZE` macros.
- Dynamic page size resolution is queried via `sysconf(_SC_PAGESIZE)` or `getpagesize()`.
- Linker flags enforce `-Wl,-z,max-page-size=16384` on all compiled native libraries.

---

## 3. libweston-17 Private ABI Contract Audit

`linuxdroid_backend.c` interacts with libweston's GL renderer by constructing renderbuffers that mirror the internal layout of `struct gl_renderbuffer`.

### 3.1 Upstream Dependency Lock
- **Repository**: `https://github.com/LinuxDroidapp/weston.git`
- **Locked Commit**: `9669073fe8f411ef3e9f40a36d0ec9aa68362fa2`
- **Libweston Major**: `17`
- **Archived Prebuilt**: `libweston-17.so` in `app/src/main/jniLibs/arm64-v8a/`

### 3.2 Structure Layout & Alignment
The structure `struct linuxdroid_gl_renderbuffer` matches libweston-17's internal `struct gl_renderbuffer`:

```c
struct linuxdroid_gl_renderbuffer {
    struct weston_output *output;                      /* offset 0,  size 8  */
    int type;                                          /* offset 8,  size 4  */
    pixman_region32_t damage;                          /* offset 16, size 32 */
    int border_status;                                 /* offset 48, size 4  */
    bool stale;                                        /* offset 52, size 1  */
    GLuint fb;                                         /* offset 56, size 4  */
    struct {
        int age;                                       /* offset 60, size 4  */
    } window;
    weston_renderbuffer_discarded_func discarded_cb;   /* offset 64, size 8  */
    void *user_data;                                   /* offset 72, size 8  */
    struct wl_list link;                               /* offset 80, size 16 */
};
```

### 3.3 Compile-Time Assertions
To prevent silent breakage upon toolchain upgrades or header changes, `linuxdroid_backend.c` includes static assertions:
- `static_assert(sizeof(struct linuxdroid_gl_renderbuffer) >= 96, "linuxdroid_gl_renderbuffer size mismatch");`
- `static_assert(offsetof(struct linuxdroid_gl_renderbuffer, output) == 0, "output offset invalid");`
- `static_assert(offsetof(struct linuxdroid_gl_renderbuffer, fb) == 56, "fb offset invalid");`
- `static_assert(offsetof(struct linuxdroid_gl_renderbuffer, link) == 80, "link offset invalid");`

