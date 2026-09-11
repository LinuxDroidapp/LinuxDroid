#include "test_framework.hpp"
#include "lddm/session/session_state.hpp"
#include <vector>

TEST_CASE(SessionState_ToString) {
    EXPECT_EQ(lddm::to_string(lddm::SessionState::CREATED), "CREATED");
    EXPECT_EQ(lddm::to_string(lddm::SessionState::INITIALIZING), "INITIALIZING");
    EXPECT_EQ(lddm::to_string(lddm::SessionState::READY), "READY");
    EXPECT_EQ(lddm::to_string(lddm::SessionState::STARTING), "STARTING");
    EXPECT_EQ(lddm::to_string(lddm::SessionState::RUNNING), "RUNNING");
    EXPECT_EQ(lddm::to_string(lddm::SessionState::STOPPING), "STOPPING");
    EXPECT_EQ(lddm::to_string(lddm::SessionState::STOPPED), "STOPPED");
    EXPECT_EQ(lddm::to_string(lddm::SessionState::FAILED), "FAILED");
}

TEST_CASE(SessionState_ValidSequence) {
    lddm::SessionStateMachine sm("test-sm");
    EXPECT_EQ(sm.state(), lddm::SessionState::CREATED);
    EXPECT_FALSE(sm.is_running());
    EXPECT_FALSE(sm.is_terminal());

    std::vector<lddm::SessionState> recorded_states;
    sm.register_observer([&](const lddm::SessionTransitionEvent& ev) {
        recorded_states.push_back(ev.to);
    });

    EXPECT_TRUE(sm.transition_to(lddm::SessionState::INITIALIZING, "init").has_value());
    EXPECT_TRUE(sm.transition_to(lddm::SessionState::READY, "ready").has_value());
    EXPECT_TRUE(sm.transition_to(lddm::SessionState::STARTING, "start").has_value());
    EXPECT_TRUE(sm.transition_to(lddm::SessionState::RUNNING, "running").has_value());
    EXPECT_TRUE(sm.is_running());
    EXPECT_TRUE(sm.is_active());

    EXPECT_TRUE(sm.transition_to(lddm::SessionState::STOPPING, "stopping").has_value());
    EXPECT_TRUE(sm.transition_to(lddm::SessionState::STOPPED, "stopped").has_value());
    EXPECT_TRUE(sm.is_terminal());

    EXPECT_EQ(recorded_states.size(), 6u);
    EXPECT_EQ(recorded_states[0], lddm::SessionState::INITIALIZING);
    EXPECT_EQ(recorded_states[3], lddm::SessionState::RUNNING);
    EXPECT_EQ(recorded_states[5], lddm::SessionState::STOPPED);
}

TEST_CASE(SessionState_FailureTransitions) {
    // Failure from INITIALIZING
    {
        lddm::SessionStateMachine sm("test-fail-1");
        (void)sm.transition_to(lddm::SessionState::INITIALIZING);
        EXPECT_TRUE(sm.transition_to(lddm::SessionState::FAILED, "error during init").has_value());
        EXPECT_EQ(sm.state(), lddm::SessionState::FAILED);
        EXPECT_TRUE(sm.is_terminal());
    }

    // Failure from RUNNING
    {
        lddm::SessionStateMachine sm("test-fail-2");
        (void)sm.transition_to(lddm::SessionState::INITIALIZING);
        (void)sm.transition_to(lddm::SessionState::READY);
        (void)sm.transition_to(lddm::SessionState::STARTING);
        (void)sm.transition_to(lddm::SessionState::RUNNING);
        EXPECT_TRUE(sm.transition_to(lddm::SessionState::FAILED, "compositor crash").has_value());
        EXPECT_EQ(sm.state(), lddm::SessionState::FAILED);
    }
}

TEST_CASE(SessionState_InvalidTransitions) {
    lddm::SessionStateMachine sm("test-invalid");

    // CREATED -> RUNNING is invalid
    auto res1 = sm.transition_to(lddm::SessionState::RUNNING);
    EXPECT_FALSE(res1.has_value());
    EXPECT_EQ(res1.error().code(), lddm::ErrorCode::SessionInvalidState);
    EXPECT_EQ(sm.state(), lddm::SessionState::CREATED);

    // CREATED -> STOPPED is invalid
    auto res2 = sm.transition_to(lddm::SessionState::STOPPED);
    EXPECT_FALSE(res2.has_value());
    EXPECT_EQ(sm.state(), lddm::SessionState::CREATED);
}

TEST_MAIN()

