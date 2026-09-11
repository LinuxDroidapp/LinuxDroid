#include "test_framework.hpp"
#include "lddm/weston/weston_config.hpp"
#include <filesystem>
#include <fstream>

using namespace lddm::weston;

TEST_CASE(WestonConfig_DefaultAndValidation) {
    auto config = WestonConfig::create_default();
    EXPECT_EQ(config.socket_name, "wayland-0");
    EXPECT_EQ(config.backend, "headless-backend.so");
    EXPECT_TRUE(config.startup_timeout_ms > 0);
    EXPECT_TRUE(config.validate().has_value());

    // Invalid configs
    config.socket_name = "";
    EXPECT_FALSE(config.validate().has_value());

    config.socket_name = "wayland-0";
    config.backend = "";
    EXPECT_FALSE(config.validate().has_value());

    config.backend = "headless";
    config.startup_timeout_ms = 0;
    EXPECT_FALSE(config.validate().has_value());
}

TEST_CASE(WestonConfigWriter_IniGeneration) {
    WestonConfig config;
    config.idle_time_seconds = 30;
    config.require_input = true;
    config.modules = {"systemd-notify.so"};

    auto ini = WestonConfigWriter::generate_ini_content(config);
    EXPECT_TRUE(ini.find("[core]") != std::string::npos);
    EXPECT_TRUE(ini.find("idle-time=30") != std::string::npos);
    EXPECT_TRUE(ini.find("require-input=true") != std::string::npos);
    EXPECT_TRUE(ini.find("modules=systemd-notify.so") != std::string::npos);
    EXPECT_TRUE(ini.find("[shell]") != std::string::npos);

    // Test writing to disk
    std::string test_path = "/tmp/lddm_test_weston_writer.ini";
    auto write_res = WestonConfigWriter::write_config_file(test_path, config);
    EXPECT_TRUE(write_res.has_value());
    EXPECT_TRUE(std::filesystem::exists(test_path));

    std::filesystem::remove(test_path);
}

TEST_CASE(WestonExecutableResolver_Resolve) {
    // 1. Resolve explicitly installed weston
    auto res = WestonExecutableResolver::resolve("/usr/bin/weston");
    EXPECT_TRUE(res.has_value());
    EXPECT_EQ(res.value(), "/usr/bin/weston");

    // 2. Non-existent path returns error
    auto invalid_res = WestonExecutableResolver::resolve("/nonexistent/bin/weston");
    EXPECT_FALSE(invalid_res.has_value());

    // 3. Resolve via auto-search
    auto auto_res = WestonExecutableResolver::resolve("");
    EXPECT_TRUE(auto_res.has_value());
    EXPECT_TRUE(std::filesystem::exists(auto_res.value()));
}

TEST_MAIN()
