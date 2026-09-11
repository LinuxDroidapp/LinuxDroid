#include "test_framework.hpp"
#include "lddm/ldde/ldde_manager.hpp"
#include "lddm/process/process_supervisor.hpp"
#include <filesystem>
#include <fstream>
#include <sys/stat.h>

using namespace lddm;
using namespace lddm::ldde;

TEST_CASE(LddeFailure_MissingExecutable) {
    auto supervisor = std::make_shared<ProcessSupervisor>();

    LddeConfig config;
    config.executable = "/nonexistent/invalid/ldde_bin";

    auto manager = std::make_shared<LddeManager>(config);
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
    SessionPaths paths(sid, "/tmp/lddm_test_ldde_missing_exe");
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
    EXPECT_EQ(init_res.error().code(), ErrorCode::LddeExecutableNotFound);
    EXPECT_EQ(manager->state(), LddeState::Failed);

    (void)paths.remove_directories();
}

TEST_CASE(LddeFailure_ProcessExitsEarly) {
    auto supervisor = std::make_shared<ProcessSupervisor>();

    LddeConfig config;
    config.executable = "/usr/bin/false";
    config.readiness_timeout_ms = 500;

    auto manager = std::make_shared<LddeManager>(config);
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
    SessionPaths paths(sid, "/tmp/lddm_test_ldde_early_exit");
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
    EXPECT_EQ(manager->state(), LddeState::Failed);
    EXPECT_TRUE(manager->diagnostics().last_error().has_value());

    (void)paths.remove_directories();
}

TEST_CASE(LddeFailure_StartupTimeout) {
    auto supervisor = std::make_shared<ProcessSupervisor>();

    std::string sleeper_script = "/tmp/lddm_ldde_dummy_sleeper.sh";
    {
        std::ofstream ofs(sleeper_script);
        ofs << "#!/bin/sh\nsleep 10\n";
    }
    chmod(sleeper_script.c_str(), 0755);

    LddeConfig config;
    config.executable = sleeper_script;
    config.readiness_timeout_ms = 150;

    auto manager = std::make_shared<LddeManager>(config);
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
    SessionPaths paths(sid, "/tmp/lddm_test_ldde_timeout");
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
    EXPECT_EQ(start_res.error().code(), ErrorCode::LddeTimeout);
    EXPECT_EQ(manager->state(), LddeState::Failed);

    std::filesystem::remove(sleeper_script);
    (void)paths.remove_directories();
}

TEST_CASE(LddeFailure_ProtocolError) {
    auto supervisor = std::make_shared<ProcessSupervisor>();

    std::string fail_script = "/tmp/lddm_ldde_fail_script.sh";
    {
        std::ofstream ofs(fail_script);
        ofs << "#!/bin/sh\n"
            << "echo \"STATUS=FAILED: Missing desktop dependencies\" > \"$LDDE_READINESS_FILE\"\n"
            << "sleep 2\n";
    }
    chmod(fail_script.c_str(), 0755);

    LddeConfig config;
    config.executable = fail_script;
    config.readiness_timeout_ms = 500;

    auto manager = std::make_shared<LddeManager>(config);
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
    SessionPaths paths(sid, "/tmp/lddm_test_ldde_protocol_err");
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
    EXPECT_EQ(start_res.error().code(), ErrorCode::LddeProtocolError);
    EXPECT_EQ(manager->state(), LddeState::Failed);

    std::filesystem::remove(fail_script);
    (void)paths.remove_directories();
}

TEST_CASE(LddeFailure_RepeatedStop) {
    LddeConfig config;
    auto manager = std::make_shared<LddeManager>(config);

    // Initial stop from Created
    auto stop1 = manager->stop();
    EXPECT_TRUE(stop1.has_value());
    EXPECT_EQ(manager->state(), LddeState::Stopped);

    // Repeated stop from Stopped
    auto stop2 = manager->stop();
    EXPECT_TRUE(stop2.has_value());
    EXPECT_EQ(manager->state(), LddeState::Stopped);
}

TEST_MAIN()
