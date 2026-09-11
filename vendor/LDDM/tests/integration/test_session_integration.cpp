#include "test_framework.hpp"
#include "lddm/session/session_manager.hpp"

class MockSessionService : public lddm::ISessionComponent {
public:
    explicit MockSessionService(std::string name)
        : name_(std::move(name)) {}

    [[nodiscard]] const std::string& name() const noexcept override { return name_; }
    [[nodiscard]] bool is_running() const noexcept override { return running_; }

    lddm::Result<void> initialize(const lddm::SessionContext& context) override {
        initialized_ = true;
        socket_dir_ = context.paths.runtime_dir().string();
        return lddm::Result<void>::success();
    }

    lddm::Result<void> start() override {
        if (!initialized_) {
            return lddm::Result<void>::failure(lddm::Error(
                lddm::ErrorCategory::Session,
                lddm::ErrorCode::SessionStartFailed,
                "Service not initialized"));
        }
        running_ = true;
        return lddm::Result<void>::success();
    }

    lddm::Result<void> stop() override {
        running_ = false;
        return lddm::Result<void>::success();
    }

    [[nodiscard]] bool was_initialized() const noexcept { return initialized_; }
    [[nodiscard]] const std::string& socket_dir() const noexcept { return socket_dir_; }

private:
    std::string name_;
    bool initialized_{false};
    bool running_{false};
    std::string socket_dir_{};
};

TEST_CASE(Integration_FullSessionOrchestration) {
    lddm::SessionManager manager;

    lddm::SessionConfig cfg{
        .id = lddm::SessionId::generate("sess-int"),
        .type = lddm::SessionType::Wayland,
        .user = "integration_user",
        .wayland_display = "wayland-int-0",
        .base_runtime_dir = "/tmp/lddm_session_integration"
    };

    auto create_res = manager.create_session(cfg);
    EXPECT_TRUE(create_res.has_value());
    auto session = create_res.value();
    EXPECT_EQ(manager.session_count(), 1u);
    EXPECT_EQ(manager.active_session(), session);

    // Attach mock components
    auto svc1 = std::make_shared<MockSessionService>("MockCompositor");
    auto svc2 = std::make_shared<MockSessionService>("MockDesktop");
    session->attach_component(svc1);
    session->attach_component(svc2);

    // Initialize session
    auto init_res = session->initialize();
    EXPECT_TRUE(init_res.has_value());
    EXPECT_EQ(session->state(), lddm::SessionState::READY);
    EXPECT_TRUE(svc1->was_initialized());
    EXPECT_TRUE(svc2->was_initialized());
    EXPECT_TRUE(session->paths().exists());

    // Start session
    auto start_res = session->start();
    EXPECT_TRUE(start_res.has_value());
    EXPECT_EQ(session->state(), lddm::SessionState::RUNNING);
    EXPECT_TRUE(svc1->is_running());
    EXPECT_TRUE(svc2->is_running());

    // Stop session
    auto stop_res = session->stop();
    EXPECT_TRUE(stop_res.has_value());
    EXPECT_EQ(session->state(), lddm::SessionState::STOPPED);
    EXPECT_FALSE(svc1->is_running());
    EXPECT_FALSE(svc2->is_running());
    EXPECT_FALSE(session->paths().exists()); // Cleaned up

    // Remove from manager
    auto rem_res = manager.remove_session(session->id());
    EXPECT_TRUE(rem_res.has_value());
    EXPECT_EQ(manager.session_count(), 0u);
    EXPECT_EQ(manager.active_session(), nullptr);
}

TEST_MAIN()

