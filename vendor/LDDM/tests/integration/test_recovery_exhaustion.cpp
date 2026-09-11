#include "test_framework.hpp"
#include "lddm/session/session.hpp"
#include "lddm/weston/weston_manager.hpp"
#include "lddm/ldde/ldde_manager.hpp"
#include "lddm/recovery/recovery_manager.hpp"

#include <filesystem>
#include <fstream>
#include <sys/stat.h>

using namespace lddm;
using namespace lddm::weston;
using namespace lddm::ldde;
using namespace lddm::recovery;

static std::string create_failing_binary(const std::string& bin_path) {
    std::ofstream out(bin_path);
    out << "#!/bin/bash\n";
    out << "exit 1\n";
    out.close();
    chmod(bin_path.c_str(), 0755);
    return bin_path;
}

TEST_CASE(Recovery_ExhaustionAndFailureLoopPrevention) {
    std::string base_dir = "/tmp/lddm_test_rec_exhaustion";
    std::filesystem::remove_all(base_dir);
    std::filesystem::create_directories(base_dir);

    std::string fail_bin = create_failing_binary(base_dir + "/failing_proc.sh");

    SessionConfig scfg;
    scfg.base_runtime_dir = base_dir;
    scfg.type = SessionType::Wayland;

    Session session(scfg);
    EXPECT_TRUE(session.initialize().has_value());

    WestonConfig wcfg;
    wcfg.executable = fail_bin;
    wcfg.startup_timeout_ms = 500;
    auto weston_mgr = std::make_shared<WestonManager>(wcfg);

    RecoveryConfig rcfg;
    rcfg.max_attempts = 2; // bounded to 2 attempts
    rcfg.backoff_initial_ms = 5;
    rcfg.backoff_max_ms = 20;
    auto rec_mgr = std::make_shared<RecoveryManager>(&session, rcfg);

    session.attach_compositor(weston_mgr);
    session.attach_recovery(rec_mgr);

    // Attempt 1: fails
    auto res1 = rec_mgr->recover(RecoveryReason::WestonStartFailure, "weston");
    EXPECT_FALSE(res1.has_value());
    EXPECT_EQ(rec_mgr->state(), RecoveryState::Failed);

    // Attempt 2: fails
    auto res2 = rec_mgr->recover(RecoveryReason::WestonStartFailure, "weston");
    EXPECT_FALSE(res2.has_value());
    EXPECT_EQ(rec_mgr->state(), RecoveryState::Failed);

    // Attempt 3: exceeds max_attempts (2) -> RecoveryExhausted
    auto res3 = rec_mgr->recover(RecoveryReason::WestonStartFailure, "weston");
    EXPECT_FALSE(res3.has_value());
    EXPECT_EQ(res3.error().code(), ErrorCode::RecoveryExhausted);
    EXPECT_EQ(rec_mgr->state(), RecoveryState::Failed);
    EXPECT_EQ(session.state(), SessionState::FAILED);

    std::filesystem::remove_all(base_dir);
}

TEST_MAIN()
