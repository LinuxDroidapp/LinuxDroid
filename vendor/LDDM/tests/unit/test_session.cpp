#include "test_framework.hpp"
#include "lddm/session/session.hpp"

class MockCompositor : public lddm::ICompositorInstance {
public:
    explicit MockCompositor(std::string name = "MockWeston")
        : name_(std::move(name)) {}

    [[nodiscard]] const std::string& name() const noexcept override { return name_; }
    [[nodiscard]] bool is_running() const noexcept override { return running_; }
    [[nodiscard]] const std::string& socket_path() const noexcept override { return socket_; }

    lddm::Result<void> start() override {
        running_ = true;
        return lddm::Result<void>::success();
    }

    lddm::Result<void> stop() override {
        running_ = false;
        return lddm::Result<void>::success();
    }

private:
    std::string name_;
    std::string socket_{"/run/lddm/wayland-0"};
    bool running_{false};
};

class MockDesktop : public lddm::IDesktopEnvironmentInstance {
public:
    explicit MockDesktop(std::string name = "MockLDDE")
        : name_(std::move(name)) {}

    [[nodiscard]] const std::string& name() const noexcept override { return name_; }
    [[nodiscard]] bool is_running() const noexcept override { return running_; }

    lddm::Result<void> start() override {
        running_ = true;
        return lddm::Result<void>::success();
    }

    lddm::Result<void> stop() override {
        running_ = false;
        return lddm::Result<void>::success();
    }

private:
    std::string name_;
    bool running_{false};
};

TEST_CASE(Session_CreationAndEnvironment) {
    lddm::SessionConfig cfg{
        .id = lddm::SessionId("session-test-01"),
        .type = lddm::SessionType::Wayland,
        .user = "droiduser",
        .wayland_display = "wayland-9",
        .base_runtime_dir = "/tmp/lddm_test_runtime"
    };

    lddm::Session session(cfg);
    EXPECT_EQ(session.id().str(), "session-test-01");
    EXPECT_EQ(session.state(), lddm::SessionState::CREATED);

    // Initialize to populate environment
    auto init_res = session.initialize();
    EXPECT_TRUE(init_res.has_value());
    EXPECT_EQ(session.state(), lddm::SessionState::READY);

    const auto& env = session.environment();
    auto user_opt = env.get("USER");
    EXPECT_TRUE(user_opt.has_value());
    if (user_opt) {
        EXPECT_EQ(*user_opt, "droiduser");
    }

    auto disp_opt = env.get("WAYLAND_DISPLAY");
    EXPECT_TRUE(disp_opt.has_value());
    if (disp_opt) {
        EXPECT_EQ(*disp_opt, "wayland-9");
    }

    (void)session.stop();
}

TEST_CASE(Session_LifecycleAndComponentOrchestration) {
    lddm::SessionConfig cfg{
        .id = lddm::SessionId("session-orchestration"),
        .type = lddm::SessionType::Wayland,
        .user = "root",
        .base_runtime_dir = "/tmp/lddm_test_runtime"
    };

    lddm::Session session(cfg);
    auto comp = std::make_shared<MockCompositor>();
    auto desk = std::make_shared<MockDesktop>();

    session.attach_compositor(comp);
    session.attach_desktop(desk);

    EXPECT_FALSE(comp->is_running());
    EXPECT_FALSE(desk->is_running());

    // Activate session
    auto act_res = session.start();
    EXPECT_TRUE(act_res.has_value());
    EXPECT_EQ(session.state(), lddm::SessionState::RUNNING);
    EXPECT_TRUE(comp->is_running());
    EXPECT_TRUE(desk->is_running());

    // Terminate session
    auto term_res = session.stop();
    EXPECT_TRUE(term_res.has_value());
    EXPECT_EQ(session.state(), lddm::SessionState::STOPPED);
    EXPECT_FALSE(comp->is_running());
    EXPECT_FALSE(desk->is_running());
}

TEST_CASE(Session_InvalidTransitionRejected) {
    lddm::SessionConfig cfg{
        .id = lddm::SessionId("session-invalid-trans"),
        .base_runtime_dir = "/tmp/lddm_test_runtime"
    };
    lddm::Session session(cfg);

    // Direct jump from CREATED to STOPPED is illegal
    auto res = session.transition_to(lddm::SessionState::STOPPED);
    EXPECT_FALSE(res.has_value());
    EXPECT_EQ(res.error().code(), lddm::ErrorCode::SessionInvalidState);
    EXPECT_EQ(session.state(), lddm::SessionState::CREATED);
}

TEST_MAIN()
