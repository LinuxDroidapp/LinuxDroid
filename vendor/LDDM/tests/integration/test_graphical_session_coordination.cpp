#include "test_framework.hpp"
#include "lddm/session/session.hpp"
#include "lddm/weston/weston_manager.hpp"
#include "lddm/ldde/ldde_manager.hpp"
#include "lddm/weston/weston_readiness.hpp"
#include "lddm/ldde/ldde_readiness.hpp"
#include <filesystem>
#include <fstream>
#include <sys/stat.h>

using namespace lddm;
using namespace lddm::weston;
using namespace lddm::ldde;

TEST_CASE(GraphicalSession_Coordination) {
    std::string base_dir = "/tmp/lddm_test_graphical_session_coordination";
    std::filesystem::remove_all(base_dir);

    std::string mock_ldde_script = "/tmp/mock_ldde_coord.sh";
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
    scfg.clean_runtime_dir_on_stop = true;

    Session session(scfg);

    WestonConfig wcfg;
    wcfg.executable = "/usr/bin/weston";
    wcfg.backend = "headless";
    wcfg.socket_name = "wayland-coord-test";
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

    EXPECT_EQ(session.compositor(), weston_mgr.get());
    EXPECT_EQ(session.desktop(), ldde_mgr.get());

    // 1. Initialize session
    auto init_res = session.initialize();
    EXPECT_TRUE(init_res.has_value());
    EXPECT_EQ(session.state(), SessionState::READY);
    EXPECT_EQ(weston_mgr->state(), WestonState::Created);
    EXPECT_EQ(ldde_mgr->state(), LddeState::Created);
    EXPECT_FALSE(session.is_graphical_session_ready());

    // 2. Start session (Weston starts and becomes ready, then LDDE starts and becomes ready)
    auto start_res = session.start();
    EXPECT_TRUE(start_res.has_value());
    EXPECT_EQ(session.state(), SessionState::RUNNING);
    EXPECT_EQ(weston_mgr->state(), WestonState::Running);
    EXPECT_EQ(ldde_mgr->state(), LddeState::Running);
    EXPECT_TRUE(weston_mgr->is_running());
    EXPECT_TRUE(ldde_mgr->is_running());

    // Graphical session is fully ready!
    EXPECT_TRUE(session.is_graphical_session_ready());

    // Verify Wayland socket
    EXPECT_TRUE(std::filesystem::exists(weston_mgr->socket_path()));
    auto sock_status = WestonReadinessDetector::check_socket(
        weston_mgr->socket_path(),
        weston_mgr->process() ? weston_mgr->process()->pid() : -1);
    EXPECT_EQ(sock_status, WaylandSocketStatus::WaylandConnectionUsable);

    // Verify LDDE readiness file
    EXPECT_TRUE(std::filesystem::exists(ldde_mgr->readiness_path()));
    auto ldde_status = LddeReadinessDetector::check_readiness(
        ldde_mgr->readiness_path(),
        LddeReadinessMode::File,
        ldde_mgr->process() ? ldde_mgr->process()->pid() : -1);
    EXPECT_EQ(ldde_status, LddeReadinessStatus::Ready);

    // 3. Stop session (LDDE stops first, then Weston stops)
    auto stop_res = session.stop();
    EXPECT_TRUE(stop_res.has_value());
    EXPECT_EQ(session.state(), SessionState::STOPPED);
    EXPECT_EQ(ldde_mgr->state(), LddeState::Stopped);
    EXPECT_EQ(weston_mgr->state(), WestonState::Stopped);
    EXPECT_FALSE(ldde_mgr->is_running());
    EXPECT_FALSE(weston_mgr->is_running());
    EXPECT_FALSE(session.is_graphical_session_ready());

    std::filesystem::remove(mock_ldde_script);
    std::filesystem::remove_all(base_dir);
}

TEST_MAIN()
