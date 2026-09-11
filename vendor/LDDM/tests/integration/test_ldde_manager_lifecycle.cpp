#include "test_framework.hpp"
#include "lddm/ldde/ldde_manager.hpp"
#include "lddm/process/process_supervisor.hpp"
#include "lddm/ldde/ldde_readiness.hpp"
#include <filesystem>
#include <fstream>
#include <sys/stat.h>

using namespace lddm;
using namespace lddm::ldde;

TEST_CASE(LddeManager_Lifecycle) {
    std::string test_runtime = "/tmp/lddm_ldde_lifecycle_test";
    std::filesystem::remove_all(test_runtime);
    std::filesystem::create_directories(test_runtime);
    chmod(test_runtime.c_str(), 0700);

    std::string mock_script = "/tmp/mock_ldde_lifecycle.sh";
    {
        std::ofstream ofs(mock_script);
        ofs << "#!/bin/sh\n"
            << "echo \"STATUS=READY\" > \"$LDDE_READINESS_FILE\"\n"
            << "echo \"VERSION=1\" >> \"$LDDE_READINESS_FILE\"\n"
            << "trap 'exit 0' TERM INT\n"
            << "while true; do sleep 1; done\n";
    }
    chmod(mock_script.c_str(), 0755);

    auto supervisor = std::make_shared<ProcessSupervisor>();

    LddeConfig config;
    config.executable = mock_script;
    config.session_target = "default";
    config.startup_timeout_ms = 5000;
    config.stop_timeout_ms = 3000;
    config.readiness_timeout_ms = 5000;

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
    SessionPaths paths(sid, "/tmp/lddm_ldde_lifecycle_base");
    (void)paths.create_directories();
    SessionEnvironment env;
    env.set("WAYLAND_DISPLAY", "wayland-0");

    SessionContext ctx{
        .identity = id,
        .config = scfg,
        .paths = paths,
        .environment = env,
        .state = SessionState::READY,
        .supervisor = supervisor
    };

    // 1. Initialize
    auto init_res = manager->initialize(ctx);
    EXPECT_TRUE(init_res.has_value());
    EXPECT_EQ(manager->state(), LddeState::Created);

    // 2. Prepare
    auto prep_res = manager->prepare();
    EXPECT_TRUE(prep_res.has_value());
    EXPECT_EQ(manager->state(), LddeState::Preparing);

    // 3. Start
    auto start_res = manager->start();
    EXPECT_TRUE(start_res.has_value());
    EXPECT_EQ(manager->state(), LddeState::Running);
    EXPECT_TRUE(manager->is_running());
    EXPECT_TRUE(manager->process_handle().has_value());
    EXPECT_TRUE(manager->process() != nullptr);

    // Verify readiness file was created and is valid
    EXPECT_TRUE(std::filesystem::exists(manager->readiness_path()));
    auto status = LddeReadinessDetector::check_readiness(
        manager->readiness_path(), LddeReadinessMode::File, manager->process()->pid());
    EXPECT_EQ(status, LddeReadinessStatus::Ready);

    // Verify diagnostics recorded
    auto& diag = manager->diagnostics();
    EXPECT_TRUE(diag.start_time().has_value());
    EXPECT_TRUE(diag.ready_time().has_value());
    EXPECT_TRUE(diag.startup_duration().count() >= 0);

    // 4. Stop
    auto stop_res = manager->stop();
    EXPECT_TRUE(stop_res.has_value());
    EXPECT_EQ(manager->state(), LddeState::Stopped);
    EXPECT_FALSE(manager->is_running());
    EXPECT_FALSE(std::filesystem::exists(manager->readiness_path()));

    std::filesystem::remove(mock_script);
    std::filesystem::remove_all(test_runtime);
    (void)paths.remove_directories();
}

TEST_MAIN()
