#include "test_framework.hpp"
#include "lddm/config/config.hpp"
#include "lddm/config/config_parser.hpp"
#include "lddm/config/config_validator.hpp"

TEST_CASE(Config_Defaults) {
    auto cfg = lddm::LddmConfig::create_default();
    EXPECT_EQ(cfg.logging.level, lddm::LogLevel::INFO);
    EXPECT_TRUE(cfg.logging.console_output);
    EXPECT_EQ(cfg.server.runtime_dir, "/run/lddm");
    EXPECT_EQ(cfg.session.default_user, "root");
    EXPECT_EQ(cfg.weston.executable, "/usr/bin/weston");
    EXPECT_EQ(cfg.process.startup_timeout_ms, 10000u);

    auto val_res = lddm::ConfigValidator::validate(cfg);
    EXPECT_TRUE(val_res.has_value());
}

TEST_CASE(Config_ParserCustomString) {
    const std::string in_ini = R"(
# LDDM Test Configuration
[logging]
level = DEBUG
file_path = "/tmp/test-lddm.log"
console = false

[server]
socket_path = "/run/test.sock"
runtime_dir = "/run/test"

[session]
default_user = "droid"
session_type = "wayland"
wayland_display = "wayland-1"

[weston]
executable = "/usr/local/bin/weston"
socket_name = "wayland-1"
additional_args = "--backend=headless-backend.so, --log=/tmp/weston.log"

[process]
startup_timeout_ms = 15000
stop_timeout_ms = 8000
max_restart_count = 5

[environment]
CUSTOM_VAR = "hello_world"
)";

    lddm::ConfigParser parser;
    auto res = parser.parse_string(in_ini);
    EXPECT_TRUE(res.has_value());

    const auto& cfg = res.value();
    EXPECT_EQ(cfg.logging.level, lddm::LogLevel::DEBUG);
    EXPECT_EQ(cfg.logging.file_path, "/tmp/test-lddm.log");
    EXPECT_FALSE(cfg.logging.console_output);
    EXPECT_EQ(cfg.server.socket_path, "/run/test.sock");
    EXPECT_EQ(cfg.server.runtime_dir, "/run/test");
    EXPECT_EQ(cfg.session.default_user, "droid");
    EXPECT_EQ(cfg.session.wayland_display, "wayland-1");
    EXPECT_EQ(cfg.weston.executable, "/usr/local/bin/weston");
    EXPECT_EQ(cfg.weston.socket_name, "wayland-1");
    EXPECT_EQ(cfg.weston.additional_args.size(), 2u);
    EXPECT_EQ(cfg.process.startup_timeout_ms, 15000u);
    EXPECT_EQ(cfg.process.stop_timeout_ms, 8000u);
    EXPECT_EQ(cfg.process.max_restart_count, 5u);

    auto it = cfg.environment.variables.find("CUSTOM_VAR");
    EXPECT_TRUE(it != cfg.environment.variables.end());
    if (it != cfg.environment.variables.end()) {
        EXPECT_EQ(it->second, "hello_world");
    }

    auto val_res = lddm::ConfigValidator::validate(cfg);
    EXPECT_TRUE(val_res.has_value());
}

TEST_CASE(Config_ValidationFailures) {
    auto cfg = lddm::LddmConfig::create_default();

    cfg.server.socket_path = "";
    auto res1 = lddm::ConfigValidator::validate(cfg);
    EXPECT_FALSE(res1.has_value());
    EXPECT_EQ(res1.error().code(), lddm::ErrorCode::ConfigValidationFailed);

    cfg = lddm::LddmConfig::create_default();
    cfg.process.startup_timeout_ms = 0;
    auto res2 = lddm::ConfigValidator::validate(cfg);
    EXPECT_FALSE(res2.has_value());
    EXPECT_EQ(res2.error().code(), lddm::ErrorCode::ConfigValidationFailed);

    cfg = lddm::LddmConfig::create_default();
    cfg.session.default_user = "";
    auto res3 = lddm::ConfigValidator::validate(cfg);
    EXPECT_FALSE(res3.has_value());
}

TEST_MAIN()

