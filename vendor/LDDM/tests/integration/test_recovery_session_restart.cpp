#include "test_framework.hpp"
#include "lddm/session/session.hpp"
#include "lddm/weston/weston_manager.hpp"
#include "lddm/ldde/ldde_manager.hpp"
#include "lddm/recovery/recovery_manager.hpp"

#include <filesystem>
#include <fstream>
#include <sys/stat.h>
#include <csignal>
#include <thread>
#include <chrono>

using namespace lddm;
using namespace lddm::weston;
using namespace lddm::ldde;
using namespace lddm::recovery;

static std::string create_mock_weston(const std::string& bin_path) {
    std::ofstream out(bin_path);
    out << "#!/bin/bash\n";
    out << "SOC=\"${XDG_RUNTIME_DIR}/${WAYLAND_DISPLAY:-wayland-0}\"\n";
    out << "python3 -c \"import socket, select; s=socket.socket(socket.AF_UNIX, socket.SOCK_STREAM); s.bind('$SOC'); s.listen(128);\n"
        << "while True:\n"
        << "    r, _, _ = select.select([s], [], [], 1.0)\n"
        << "    if r:\n"
        << "        try:\n"
        << "            c, _ = s.accept()\n"
        << "            c.close()\n"
        << "        except:\n"
        << "            pass\" &\n";
    out << "PY_PID=$!\n";
    out << "trap \"kill -TERM $PY_PID 2>/dev/null; exit 0\" TERM INT EXIT\n";
    out << "wait $PY_PID\n";
    out.close();
    chmod(bin_path.c_str(), 0755);
    return bin_path;
}

static std::string create_mock_ldde(const std::string& bin_path) {
    std::ofstream out(bin_path);
    out << "#!/bin/bash\n";
    out << "if [ -n \"$LDDE_READINESS_FILE\" ]; then\n";
    out << "  echo \"STATUS=READY\" > \"$LDDE_READINESS_FILE\"\n";
    out << "  echo \"VERSION=1\" >> \"$LDDE_READINESS_FILE\"\n";
    out << "  echo \"PID=$$\" >> \"$LDDE_READINESS_FILE\"\n";
    out << "fi\n";
    out << "while true; do sleep 0.1; done\n";
    out.close();
    chmod(bin_path.c_str(), 0755);
    return bin_path;
}

TEST_CASE(Recovery_SessionRestart) {
    std::string base_dir = "/tmp/lddm_test_rec_session_restart";
    std::filesystem::remove_all(base_dir);
    std::filesystem::create_directories(base_dir);

    std::string mock_weston_bin = create_mock_weston(base_dir + "/mock_weston.sh");
    std::string mock_ldde_bin = create_mock_ldde(base_dir + "/mock_ldde.sh");

    SessionConfig scfg;
    scfg.base_runtime_dir = base_dir;
    scfg.type = SessionType::Wayland;

    Session session(scfg);

    WestonConfig wcfg;
    wcfg.executable = mock_weston_bin;
    wcfg.startup_timeout_ms = 2000;
    auto weston_mgr = std::make_shared<WestonManager>(wcfg);

    LddeConfig lcfg;
    lcfg.executable = mock_ldde_bin;
    lcfg.readiness_timeout_ms = 2000;
    auto ldde_mgr = std::make_shared<LddeManager>(lcfg);

    RecoveryConfig rcfg;
    rcfg.backoff_initial_ms = 10;
    rcfg.max_attempts = 3;
    auto rec_mgr = std::make_shared<RecoveryManager>(&session, rcfg);

    session.attach_compositor(weston_mgr);
    session.attach_desktop(ldde_mgr);
    session.attach_recovery(rec_mgr);

    auto start_res = session.start();
    EXPECT_TRUE(start_res.has_value());
    EXPECT_TRUE(session.is_graphical_session_ready());
    EXPECT_EQ(session.state(), SessionState::RUNNING);

    auto orig_weston_pid = weston_mgr->process()->pid();
    auto orig_ldde_pid = ldde_mgr->process()->pid();

    // Trigger full session restart
    auto restart_res = rec_mgr->restart_session(RecoveryReason::SessionRuntimeFailure);
    EXPECT_TRUE(restart_res.has_value());
    EXPECT_TRUE(session.is_graphical_session_ready());
    EXPECT_EQ(session.state(), SessionState::RUNNING);

    // Both processes restarted
    EXPECT_NE(weston_mgr->process()->pid(), orig_weston_pid);
    EXPECT_NE(ldde_mgr->process()->pid(), orig_ldde_pid);

    EXPECT_EQ(rec_mgr->diagnostics().successful_recoveries(), 1u);
    EXPECT_EQ(rec_mgr->state(), RecoveryState::Recovered);

    auto stop_res = session.stop();
    EXPECT_TRUE(stop_res.has_value());
    std::filesystem::remove_all(base_dir);
}

TEST_MAIN()
