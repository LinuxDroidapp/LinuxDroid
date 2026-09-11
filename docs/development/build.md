# LinuxDroid — Build & Toolchain Guide

## 1. Prerequisites
- **Android SDK:** Platform API 37 (`minSdk = 36`, `compileSdk = 37`, `targetSdk = 37`)
- **Android NDK:** Version `30.0.16248370` (NDK r30 stable, Clang 21)
- **CMake:** Version `3.22.1+` (or `4.4.3` via SDK)
- **JDK:** OpenJDK 21 (Temurin / SDKMAN)
- **Gradle:** 9.7.1 (via Gradle Wrapper `./gradlew`)
- **Host Tools:** Meson, Ninja, Python 3, Bison, Flex, `aarch64-linux-gnu-gcc`

## 2. Native Runtime Build Pipeline
LinuxDroid bundles its runtime components and native engines directly in the application build. All binaries and libraries are compiled with `-Wl,-z,max-page-size=16384` for strict 16 KB ELF page alignment.

The build pipeline consists of:
1. **PRoot & Loader**:
   ```bash
   ./scripts/build-proot.sh
   ```
   Builds standalone `proot`, `loader`, `libproot.so`, and `libproot_loader.so` using NDK r29. Generates `MANIFEST.txt` and stages artifacts.

2. **Native Wayland & libweston-17 Stack**:
   ```bash
   ./native/weston/build_wayland_stack.sh
   ```
   Cross-compiles libdrm, libffi, pixman (NEON accelerated), wayland, wayland-protocols, libxkbcommon, and libweston-17 for Android `arm64-v8a`. Stages libraries into `app/src/main/jniLibs/arm64-v8a/`.

3. **Guest Debian Packages (LDDM & LDDE)**:
   ```bash
   ./scripts/build-packages.sh
   ```
   Cross-compiles LDDM and LDDE Debian packages with 16 KB page alignment and stages them into `app/src/main/assets/packages/`. Generates `PACKAGES_MANIFEST.txt`.

4. **Master Build Orchestrator**:
   ```bash
   ./scripts/build-runtime.sh
   ```
   Sequentially executes all component builds, stages them to `build/linuxdroid/`, copies them to the Android project, and runs validation.

5. **Artifact Validation**:
   ```bash
   ./scripts/validate-artifacts.sh
   ```
   Verifies 16 KB page alignment (`p_align >= 0x4000`, `vaddr % align == offset % align`), 64-bit arm64-v8a architecture, DT_NEEDED clean dependencies, and SHA256 integrity manifests across all 24 production targets.

## 3. Application Build Commands
Set environment paths and assemble the APK:

```bash
# Set Android SDK and NDK environment variables
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_ROOT=$ANDROID_HOME/ndk/30.0.16248370

# Validate native artifacts
./scripts/validate-artifacts.sh

# Run unit tests across all modules
./gradlew testDebugUnitTest --no-daemon

# Compile and package Debug APK
./gradlew :app:assembleDebug --no-daemon

# Compile and package Release APK
./gradlew :app:assembleRelease --no-daemon
```

### Build Outputs
- **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **Release APK**: `app/build/outputs/apk/release/app-release.apk`
- **Staged Runtime Artifacts**: `build/linuxdroid/`
- **Packaged Native Libraries**: `app/src/main/jniLibs/arm64-v8a/`
- **Packaged PRoot Binaries**: `app/src/main/assets/proot/arm64-v8a/`
- **Packaged Debian Packages**: `app/src/main/assets/packages/`

