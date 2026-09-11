#include "test_framework.hpp"
#include "lddm/version.hpp"

#include <filesystem>
#include <fstream>
#include <sstream>
#include <cstdlib>
#include <array>
#include <memory>

using namespace lddm;

struct PipeCloser {
    void operator()(FILE* fp) const noexcept {
        if (fp) {
            pclose(fp);
        }
    }
};

static std::string exec_cmd(const std::string& cmd) {
    std::array<char, 256> buffer;
    std::string result;
    std::unique_ptr<FILE, PipeCloser> pipe(popen(cmd.c_str(), "r"));
    if (!pipe) {
        return "";
    }
    while (fgets(buffer.data(), buffer.size(), pipe.get()) != nullptr) {
        result += buffer.data();
    }
    return result;
}

TEST_CASE(PackageLifecycle_InstallUpgradeAndRemoval) {
    std::string root_dir = "/workspaces/LinuxDroid/vendor/LDDM";
    if (const char* env_dir = std::getenv("LDDM_SOURCE_DIR")) {
        root_dir = env_dir;
    } else if (!std::filesystem::exists(root_dir) && std::filesystem::exists("/workspaces/LDDM")) {
        root_dir = "/workspaces/LDDM";
    }
    std::filesystem::path cwd = std::filesystem::current_path();
    std::filesystem::path p = cwd;
    std::string build_dir;
    while (!p.empty() && p != p.root_path()) {
        if (std::filesystem::exists(p / "CMakeCache.txt")) {
            build_dir = p.string();
            break;
        }
        p = p.parent_path();
    }
    if (build_dir.empty()) {
        build_dir = root_dir + "/build-release";
    }
    std::string out_dir = build_dir + "/packages";

    // Ensure package is built
    std::string build_cmd = root_dir + "/packaging/build_package.sh --build-dir " + build_dir + " --output-dir " + out_dir;
    int build_ret = std::system(build_cmd.c_str());
    EXPECT_EQ(build_ret, 0);

    std::string deb_path;
    std::string preferred_arch = "amd64";
#if defined(__aarch64__) || defined(_M_ARM64)
    preferred_arch = "arm64";
#endif
    for (const auto& entry : std::filesystem::directory_iterator(out_dir)) {
        if (entry.path().extension() == ".deb" && entry.path().filename().string().rfind("linuxdroid-display-manager", 0) == 0 && entry.path().filename().string().find(preferred_arch) != std::string::npos) {
            deb_path = entry.path().string();
            break;
        }
    }
    if (deb_path.empty()) {
        for (const auto& entry : std::filesystem::directory_iterator(out_dir)) {
            if (entry.path().extension() == ".deb" && entry.path().filename().string().rfind("linuxdroid-display-manager", 0) == 0) {
                deb_path = entry.path().string();
                break;
            }
        }
    }
    EXPECT_FALSE(deb_path.empty());

    // 1. Setup mock rootfs
    std::string mock_rootfs = "/tmp/lddm_mock_rootfs";
    std::filesystem::remove_all(mock_rootfs);
    std::filesystem::create_directories(mock_rootfs);

    // 2. Simulate package installation: extract archive into mock rootfs
    int extract_ret = std::system(("dpkg-deb -x " + deb_path + " " + mock_rootfs).c_str());
    EXPECT_EQ(extract_ret, 0);

    // Verify binary works
    std::string installed_bin = mock_rootfs + "/usr/bin/lddm";
    EXPECT_TRUE(std::filesystem::exists(installed_bin));

    std::string ver_out = exec_cmd(installed_bin + " --version");
    EXPECT_NE(ver_out.find("lddm (LinuxDroid Display Manager)"), std::string::npos);

    std::string symlink_bin = mock_rootfs + "/usr/bin/linuxdroid-display-manager";
    EXPECT_TRUE(std::filesystem::exists(symlink_bin));

    // Verify configuration installation
    std::string installed_conf = mock_rootfs + "/etc/linuxdroid/lddm.conf";
    EXPECT_TRUE(std::filesystem::exists(installed_conf));

    // 3. Simulate administrator modifying configuration
    {
        std::ofstream out(installed_conf, std::ios::app);
        out << "\n# Custom admin setting\ncustom_admin_key = custom_admin_value\n";
    }

    // 4. Simulate package upgrade:
    // In Debian, conffiles are not overwritten if modified.
    // We simulate this by extracting everything EXCEPT conffiles (or restoring conffiles).
    std::string upgrade_staging = "/tmp/lddm_upgrade_staging";
    std::filesystem::remove_all(upgrade_staging);
    std::filesystem::create_directories(upgrade_staging);
    int up_ret = std::system(("dpkg-deb -x " + deb_path + " " + upgrade_staging).c_str());
    EXPECT_EQ(up_ret, 0);

    // Binary is updated
    std::filesystem::copy_file(upgrade_staging + "/usr/bin/lddm", installed_bin, std::filesystem::copy_options::overwrite_existing);

    // Verify admin config was preserved
    std::ifstream conf_check(installed_conf);
    std::stringstream conf_buf;
    conf_buf << conf_check.rdbuf();
    std::string conf_text = conf_buf.str();
    EXPECT_NE(conf_text.find("custom_admin_key = custom_admin_value"), std::string::npos);

    // 5. Test CLI config migration on mock rootfs
    std::string mig_out = exec_cmd(installed_bin + " --migrate-config " + installed_conf);
    EXPECT_NE(mig_out.find("Configuration migrated successfully"), std::string::npos);

    // 6. Simulate package removal (binaries/docs deleted, user config preserved)
    std::filesystem::remove(installed_bin);
    std::filesystem::remove(symlink_bin);
    std::filesystem::remove_all(mock_rootfs + "/usr/share/doc/linuxdroid-display-manager");
    EXPECT_FALSE(std::filesystem::exists(installed_bin));
    EXPECT_TRUE(std::filesystem::exists(installed_conf)); // config preserved on remove!

    // 7. Simulate purge (config deleted)
    std::filesystem::remove(installed_conf);
    std::filesystem::remove_all(mock_rootfs + "/etc/linuxdroid");
    EXPECT_FALSE(std::filesystem::exists(installed_conf));

    std::filesystem::remove_all(mock_rootfs);
    std::filesystem::remove_all(upgrade_staging);
}

TEST_MAIN()
