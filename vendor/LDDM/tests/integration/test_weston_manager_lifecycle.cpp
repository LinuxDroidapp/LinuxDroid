#include "test_framework.hpp"
#include "lddm/weston/weston_manager.hpp"
#include "lddm/process/process_supervisor.hpp"
#include "lddm/weston/weston_readiness.hpp"
#include <filesystem>
#include <fstream>
#include <sys/stat.h>

using namespace lddm;
using namespace lddm::weston;

TEST_CASE(WestonManager_LifecycleHeadless) {
    std::string test_runtime = "/tmp/lddm_weston_lifecycle_test";
    std::filesystem::remove_all(test_runtime);
    std::filesystem::create_directories(test_runtime);
    chmod(test_runtime.c_str(), 0700);

    auto supervisor = std::make_shared<ProcessSupervisor>();

    WestonConfig config;
    config.executable = "/usr/bin/weston";
    config.backend = "headless";
    config.socket_name = "wayland-test-lifecycle";
    config.idle_time_seconds = 0;
    config.startup_timeout_ms = 5000;
    config.stop_timeout_ms = 3000;

    auto manager = std::make_shared<WestonManager>(config);
    manager->set_supervisor(supervisor);

    // Context mock
    SessionId sid = SessionId::generate();
    SessionIdentity id{
        .id = sid,
        .type = static_cast<std::uint32_t>(SessionType::Wayland),
        .user_id = 0,
        .group_id = 0,
        .username = "root"
    };
    SessionConfig scfg;
    SessionPaths paths(sid, "/tmp/lddm_weston_lifecycle_test_base");
    SessionEnvironment env;
    SessionContext ctx{
        .identity = id,
        .config = scfg,
        .paths = paths,
        .environment = env,
        .state = SessionState::READY,
        .supervisor = supervisor
    };

    // Initialize
    auto init_res = manager->initialize(ctx);
    EXPECT_TRUE(init_res.has_value());
    EXPECT_EQ(manager->state(), WestonState::Created);

    // Prepare
    auto prep_res = manager->prepare();
    EXPECT_TRUE(prep_res.has_value());
    EXPECT_EQ(manager->state(), WestonState::Preparing);

    // Start
    auto start_res = manager->start();
    EXPECT_TRUE(start_res.has_value());
    EXPECT_EQ(manager->state(), WestonState::Running);
    EXPECT_TRUE(manager->is_running());
    EXPECT_TRUE(manager->process_handle().has_value());
    EXPECT_TRUE(manager->process() != nullptr);

    // Verify Wayland socket exists and is connectable
    EXPECT_TRUE(std::filesystem::exists(manager->socket_path()));
    auto sock_status = WestonReadinessDetector::check_socket(
        manager->socket_path(), manager->process()->pid());
    EXPECT_EQ(sock_status, WaylandSocketStatus::WaylandConnectionUsable);

    // Verify diagnostics recorded
    auto& diag = manager->diagnostics();
    EXPECT_TRUE(diag.start_time().has_value());
    EXPECT_TRUE(diag.ready_time().has_value());
    EXPECT_TRUE(diag.startup_duration().count() >= 0);

    // Stop
    auto stop_res = manager->stop();
    EXPECT_TRUE(stop_res.has_value());
    EXPECT_EQ(manager->state(), WestonState::Stopped);
    EXPECT_FALSE(manager->is_running());

    std::filesystem::remove_all(test_runtime);
    (void)paths.remove_directories();
}

TEST_MAIN()
