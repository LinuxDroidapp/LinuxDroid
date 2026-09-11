#include "test_framework.hpp"
#include "lddm/weston/weston_manager.hpp"
#include "lddm/process/process_supervisor.hpp"
#include <filesystem>
#include <fstream>
#include <sys/stat.h>

using namespace lddm;
using namespace lddm::weston;

TEST_CASE(WestonFailure_MissingExecutable) {
    auto supervisor = std::make_shared<ProcessSupervisor>();

    WestonConfig config;
    config.executable = "/nonexistent/invalid/weston_bin";

    auto manager = std::make_shared<WestonManager>(config);
    manager->set_supervisor(supervisor);

    SessionId sid = SessionId::generate();
    SessionIdentity id{
        .id = sid,
        .type = static_cast<std::uint32_t>(SessionType::Wayland),
        .user_id = 0,
        .group_id = 0,
        .username = "root"
    };
    SessionConfig scfg;
    SessionPaths paths(sid, "/tmp/lddm_test_fail_missing_exe");
    SessionEnvironment env;
    SessionContext ctx{
        .identity = id,
        .config = scfg,
        .paths = paths,
        .environment = env,
        .state = SessionState::READY,
        .supervisor = supervisor
    };

    auto init_res = manager->initialize(ctx);
    EXPECT_FALSE(init_res.has_value());
    EXPECT_EQ(init_res.error().code(), ErrorCode::CompositorExecutableNotFound);
    EXPECT_EQ(manager->state(), WestonState::Failed);

    (void)paths.remove_directories();
}

TEST_CASE(WestonFailure_ProcessExitsEarly) {
    auto supervisor = std::make_shared<ProcessSupervisor>();

    WestonConfig config;
    config.executable = "/usr/bin/false";
    config.startup_timeout_ms = 500;

    auto manager = std::make_shared<WestonManager>(config);
    manager->set_supervisor(supervisor);

    SessionId sid = SessionId::generate();
    SessionIdentity id{
        .id = sid,
        .type = static_cast<std::uint32_t>(SessionType::Wayland),
        .user_id = 0,
        .group_id = 0,
        .username = "root"
    };
    SessionConfig scfg;
    SessionPaths paths(sid, "/tmp/lddm_test_fail_early_exit");
    SessionEnvironment env;
    SessionContext ctx{
        .identity = id,
        .config = scfg,
        .paths = paths,
        .environment = env,
        .state = SessionState::READY,
        .supervisor = supervisor
    };

    auto init_res = manager->initialize(ctx);
    EXPECT_TRUE(init_res.has_value());

    auto start_res = manager->start();
    EXPECT_FALSE(start_res.has_value());
    EXPECT_EQ(manager->state(), WestonState::Failed);
    EXPECT_TRUE(manager->diagnostics().last_error().has_value());

    (void)paths.remove_directories();
}

TEST_CASE(WestonFailure_StartupTimeout) {
    auto supervisor = std::make_shared<ProcessSupervisor>();

    std::string sleeper_script = "/tmp/lddm_dummy_sleeper.sh";
    {
        std::ofstream ofs(sleeper_script);
        ofs << "#!/bin/sh\nsleep 10\n";
    }
    chmod(sleeper_script.c_str(), 0755);

    WestonConfig config;
    config.executable = sleeper_script;
    config.startup_timeout_ms = 150;

    auto manager = std::make_shared<WestonManager>(config);
    manager->set_supervisor(supervisor);

    SessionId sid = SessionId::generate();
    SessionIdentity id{
        .id = sid,
        .type = static_cast<std::uint32_t>(SessionType::Wayland),
        .user_id = 0,
        .group_id = 0,
        .username = "root"
    };
    SessionConfig scfg;
    SessionPaths paths(sid, "/tmp/lddm_test_fail_timeout");
    SessionEnvironment env;
    SessionContext ctx{
        .identity = id,
        .config = scfg,
        .paths = paths,
        .environment = env,
        .state = SessionState::READY,
        .supervisor = supervisor
    };

    auto init_res = manager->initialize(ctx);
    EXPECT_TRUE(init_res.has_value());

    auto start_res = manager->start();
    EXPECT_FALSE(start_res.has_value());
    EXPECT_EQ(start_res.error().code(), ErrorCode::CompositorTimeout);
    EXPECT_EQ(manager->state(), WestonState::Failed);

    std::filesystem::remove(sleeper_script);
    (void)paths.remove_directories();
}

TEST_CASE(WestonFailure_RepeatedStop) {
    WestonConfig config;
    auto manager = std::make_shared<WestonManager>(config);

    // Initial stop from Created
    auto stop1 = manager->stop();
    EXPECT_TRUE(stop1.has_value());
    EXPECT_EQ(manager->state(), WestonState::Stopped);

    // Repeated stop from Stopped
    auto stop2 = manager->stop();
    EXPECT_TRUE(stop2.has_value());
    EXPECT_EQ(manager->state(), WestonState::Stopped);
}

TEST_MAIN()
