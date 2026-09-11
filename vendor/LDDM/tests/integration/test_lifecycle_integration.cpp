#include "test_framework.hpp"
#include "lddm/core/lifecycle.hpp"
#include "lddm/config/config.hpp"
#include "lddm/logging/logger.hpp"
#include "lddm/platform/signal_handler.hpp"
#include "lddm/session/session.hpp"

TEST_CASE(Integration_FullLifecycleRun) {
    // 1. Logging setup
    auto mem_sink = std::make_shared<lddm::MemorySink>();
    lddm::Logger::instance().clear_sinks();
    lddm::Logger::instance().add_sink(mem_sink);
    lddm::Logger::instance().set_level(lddm::LogLevel::TRACE);

    // 2. Lifecycle initial state
    lddm::LifecycleStateMachine lifecycle;
    EXPECT_EQ(lifecycle.state(), lddm::LifecycleState::CREATED);

    // 3. Enter INITIALIZING
    auto init_res = lifecycle.transition_to(lddm::LifecycleState::INITIALIZING, "System boot");
    EXPECT_TRUE(init_res.has_value());

    // 4. Initialize SignalHandler
    auto sig_res = lddm::SignalHandler::initialize();
    EXPECT_TRUE(sig_res.has_value());

    // 5. Config loading
    lddm::ConfigManager cfg_mgr;
    auto cfg_res = cfg_mgr.load_defaults();
    EXPECT_TRUE(cfg_res.has_value());

    // 6. Enter READY
    auto ready_res = lifecycle.transition_to(lddm::LifecycleState::READY, "Subsystems initialized");
    EXPECT_TRUE(ready_res.has_value());

    // 7. Enter STARTING
    auto starting_res = lifecycle.transition_to(lddm::LifecycleState::STARTING, "Starting default session");
    EXPECT_TRUE(starting_res.has_value());

    // 8. Session creation and preparation
    lddm::SessionConfig s_cfg{
        .id = lddm::SessionId("session-integration-0"),
        .type = lddm::SessionType::Wayland,
        .user = "root",
        .wayland_display = "wayland-0",
        .base_runtime_dir = "/tmp/lddm_integration_tests"
    };
    lddm::Session session(std::move(s_cfg));
    auto prep_res = session.prepare();
    EXPECT_TRUE(prep_res.has_value());

    // 9. Enter RUNNING
    auto run_res = lifecycle.transition_to(lddm::LifecycleState::RUNNING, "LDDM operational");
    EXPECT_TRUE(run_res.has_value());
    EXPECT_TRUE(lifecycle.is_running());

    // 10. Teardown / STOPPING
    auto stop_res = lifecycle.transition_to(lddm::LifecycleState::STOPPING, "Shutdown signal");
    EXPECT_TRUE(stop_res.has_value());

    auto sess_term = session.terminate();
    EXPECT_TRUE(sess_term.has_value());
    EXPECT_EQ(session.state(), lddm::SessionState::STOPPED);

    // 11. Enter STOPPED
    auto stopped_res = lifecycle.transition_to(lddm::LifecycleState::STOPPED, "Shutdown complete");
    EXPECT_TRUE(stopped_res.has_value());
    EXPECT_TRUE(lifecycle.is_terminal());

    lddm::SignalHandler::restore_defaults();

    // Verify logs were generated throughout
    EXPECT_TRUE(mem_sink->size() > 0);
}

TEST_MAIN()

