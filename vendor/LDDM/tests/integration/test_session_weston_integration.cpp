#include "test_framework.hpp"
#include "lddm/session/session.hpp"
#include "lddm/weston/weston_manager.hpp"
#include "lddm/weston/weston_readiness.hpp"
#include <filesystem>

using namespace lddm;
using namespace lddm::weston;

TEST_CASE(SessionWeston_FullIntegration) {
    std::string base_dir = "/tmp/lddm_test_session_weston_integration";
    std::filesystem::remove_all(base_dir);

    SessionConfig scfg;
    scfg.base_runtime_dir = base_dir;
    scfg.type = SessionType::Wayland;
    scfg.clean_runtime_dir_on_stop = true;

    Session session(scfg);

    WestonConfig wcfg;
    wcfg.executable = "/usr/bin/weston";
    wcfg.backend = "headless";
    wcfg.socket_name = "wayland-session-integration";
    wcfg.startup_timeout_ms = 5000;
    wcfg.stop_timeout_ms = 3000;

    auto weston_mgr = std::make_shared<WestonManager>(wcfg);
    session.attach_compositor(weston_mgr);

    EXPECT_EQ(session.compositor(), weston_mgr.get());

    // 1. Initialize session
    auto init_res = session.initialize();
    EXPECT_TRUE(init_res.has_value());
    EXPECT_EQ(session.state(), SessionState::READY);
    EXPECT_EQ(weston_mgr->state(), WestonState::Created);

    // 2. Start session (starts Weston and waits for readiness)
    auto start_res = session.start();
    EXPECT_TRUE(start_res.has_value());
    EXPECT_EQ(session.state(), SessionState::RUNNING);
    EXPECT_EQ(weston_mgr->state(), WestonState::Running);
    EXPECT_TRUE(weston_mgr->is_running());

    // Verify Wayland socket
    EXPECT_TRUE(std::filesystem::exists(weston_mgr->socket_path()));
    auto status = WestonReadinessDetector::check_socket(
        weston_mgr->socket_path(),
        weston_mgr->process() ? weston_mgr->process()->pid() : -1);
    EXPECT_EQ(status, WaylandSocketStatus::WaylandConnectionUsable);

    // 3. Stop session (stops Weston cleanly and cleans up)
    auto stop_res = session.stop();
    EXPECT_TRUE(stop_res.has_value());
    EXPECT_EQ(session.state(), SessionState::STOPPED);
    EXPECT_EQ(weston_mgr->state(), WestonState::Stopped);
    EXPECT_FALSE(weston_mgr->is_running());

    std::filesystem::remove_all(base_dir);
}

TEST_MAIN()
