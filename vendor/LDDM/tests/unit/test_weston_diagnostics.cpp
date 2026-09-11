#include "test_framework.hpp"
#include "lddm/weston/weston_diagnostics.hpp"
#include <thread>

using namespace lddm::weston;

TEST_CASE(WestonDiagnostics_Recording) {
    WestonDiagnostics diag;
    EXPECT_EQ(diag.state(), WestonState::Created);

    diag.record_transition(WestonState::Created, WestonState::Preparing, "Preparing");
    diag.record_transition(WestonState::Preparing, WestonState::Starting, "Starting");
    diag.record_transition(WestonState::Starting, WestonState::WaitingReady, "Waiting");
    diag.record_transition(WestonState::WaitingReady, WestonState::Running, "Ready");

    EXPECT_EQ(diag.state(), WestonState::Running);
    EXPECT_EQ(diag.transition_history().size(), 4);

    diag.set_pid(12345);
    EXPECT_EQ(diag.pid(), 12345);

    diag.set_executable("/usr/bin/weston");
    EXPECT_EQ(diag.executable(), "/usr/bin/weston");

    diag.set_config_path("/etc/xdg/weston/weston.ini");
    EXPECT_EQ(diag.config_path(), "/etc/xdg/weston/weston.ini");

    diag.set_runtime_dir("/run/user/1000");
    EXPECT_EQ(diag.runtime_dir(), "/run/user/1000");

    diag.set_wayland_display("wayland-0");
    EXPECT_EQ(diag.wayland_display(), "wayland-0");

    diag.set_socket_path("/run/user/1000/wayland-0");
    EXPECT_EQ(diag.socket_path(), "/run/user/1000/wayland-0");

    diag.set_log_path("/var/log/weston.log");
    EXPECT_EQ(diag.log_path(), "/var/log/weston.log");
}

TEST_CASE(WestonDiagnostics_Durations) {
    WestonDiagnostics diag;
    diag.record_start_time();
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    diag.record_ready_time();
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    diag.record_stop_time();

    EXPECT_TRUE(diag.startup_duration().count() >= 15);
    EXPECT_TRUE(diag.total_runtime().count() >= 35);
}

TEST_CASE(WestonDiagnostics_ErrorAndExit) {
    WestonDiagnostics diag;
    EXPECT_FALSE(diag.last_error().has_value());
    EXPECT_FALSE(diag.exit_info().has_value());

    lddm::Error err(lddm::ErrorCategory::Compositor, lddm::ErrorCode::CompositorCrash, "Weston crashed");
    diag.record_error(err);
    EXPECT_TRUE(diag.last_error().has_value());
    EXPECT_EQ(diag.last_error()->code(), lddm::ErrorCode::CompositorCrash);

    lddm::process::ProcessExitInfo exit_info;
    exit_info.pid = 12345;
    exit_info.exit_code = 1;
    exit_info.exited_normally = true;
    diag.record_exit_info(exit_info);

    EXPECT_TRUE(diag.exit_info().has_value());
    EXPECT_EQ(diag.exit_info()->pid, 12345);
    EXPECT_EQ(diag.exit_info()->exit_code, 1);
}

TEST_MAIN()
