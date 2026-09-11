#include "test_framework.hpp"
#include "lddm/session/session_environment.hpp"
#include "lddm/session/session_config.hpp"
#include "lddm/session/session_paths.hpp"

TEST_CASE(SessionEnvironment_DefaultsAndPopulation) {
    lddm::SessionEnvironment env;
    EXPECT_TRUE(env.has("PATH"));
    EXPECT_TRUE(env.has("TERM"));

    lddm::SessionId id("sess-env-test");
    std::filesystem::path base_dir = "/tmp/lddm_unit_tests";
    lddm::SessionPaths paths(id, base_dir, "wayland-2");

    lddm::SessionConfig config{
        .id = id,
        .type = lddm::SessionType::Wayland,
        .user = "tester",
        .uid = 1001,
        .gid = 1001,
        .wayland_display = "wayland-2"
    };

    env.populate_session_defaults(config, paths);

    EXPECT_EQ(env.get("USER").value_or(""), "tester");
    EXPECT_EQ(env.get("LOGNAME").value_or(""), "tester");
    EXPECT_EQ(env.get("WAYLAND_DISPLAY").value_or(""), "wayland-2");
    EXPECT_EQ(env.get("XDG_SESSION_TYPE").value_or(""), "wayland");
    EXPECT_EQ(env.get("XDG_CURRENT_DESKTOP").value_or(""), "LDDE");
    EXPECT_EQ(env.get("XDG_RUNTIME_DIR").value_or(""), paths.runtime_dir().string());
}

TEST_CASE(SessionEnvironment_OverridesAndRedaction) {
    lddm::SessionEnvironment env;
    env.set("NORMAL_VAR", "visible_value");
    env.set("APP_SECRET_TOKEN", "super_secret_123");
    env.set("USER_PASSWORD", "mypassword");

    EXPECT_TRUE(lddm::SessionEnvironment::is_sensitive_key("APP_SECRET_TOKEN"));
    EXPECT_TRUE(lddm::SessionEnvironment::is_sensitive_key("USER_PASSWORD"));
    EXPECT_FALSE(lddm::SessionEnvironment::is_sensitive_key("NORMAL_VAR"));

    auto redacted = env.redacted_summary();
    EXPECT_EQ(redacted["NORMAL_VAR"], "visible_value");
    EXPECT_EQ(redacted["APP_SECRET_TOKEN"], "[REDACTED]");
    EXPECT_EQ(redacted["USER_PASSWORD"], "[REDACTED]");
}

TEST_CASE(SessionEnvironment_VectorExport) {
    lddm::SessionEnvironment env;
    env.set("FOO", "BAR");
    env.set("BAZ", "QUX");

    auto vec = env.to_vector();
    bool found_foo = false;
    for (const auto& entry : vec) {
        if (entry == "FOO=BAR") found_foo = true;
    }
    EXPECT_TRUE(found_foo);
}

TEST_MAIN()

