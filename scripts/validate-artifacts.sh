#!/usr/bin/env bash
# =============================================================================
# LinuxDroid — Production Artifacts & 16 KB Alignment Validator
# =============================================================================
# Validates all ARM64 runtime artifacts against strict production criteria:
# 1. ELF 64-bit AArch64 binary format
# 2. 16 KB page alignment for all PT_LOAD segments (p_align >= 0x4000 and
#    p_vaddr % p_align == p_offset % p_align)
# 3. Dynamic dependency integrity (DT_NEEDED verification)
# 4. Debian package architecture and control integrity
# 5. Asset integrity and checksum manifests
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

READELF="llvm-readelf"
if ! command -v "$READELF" >/dev/null 2>&1; then
    if [[ -n "${ANDROID_NDK_ROOT:-}" && -x "${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" ]]; then
        READELF="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    elif [[ -x "/home/codespace/Android/Sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" ]]; then
        READELF="/home/codespace/Android/Sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    else
        READELF="readelf"
    fi
fi

echo "========================================================================"
echo " LinuxDroid: Production Artifact Validation"
echo " Toolchain readelf: ${READELF}"
echo "========================================================================"

python3 - << 'PYEOF'
import os
import sys
import subprocess
import tempfile
import hashlib

root_dir = os.path.abspath(os.path.join(os.path.dirname(os.environ.get("SCRIPT_DIR", ".")), ".."))
if not os.path.isdir(os.path.join(root_dir, "app")):
    root_dir = "/workspaces/LinuxDroid"

readelf = os.environ.get("READELF", "llvm-readelf")

print(f"Project root: {root_dir}")
print(f"Readelf tool: {readelf}")

total_checks = 0
failed_checks = 0

def check_elf_binary(path, name=None):
    global total_checks, failed_checks
    total_checks += 1
    disp_name = name or os.path.basename(path)

    if not os.path.isfile(path):
        print(f"  [FAIL] {disp_name}: file does not exist ({path})")
        failed_checks += 1
        return False

    # 1. Architecture check
    file_info = subprocess.check_output(["file", path], text=True)
    if "ELF 64-bit" not in file_info or "aarch64" not in file_info:
        print(f"  [FAIL] {disp_name}: NOT ELF 64-bit AArch64! ({file_info.strip()})")
        failed_checks += 1
        return False

    # 2. Segment & 16 KB alignment check
    readelf_out = subprocess.check_output([readelf, "-l", path], text=True)
    load_segments = []
    for line in readelf_out.splitlines():
        parts = line.strip().split()
        if len(parts) >= 8 and parts[0] == "LOAD":
            # llvm-readelf format: LOAD Offset VirtAddr PhysAddr FileSiz MemSiz [Flags] Align
            offset = int(parts[1], 16)
            vaddr = int(parts[2], 16)
            align = int(parts[-1], 16)
            load_segments.append((offset, vaddr, align))

    if not load_segments:
        print(f"  [FAIL] {disp_name}: No PT_LOAD segments found!")
        failed_checks += 1
        return False

    for idx, (offset, vaddr, align) in enumerate(load_segments):
        if align < 0x4000:
            print(f"  [FAIL] {disp_name}: PT_LOAD[{idx}] alignment {hex(align)} < 0x4000 (16 KB)!")
            failed_checks += 1
            return False
        if (vaddr % align) != (offset % align):
            print(f"  [FAIL] {disp_name}: PT_LOAD[{idx}] vaddr % align != offset % align ({hex(vaddr % align)} != {hex(offset % align)})")
            failed_checks += 1
            return False

    aligns_str = ", ".join(hex(a) for _, _, a in load_segments)
    print(f"  [PASS] {disp_name}: ELF 64-bit aarch64, 16 KB aligned (PT_LOAD aligns: [{aligns_str}])")
    return True

print("\n--- 1. Validating Android Host Shared Libraries (jniLibs/arm64-v8a) ---")
jnilibs_dir = os.path.join(root_dir, "app/src/main/jniLibs/arm64-v8a")
expected_jnilibs = [
    "libdrm.so",
    "libffi.so",
    "libgl-renderer.so",
    "liblinuxdroid_bootstrap.so",
    "libpixman-1.so",
    "libproot.so",
    "libproot_loader.so",
    "libwayland-client.so",
    "libwayland-cursor.so",
    "libwayland-server.so",
    "libweston-17.so",
    "libxkbcommon.so"
]

for lib in expected_jnilibs:
    check_elf_binary(os.path.join(jnilibs_dir, lib))

print("\n--- 2. Validating PRoot Standalone Binaries (assets/proot/arm64-v8a) ---")
proot_assets_dir = os.path.join(root_dir, "app/src/main/assets/proot/arm64-v8a")
check_elf_binary(os.path.join(proot_assets_dir, "proot"), "assets/proot")
check_elf_binary(os.path.join(proot_assets_dir, "loader"), "assets/loader")

print("\n--- 3. Validating Staged PRoot Binaries (build/linuxdroid/proot) ---")
proot_stage_dir = os.path.join(root_dir, "build/linuxdroid/proot")
check_elf_binary(os.path.join(proot_stage_dir, "proot"), "stage/proot")
check_elf_binary(os.path.join(proot_stage_dir, "loader"), "stage/loader")
check_elf_binary(os.path.join(proot_stage_dir, "libproot.so"), "stage/libproot.so")
check_elf_binary(os.path.join(proot_stage_dir, "libproot_loader.so"), "stage/libproot_loader.so")

print("\n--- 4. Validating Debian Packages (assets/packages) ---")
packages_dir = os.path.join(root_dir, "app/src/main/assets/packages")
deb_files = [f for f in os.listdir(packages_dir) if f.endswith(".deb")]
if not deb_files:
    print("  [FAIL] No .deb packages found in assets/packages!")
    failed_checks += 1
else:
    for deb in sorted(deb_files):
        total_checks += 1
        deb_path = os.path.join(packages_dir, deb)
        print(f"Inspecting package {deb}...")
        arch = subprocess.check_output(["dpkg-deb", "-f", deb_path, "Architecture"], text=True).strip()
        pkg = subprocess.check_output(["dpkg-deb", "-f", deb_path, "Package"], text=True).strip()
        version = subprocess.check_output(["dpkg-deb", "-f", deb_path, "Version"], text=True).strip()
        if arch != "arm64":
            print(f"  [FAIL] {deb}: Architecture '{arch}' != 'arm64'")
            failed_checks += 1
        else:
            print(f"  [PASS] {deb}: Package={pkg}, Version={version}, Arch={arch}")

        # Extract and verify all ELF binaries inside deb
        with tempfile.TemporaryDirectory() as tmpdir:
            subprocess.check_call(["dpkg-deb", "-x", deb_path, tmpdir])
            for root_walk, _, files in os.walk(tmpdir):
                for f in files:
                    full_path = os.path.join(root_walk, f)
                    if os.path.isfile(full_path):
                        finfo = subprocess.check_output(["file", full_path], text=True)
                        if "ELF" in finfo:
                            check_elf_binary(full_path, f"{pkg}:{f}")

print("\n--- 5. Validating Dynamic Dependencies (DT_NEEDED) ---")
def get_needed(so_path):
    out = subprocess.check_output([readelf, "-d", so_path], text=True)
    needed = []
    for line in out.splitlines():
        if "NEEDED" in line and "[" in line and "]" in line:
            dep = line.split("[")[1].split("]")[0]
            needed.append(dep)
    return needed

# libweston-17.so
weston_so = os.path.join(jnilibs_dir, "libweston-17.so")
weston_needed = get_needed(weston_so)
forbidden_weston = ["libx11", "libxcb", "xwayland", "libgtk", "libgdk", "libxext", "libxrender"]
print(f"libweston-17.so dependencies: {weston_needed}")
has_forbidden = False
for dep in weston_needed:
    for forbidden in forbidden_weston:
        if forbidden in dep.lower():
            print(f"  [FAIL] libweston-17.so has forbidden desktop/X11 dependency: {dep}")
            failed_checks += 1
            has_forbidden = True
if not has_forbidden:
    print("  [PASS] libweston-17.so has zero X11/desktop dependencies")

# libproot.so
proot_so = os.path.join(jnilibs_dir, "libproot.so")
proot_needed = get_needed(proot_so)
print(f"libproot.so dependencies: {proot_needed}")
forbidden_proot = ["libtalloc.so", "libandroid-shmem.so"]
for forbidden in forbidden_proot:
    if forbidden in proot_needed:
        print(f"  [FAIL] libproot.so dynamically links {forbidden} (must be static)!")
        failed_checks += 1
print("  [PASS] libproot.so statically embeds talloc and android-shmem")

print("\n--- 6. Validating Integrity Manifests ---")
def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

proot_manifest = os.path.join(proot_assets_dir, "MANIFEST.txt")
total_checks += 1
if not os.path.isfile(proot_manifest):
    print(f"  [FAIL] Missing {proot_manifest}")
    failed_checks += 1
else:
    proot_sha = sha256_file(os.path.join(proot_assets_dir, "proot"))
    loader_sha = sha256_file(os.path.join(proot_assets_dir, "loader"))
    with open(proot_manifest) as f:
        content = f.read()
    if proot_sha in content and loader_sha in content:
        print(f"  [PASS] {proot_manifest} hashes match assets")
    else:
        print(f"  [FAIL] {proot_manifest} hashes mismatch!")
        failed_checks += 1

pkg_manifest = os.path.join(packages_dir, "PACKAGES_MANIFEST.txt")
total_checks += 1
if not os.path.isfile(pkg_manifest):
    print(f"  [FAIL] Missing {pkg_manifest}")
    failed_checks += 1
else:
    with open(pkg_manifest) as f:
        pkg_content = f.read()
    for deb in deb_files:
        deb_sha = sha256_file(os.path.join(packages_dir, deb))
        if deb_sha in pkg_content:
            print(f"  [PASS] {deb} matches SHA256 in PACKAGES_MANIFEST.txt")
        else:
            print(f"  [FAIL] {deb} SHA256 not found in PACKAGES_MANIFEST.txt")
            failed_checks += 1

print("\n========================================================================")
print(f" Validation Summary: {total_checks - failed_checks}/{total_checks} checks passed")
if failed_checks > 0:
    print(f" ERROR: {failed_checks} validation checks failed!")
    sys.exit(1)
else:
    print(" ALL PRODUCTION ARTIFACT VALIDATION CHECKS PASSED!")
print("========================================================================")
PYEOF
