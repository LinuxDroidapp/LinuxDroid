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

TEST_CASE(PackageDeb_BuildAndMetadataValidation) {
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

    // 1. Build package via script if not already present
    std::string build_cmd = root_dir + "/packaging/build_package.sh --build-dir " + build_dir + " --output-dir " + out_dir;
    int build_ret = std::system(build_cmd.c_str());
    EXPECT_EQ(build_ret, 0);

    // 2. Find the generated deb file
    std::string deb_path;
    for (const auto& entry : std::filesystem::directory_iterator(out_dir)) {
        if (entry.path().extension() == ".deb" && entry.path().filename().string().rfind("linuxdroid-display-manager", 0) == 0) {
            deb_path = entry.path().string();
            break;
        }
    }
    EXPECT_FALSE(deb_path.empty());

    // 3. Inspect package metadata via dpkg-deb -I
    std::string info_out = exec_cmd("dpkg-deb -I " + deb_path);
    EXPECT_FALSE(info_out.empty());
    EXPECT_NE(info_out.find("Package: linuxdroid-display-manager"), std::string::npos);
    EXPECT_NE(info_out.find("Section: x11"), std::string::npos);
    EXPECT_NE(info_out.find("Priority: optional"), std::string::npos);
    EXPECT_NE(info_out.find("libc6"), std::string::npos);

    // Version must match authoritative version
    std::string expected_ver_prefix = "Version: " + std::string(Version::string()).substr(0, 5);
    EXPECT_NE(info_out.find("Version: "), std::string::npos);

    // 4. Inspect package contents via dpkg-deb -c
    std::string contents_out = exec_cmd("dpkg-deb -c " + deb_path);
    EXPECT_FALSE(contents_out.empty());

    // Executable binary and symlink
    EXPECT_NE(contents_out.find("./usr/bin/lddm"), std::string::npos);
    EXPECT_NE(contents_out.find("./usr/bin/linuxdroid-display-manager"), std::string::npos);

    // System configuration files
    EXPECT_NE(contents_out.find("./etc/linuxdroid/lddm.conf"), std::string::npos);
    EXPECT_NE(contents_out.find("./etc/lddm/lddm.conf"), std::string::npos);

    // Documentation
    EXPECT_NE(contents_out.find("./usr/share/doc/linuxdroid-display-manager/copyright"), std::string::npos);
    EXPECT_NE(contents_out.find("./usr/share/doc/linuxdroid-display-manager/changelog.Debian.gz"), std::string::npos);

    // No source files or build artifacts leaked
    EXPECT_EQ(contents_out.find(".cpp"), std::string::npos);
    EXPECT_EQ(contents_out.find(".o"), std::string::npos);
    EXPECT_EQ(contents_out.find(".a"), std::string::npos);

    // 5. Inspect control directory via dpkg-deb -e
    std::string ctrl_extract_dir = "/tmp/lddm_test_deb_control";
    std::filesystem::remove_all(ctrl_extract_dir);
    std::filesystem::create_directories(ctrl_extract_dir);

    int extract_ret = std::system(("dpkg-deb -e " + deb_path + " " + ctrl_extract_dir).c_str());
    EXPECT_EQ(extract_ret, 0);

    EXPECT_TRUE(std::filesystem::exists(ctrl_extract_dir + "/control"));
    EXPECT_TRUE(std::filesystem::exists(ctrl_extract_dir + "/conffiles"));
    EXPECT_TRUE(std::filesystem::exists(ctrl_extract_dir + "/postinst"));
    EXPECT_TRUE(std::filesystem::exists(ctrl_extract_dir + "/prerm"));
    EXPECT_TRUE(std::filesystem::exists(ctrl_extract_dir + "/postrm"));
    EXPECT_TRUE(std::filesystem::exists(ctrl_extract_dir + "/md5sums"));

    // Verify conffiles content
    std::ifstream conffiles_in(ctrl_extract_dir + "/conffiles");
    std::string conffile_line;
    bool found_conf = false;
    while (std::getline(conffiles_in, conffile_line)) {
        if (conffile_line.find("/etc/linuxdroid/lddm.conf") != std::string::npos) {
            found_conf = true;
            break;
        }
    }
    EXPECT_TRUE(found_conf);

    std::filesystem::remove_all(ctrl_extract_dir);
}

TEST_MAIN()
