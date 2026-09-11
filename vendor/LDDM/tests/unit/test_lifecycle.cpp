#include "test_framework.hpp"
#include "lddm/core/lifecycle.hpp"
#include <vector>

TEST_CASE(Lifecycle_InitialState) {
    lddm::LifecycleStateMachine sm;
    EXPECT_EQ(sm.state(), lddm::LifecycleState::CREATED);
    EXPECT_FALSE(sm.is_running());
    EXPECT_FALSE(sm.is_terminal());
}

TEST_CASE(Lifecycle_ValidFullSequence) {
    lddm::LifecycleStateMachine sm;
    std::vector<std::pair<lddm::LifecycleState, lddm::LifecycleState>> transitions;

    sm.register_observer([&](const lddm::StateTransitionEvent& ev) {
        transitions.emplace_back(ev.from, ev.to);
    });

    EXPECT_TRUE(sm.transition_to(lddm::LifecycleState::INITIALIZING, "init").has_value());
    EXPECT_EQ(sm.state(), lddm::LifecycleState::INITIALIZING);

    EXPECT_TRUE(sm.transition_to(lddm::LifecycleState::READY, "ready").has_value());
    EXPECT_EQ(sm.state(), lddm::LifecycleState::READY);

    EXPECT_TRUE(sm.transition_to(lddm::LifecycleState::STARTING, "start").has_value());
    EXPECT_EQ(sm.state(), lddm::LifecycleState::STARTING);

    EXPECT_TRUE(sm.transition_to(lddm::LifecycleState::RUNNING, "run").has_value());
    EXPECT_EQ(sm.state(), lddm::LifecycleState::RUNNING);
    EXPECT_TRUE(sm.is_running());

    EXPECT_TRUE(sm.transition_to(lddm::LifecycleState::STOPPING, "stop").has_value());
    EXPECT_EQ(sm.state(), lddm::LifecycleState::STOPPING);

    EXPECT_TRUE(sm.transition_to(lddm::LifecycleState::STOPPED, "done").has_value());
    EXPECT_EQ(sm.state(), lddm::LifecycleState::STOPPED);
    EXPECT_TRUE(sm.is_terminal());

    EXPECT_EQ(transitions.size(), 6u);
    EXPECT_EQ(transitions[0].first, lddm::LifecycleState::CREATED);
    EXPECT_EQ(transitions[0].second, lddm::LifecycleState::INITIALIZING);
    EXPECT_EQ(transitions[5].first, lddm::LifecycleState::STOPPING);
    EXPECT_EQ(transitions[5].second, lddm::LifecycleState::STOPPED);
}

TEST_CASE(Lifecycle_InvalidTransitionsRejected) {
    lddm::LifecycleStateMachine sm;

    // Direct jump from CREATED to RUNNING is illegal
    auto res1 = sm.transition_to(lddm::LifecycleState::RUNNING, "illegal jump");
    EXPECT_FALSE(res1.has_value());
    EXPECT_EQ(res1.error().code(), lddm::ErrorCode::InternalInvalidState);
    EXPECT_EQ(sm.state(), lddm::LifecycleState::CREATED);

    // Direct jump from CREATED to STOPPED is illegal
    auto res2 = sm.transition_to(lddm::LifecycleState::STOPPED, "illegal jump");
    EXPECT_FALSE(res2.has_value());
    EXPECT_EQ(sm.state(), lddm::LifecycleState::CREATED);
}

TEST_CASE(Lifecycle_FailureTransitions) {
    lddm::LifecycleStateMachine sm;
    (void)sm.transition_to(lddm::LifecycleState::INITIALIZING);

    // Failure during init
    auto fail_res = sm.transition_to(lddm::LifecycleState::FAILED, "init error");
    EXPECT_TRUE(fail_res.has_value());
    EXPECT_EQ(sm.state(), lddm::LifecycleState::FAILED);
    EXPECT_TRUE(sm.is_terminal());

    // Recovery restart allowed from FAILED
    auto recover_res = sm.transition_to(lddm::LifecycleState::INITIALIZING, "recovery");
    EXPECT_TRUE(recover_res.has_value());
    EXPECT_EQ(sm.state(), lddm::LifecycleState::INITIALIZING);
}

TEST_MAIN()

