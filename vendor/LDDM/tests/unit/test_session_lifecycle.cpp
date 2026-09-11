#include "test_framework.hpp"
#include "lddm/session/session.hpp"

class FailingComponent : public lddm::ISessionComponent {
public:
    [[nodiscard]] const std::string& name() const noexcept override {
        static std::string n = "FailingComponent";
        return n;
    }
    [[nodiscard]] bool is_running() const noexcept override { return false; }
    lddm::Result<void> start() override {
        return lddm::Result<void>::failure(lddm::Error(
            lddm::ErrorCategory::Session,
            lddm::ErrorCode::SessionStartFailed,
            "Simulated component failure"));
    }
    lddm::Result<void> stop() override {
        return lddm::Result<void>::success();
    }
};

TEST_CASE(SessionLifecycle_CompleteWorkflow) {
    lddm::SessionConfig cfg{
        .id = lddm::SessionId("sess-workflow-test"),
        .type = lddm::SessionType::Wayland,
        .user = "root",
        .base_runtime_dir = "/tmp/lddm_workflow_tests"
    };

    lddm::Session session(cfg);
    EXPECT_EQ(session.state(), lddm::SessionState::CREATED);

    // 1. Initialize
    auto init_res = session.initialize();
    EXPECT_TRUE(init_res.has_value());
    EXPECT_EQ(session.state(), lddm::SessionState::READY);
    EXPECT_TRUE(session.paths().exists());

    // 2. Prepare (idempotent when READY)
    auto prep_res = session.prepare();
    EXPECT_TRUE(prep_res.has_value());

    // 3. Start
    auto start_res = session.start();
    EXPECT_TRUE(start_res.has_value());
    EXPECT_EQ(session.state(), lddm::SessionState::RUNNING);

    // 4. Stop
    auto stop_res = session.stop();
    EXPECT_TRUE(stop_res.has_value());
    EXPECT_EQ(session.state(), lddm::SessionState::STOPPED);
    EXPECT_FALSE(session.paths().exists()); // Cleaned up

    // 5. Cleanup (idempotent)
    auto clean_res = session.cleanup();
    EXPECT_TRUE(clean_res.has_value());
}

TEST_CASE(SessionLifecycle_StartupFailureRecovery) {
    lddm::SessionConfig cfg{
        .id = lddm::SessionId("sess-fail-recovery"),
        .base_runtime_dir = "/tmp/lddm_workflow_tests"
    };

    lddm::Session session(cfg);
    session.attach_component(std::make_shared<FailingComponent>());

    auto init_res = session.initialize();
    EXPECT_TRUE(init_res.has_value());

    auto start_res = session.start();
    EXPECT_FALSE(start_res.has_value());
    EXPECT_EQ(session.state(), lddm::SessionState::FAILED);

    // Diagnostics should record the error
    EXPECT_TRUE(session.diagnostics().last_error().has_value());

    // Cleanup from FAILED
    auto clean_res = session.cleanup();
    EXPECT_TRUE(clean_res.has_value());
}

TEST_MAIN()

