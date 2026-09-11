#include "test_framework.hpp"
#include "lddm/session/session.hpp"
#include "lddm/weston/weston_manager.hpp"
#include "lddm/ldde/ldde_manager.hpp"
#include <filesystem>
#include <fstream>
#include <sys/stat.h>
#include <csignal>
#include <thread>
#include <chrono>

using namespace lddm;
using namespace lddm::weston;
using namespace lddm::ldde;

TEST_CASE(GraphicalSession_WestonFailureBlocksLDDE) {
    std::string base_dir = "/tmp/lddm_fail_weston_blocks_ldde";
    std::filesystem::remove_all(base_dir);

    SessionConfig scfg;
    scfg.base_runtime_dir = base_dir;
    scfg.type = SessionType::Wayland;

    Session session(scfg);

    WestonConfig wcfg;
    wcfg.executable = "/nonexistent/invalid/weston";

    auto weston_mgr = std::make_shared<WestonManager>(wcfg);
    session.attach_compositor(weston_mgr);

    LddeConfig lcfg;
    lcfg.executable = "/bin/true";

    auto ldde_mgr = std::make_shared<LddeManager>(lcfg);
    session.attach_desktop(ldde_mgr);

    // Initialization fails because Weston executable resolution fails
    auto init_res = session.initialize();
    EXPECT_FALSE(init_res.has_value());
    EXPECT_EQ(session.state(), SessionState::FAILED);
    EXPECT_EQ(weston_mgr->state(), WestonState::Failed);
    EXPECT_EQ(ldde_mgr->state(), LddeState::Created);
    EXPECT_FALSE(session.is_graphical_session_ready());

    std::filesystem::remove_all(base_dir);
}

TEST_CASE(GraphicalSession_LddeFailureRollsBackWeston) {
    std::string base_dir = "/tmp/lddm_fail_ldde_rollback_weston";
    std::filesystem::remove_all(base_dir);

    SessionConfig scfg;
    scfg.base_runtime_dir = base_dir;
    scfg.type = SessionType::Wayland;

    Session session(scfg);

    WestonConfig wcfg;
    wcfg.executable = "/usr/bin/weston";
    wcfg.backend = "headless";
    wcfg.socket_name = "wayland-fail-rollback";
    wcfg.startup_timeout_ms = 5000;
    wcfg.stop_timeout_ms = 3000;

    auto weston_mgr = std::make_shared<WestonManager>(wcfg);
    session.attach_compositor(weston_mgr);

    // LDDE will fail immediately on start
    LddeConfig lcfg;
    lcfg.executable = "/usr/bin/false";
    lcfg.readiness_timeout_ms = 500;

    auto ldde_mgr = std::make_shared<LddeManager>(lcfg);
    session.attach_desktop(ldde_mgr);

    auto init_res = session.initialize();
    EXPECT_TRUE(init_res.has_value());
    EXPECT_EQ(session.state(), SessionState::READY);

    // Start session: Weston starts, but LDDE fails on start
    auto start_res = session.start();
    EXPECT_FALSE(start_res.has_value());
    EXPECT_EQ(session.state(), SessionState::FAILED);

    // Rollback policy: Weston must be rolled back and stopped!
    EXPECT_EQ(weston_mgr->state(), WestonState::Stopped);
    EXPECT_FALSE(weston_mgr->is_running());
    EXPECT_EQ(ldde_mgr->state(), LddeState::Failed);
    EXPECT_FALSE(ldde_mgr->is_running());
    EXPECT_FALSE(session.is_graphical_session_ready());

    std::filesystem::remove_all(base_dir);
}

TEST_CASE(GraphicalSession_LddeUnexpectedExit) {
    std::string base_dir = "/tmp/lddm_fail_ldde_unexpected_exit";
    std::filesystem::remove_all(base_dir);

    std::string mock_ldde_script = "/tmp/mock_ldde_crash.sh";
    {
        std::ofstream ofs(mock_ldde_script);
        ofs << "#!/bin/sh\n"
            << "echo \"STATUS=READY\" > \"$LDDE_READINESS_FILE\"\n"
            << "echo \"VERSION=1\" >> \"$LDDE_READINESS_FILE\"\n"
            << "trap 'exit 0' TERM INT\n"
            << "while true; do sleep 1; done\n";
    }
    chmod(mock_ldde_script.c_str(), 0755);

    SessionConfig scfg;
    scfg.base_runtime_dir = base_dir;
    scfg.type = SessionType::Wayland;

    Session session(scfg);

    WestonConfig wcfg;
    wcfg.executable = "/usr/bin/weston";
    wcfg.backend = "headless";
    wcfg.socket_name = "wayland-crash-test";
    wcfg.startup_timeout_ms = 5000;
    wcfg.stop_timeout_ms = 3000;

    auto weston_mgr = std::make_shared<WestonManager>(wcfg);
    session.attach_compositor(weston_mgr);

    LddeConfig lcfg;
    lcfg.executable = mock_ldde_script;
    lcfg.session_target = "default";
    lcfg.startup_timeout_ms = 5000;
    lcfg.stop_timeout_ms = 3000;
    lcfg.readiness_timeout_ms = 5000;

    auto ldde_mgr = std::make_shared<LddeManager>(lcfg);
    session.attach_desktop(ldde_mgr);

    auto init_res = session.initialize();
    EXPECT_TRUE(init_res.has_value());

    auto start_res = session.start();
    EXPECT_TRUE(start_res.has_value());
    EXPECT_TRUE(session.is_graphical_session_ready());

    // Kill LDDE process simulating crash
    if (ldde_mgr->process()) {
        kill(ldde_mgr->process()->pid(), SIGKILL);
    }

    // Wait for supervisor to reap and emit event
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    (void)session.supervisor()->reap_exited_processes();

    EXPECT_EQ(ldde_mgr->state(), LddeState::Failed);
    EXPECT_FALSE(ldde_mgr->is_running());
    EXPECT_FALSE(session.is_graphical_session_ready());

    // Stop session cleanly
    (void)session.stop();
    EXPECT_EQ(session.state(), SessionState::STOPPED);

    std::filesystem::remove(mock_ldde_script);
    std::filesystem::remove_all(base_dir);
}

TEST_MAIN()
