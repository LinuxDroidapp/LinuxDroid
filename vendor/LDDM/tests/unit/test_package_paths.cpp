#include "test_framework.hpp"
#include "lddm/config/config.hpp"
#include "lddm/platform/paths.hpp"

#include <filesystem>
#include <fstream>

using namespace lddm;

TEST_CASE(PackagePaths_DefaultSystemConfigPath) {
    auto sys_path = ConfigManager::default_system_config_path();
    EXPECT_FALSE(sys_path.empty());
    // Primary path must be under /etc/linuxdroid/ or fallback /etc/lddm/
    bool valid_prefix = (sys_path == "/etc/linuxdroid/lddm.conf" || sys_path == "/etc/lddm/lddm.conf");
    EXPECT_TRUE(valid_prefix);
}

TEST_CASE(PackagePaths_UserConfigPath) {
    auto user_path = ConfigManager::default_user_config_path();
    if (!user_path.empty()) {
        EXPECT_NE(user_path.find("lddm.conf"), std::string::npos);
    }
}

TEST_CASE(PackagePaths_OverridePrecedence) {
    std::string test_dir = "/tmp/lddm_test_package_paths";
    std::filesystem::remove_all(test_dir);
    std::filesystem::create_directories(test_dir);

    std::string custom_cfg = test_dir + "/custom.conf";
    {
        std::ofstream out(custom_cfg);
        out << "[session]\ndefault_user = custom_user\n";
    }

    ConfigManager mgr;
    auto load_res = mgr.load_standard(custom_cfg);
    EXPECT_TRUE(load_res.has_value());
    EXPECT_EQ(mgr.config().session.default_user, "custom_user");

    std::filesystem::remove_all(test_dir);
}

TEST_MAIN()

