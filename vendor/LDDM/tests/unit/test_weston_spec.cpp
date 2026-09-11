#include "test_framework.hpp"
#include "lddm/weston/weston_spec.hpp"

using namespace lddm::weston;

TEST_CASE(WestonSpec_Validation) {
    WestonSpec spec;
    spec.executable = "";
    EXPECT_FALSE(spec.validate().has_value());

    spec.executable = "/usr/bin/weston";
    spec.wayland_display = "";
    EXPECT_FALSE(spec.validate().has_value());

    spec.wayland_display = "wayland-0";
    spec.working_directory = "/tmp";
    EXPECT_TRUE(spec.validate().has_value());

    spec.working_directory = "/nonexistent/directory/path/12345";
    EXPECT_FALSE(spec.validate().has_value());
}

TEST_CASE(WestonSpec_ArgumentBuilder) {
    WestonConfig config;
    config.backend = "headless";
    config.socket_name = "wayland-test-5";
    config.idle_time_seconds = 60;
    config.shell = "kiosk-shell.so";
    config.config_path = "/tmp/test.ini";
    config.additional_args = {"--width=800", "--height=600"};

    auto args = WestonSpec::build_arguments(config, "/tmp/weston.log");
    EXPECT_FALSE(args.empty());

    bool found_backend = false;
    bool found_socket = false;
    bool found_idle = false;
    bool found_shell = false;
    bool found_config = false;
    bool found_log = false;
    bool found_width = false;

    for (const auto& arg : args) {
        if (arg == "--backend=headless") found_backend = true;
        if (arg == "--socket=wayland-test-5") found_socket = true;
        if (arg == "--idle-time=60") found_idle = true;
        if (arg == "--shell=kiosk-shell.so") found_shell = true;
        if (arg == "--config=/tmp/test.ini") found_config = true;
        if (arg == "--log=/tmp/weston.log") found_log = true;
        if (arg == "--width=800") found_width = true;
    }

    EXPECT_TRUE(found_backend);
    EXPECT_TRUE(found_socket);
    EXPECT_TRUE(found_idle);
    EXPECT_TRUE(found_shell);
    EXPECT_TRUE(found_config);
    EXPECT_TRUE(found_log);
    EXPECT_TRUE(found_width);
}

TEST_CASE(WestonSpec_ToProcessSpec) {
    WestonSpec spec;
    spec.executable = "/usr/bin/weston";
    spec.arguments = {"weston", "--backend=headless"};
    spec.working_directory = "/tmp";
    spec.environment = {{"XDG_RUNTIME_DIR", "/tmp"}};
    spec.stdout_file = "/tmp/weston.log";
    spec.stderr_file = "/tmp/weston.log";

    auto proc_spec = spec.to_process_spec();
    EXPECT_EQ(proc_spec.name, "weston");
    EXPECT_EQ(proc_spec.executable, "/usr/bin/weston");
    EXPECT_EQ(proc_spec.working_directory, "/tmp");
    EXPECT_EQ(proc_spec.stdout_path, "/tmp/weston.log");
    EXPECT_EQ(proc_spec.stderr_path, "/tmp/weston.log");
}

TEST_MAIN()
