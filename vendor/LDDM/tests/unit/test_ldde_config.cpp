#include "test_framework.hpp"
#include "lddm/ldde/ldde_config.hpp"

using namespace lddm::ldde;

TEST_CASE(LddeConfig_Defaults) {
    auto config = LddeConfig::create_default();
    EXPECT_EQ(config.executable, "/usr/bin/ldde-session");
    EXPECT_EQ(config.session_target, "default");
    EXPECT_TRUE(config.autostart);
    EXPECT_EQ(config.startup_timeout_ms, 10000);
    EXPECT_EQ(config.stop_timeout_ms, 5000);
    EXPECT_EQ(config.readiness_timeout_ms, 10000);
    EXPECT_EQ(config.readiness_mode, LddeReadinessMode::File);
    EXPECT_EQ(config.readiness_file_name, "ldde-session.ready");
    EXPECT_EQ(config.contract_version, 1);
    EXPECT_TRUE(config.validate().has_value());
}

TEST_CASE(LddeConfig_Validation) {
    LddeConfig config;

    config.executable = "";
    EXPECT_FALSE(config.validate().has_value());

    config = LddeConfig::create_default();
    config.startup_timeout_ms = 0;
    EXPECT_FALSE(config.validate().has_value());

    config = LddeConfig::create_default();
    config.stop_timeout_ms = 0;
    EXPECT_FALSE(config.validate().has_value());

    config = LddeConfig::create_default();
    config.readiness_timeout_ms = 0;
    EXPECT_FALSE(config.validate().has_value());

    config = LddeConfig::create_default();
    config.readiness_mode = LddeReadinessMode::File;
    config.readiness_file_name = "";
    EXPECT_FALSE(config.validate().has_value());

    config = LddeConfig::create_default();
    config.readiness_mode = LddeReadinessMode::Socket;
    config.readiness_socket_name = "";
    EXPECT_FALSE(config.validate().has_value());
}

TEST_CASE(LddeConfig_ExecutableResolver) {
    // Valid standard binary
    auto res_true = LddeExecutableResolver::resolve("/bin/true");
    EXPECT_TRUE(res_true.has_value());
    EXPECT_EQ(res_true.value(), "/bin/true");

    // Missing binary
    auto res_missing = LddeExecutableResolver::resolve("/nonexistent/path/to/ldde");
    EXPECT_FALSE(res_missing.has_value());
    EXPECT_EQ(res_missing.error().code(), lddm::ErrorCode::LddeExecutableNotFound);
}

TEST_MAIN()
