#include "test_framework.hpp"
#include "lddm/recovery/recovery_types.hpp"

using namespace lddm::recovery;

TEST_CASE(RecoveryTypes_StateTransitions) {
    // Valid transitions
    EXPECT_TRUE(is_valid_recovery_transition(RecoveryState::Idle, RecoveryState::Recovering));
    EXPECT_TRUE(is_valid_recovery_transition(RecoveryState::Idle, RecoveryState::Failed));
    EXPECT_TRUE(is_valid_recovery_transition(RecoveryState::Recovering, RecoveryState::Verifying));
    EXPECT_TRUE(is_valid_recovery_transition(RecoveryState::Recovering, RecoveryState::Failed));
    EXPECT_TRUE(is_valid_recovery_transition(RecoveryState::Verifying, RecoveryState::Recovered));
    EXPECT_TRUE(is_valid_recovery_transition(RecoveryState::Verifying, RecoveryState::Failed));
    EXPECT_TRUE(is_valid_recovery_transition(RecoveryState::Recovered, RecoveryState::Idle));
    EXPECT_TRUE(is_valid_recovery_transition(RecoveryState::Recovered, RecoveryState::Recovering));
    EXPECT_TRUE(is_valid_recovery_transition(RecoveryState::Failed, RecoveryState::Idle));
    EXPECT_TRUE(is_valid_recovery_transition(RecoveryState::Failed, RecoveryState::Recovering));

    // Identity transitions
    EXPECT_TRUE(is_valid_recovery_transition(RecoveryState::Idle, RecoveryState::Idle));
    EXPECT_TRUE(is_valid_recovery_transition(RecoveryState::Recovering, RecoveryState::Recovering));
    EXPECT_TRUE(is_valid_recovery_transition(RecoveryState::Recovered, RecoveryState::Recovered));

    // Invalid transitions
    EXPECT_FALSE(is_valid_recovery_transition(RecoveryState::Idle, RecoveryState::Verifying));
    EXPECT_FALSE(is_valid_recovery_transition(RecoveryState::Idle, RecoveryState::Recovered));
    EXPECT_FALSE(is_valid_recovery_transition(RecoveryState::Recovering, RecoveryState::Idle));
    EXPECT_FALSE(is_valid_recovery_transition(RecoveryState::Recovering, RecoveryState::Recovered));
}

TEST_CASE(RecoveryTypes_StateStrings) {
    EXPECT_EQ(to_string(RecoveryState::Idle), "IDLE");
    EXPECT_EQ(to_string(RecoveryState::Recovering), "RECOVERING");
    EXPECT_EQ(to_string(RecoveryState::Verifying), "VERIFYING");
    EXPECT_EQ(to_string(RecoveryState::Recovered), "RECOVERED");
    EXPECT_EQ(to_string(RecoveryState::Failed), "FAILED");
}

TEST_CASE(RecoveryTypes_ReasonStrings) {
    EXPECT_EQ(to_string(RecoveryReason::WestonStartFailure), "WESTON_START_FAILURE");
    EXPECT_EQ(to_string(RecoveryReason::WestonUnexpectedExit), "WESTON_UNEXPECTED_EXIT");
    EXPECT_EQ(to_string(RecoveryReason::WestonReadinessTimeout), "WESTON_READINESS_TIMEOUT");
    EXPECT_EQ(to_string(RecoveryReason::LddeStartFailure), "LDDE_START_FAILURE");
    EXPECT_EQ(to_string(RecoveryReason::LddeUnexpectedExit), "LDDE_UNEXPECTED_EXIT");
    EXPECT_EQ(to_string(RecoveryReason::LddeReadinessTimeout), "LDDE_READINESS_TIMEOUT");
    EXPECT_EQ(to_string(RecoveryReason::ProcessFailure), "PROCESS_FAILURE");
    EXPECT_EQ(to_string(RecoveryReason::SessionStartFailure), "SESSION_START_FAILURE");
    EXPECT_EQ(to_string(RecoveryReason::SessionRuntimeFailure), "SESSION_RUNTIME_FAILURE");
    EXPECT_EQ(to_string(RecoveryReason::SessionShutdownFailure), "SESSION_SHUTDOWN_FAILURE");
    EXPECT_EQ(to_string(RecoveryReason::RuntimeResourceFailure), "RUNTIME_RESOURCE_FAILURE");
    EXPECT_EQ(to_string(RecoveryReason::Unknown), "UNKNOWN");
}

TEST_CASE(RecoveryTypes_PolicyStrings) {
    EXPECT_EQ(to_string(RecoveryPolicy::NoRecovery), "NO_RECOVERY");
    EXPECT_EQ(to_string(RecoveryPolicy::ComponentRestart), "COMPONENT_RESTART");
    EXPECT_EQ(to_string(RecoveryPolicy::SessionRestart), "SESSION_RESTART");
    EXPECT_EQ(to_string(RecoveryPolicy::FailSession), "FAIL_SESSION");
}

TEST_MAIN()
