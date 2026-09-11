#include "test_framework.hpp"
#include "lddm/ldde/ldde_types.hpp"

using namespace lddm::ldde;

TEST_CASE(LddeTypes_StateTransitions) {
    // Valid forward transitions
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::Created, LddeState::Preparing));
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::Created, LddeState::Stopping));
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::Preparing, LddeState::Starting));
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::Preparing, LddeState::Stopping));
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::Starting, LddeState::WaitingReady));
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::Starting, LddeState::Stopping));
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::WaitingReady, LddeState::Running));
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::WaitingReady, LddeState::Stopping));
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::Running, LddeState::Stopping));
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::Stopping, LddeState::Stopped));

    // Valid failure transitions
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::Created, LddeState::Failed));
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::Preparing, LddeState::Failed));
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::Starting, LddeState::Failed));
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::WaitingReady, LddeState::Failed));
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::Running, LddeState::Failed));
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::Stopping, LddeState::Failed));

    // Recovery from failed
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::Failed, LddeState::Stopped));
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::Failed, LddeState::Stopping));

    // Identity transitions
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::Created, LddeState::Created));
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::Running, LddeState::Running));
    EXPECT_TRUE(is_valid_ldde_transition(LddeState::Stopped, LddeState::Stopped));

    // Invalid transitions
    EXPECT_FALSE(is_valid_ldde_transition(LddeState::Created, LddeState::Running));
    EXPECT_FALSE(is_valid_ldde_transition(LddeState::Created, LddeState::Stopped));
    EXPECT_FALSE(is_valid_ldde_transition(LddeState::Starting, LddeState::Stopped));
    EXPECT_FALSE(is_valid_ldde_transition(LddeState::Stopped, LddeState::Running));
    EXPECT_FALSE(is_valid_ldde_transition(LddeState::Stopped, LddeState::Preparing));
}

TEST_CASE(LddeTypes_StateStrings) {
    EXPECT_EQ(to_string(LddeState::Created), "CREATED");
    EXPECT_EQ(to_string(LddeState::Preparing), "PREPARING");
    EXPECT_EQ(to_string(LddeState::Starting), "STARTING");
    EXPECT_EQ(to_string(LddeState::WaitingReady), "WAITING_READY");
    EXPECT_EQ(to_string(LddeState::Running), "RUNNING");
    EXPECT_EQ(to_string(LddeState::Stopping), "STOPPING");
    EXPECT_EQ(to_string(LddeState::Stopped), "STOPPED");
    EXPECT_EQ(to_string(LddeState::Failed), "FAILED");

    EXPECT_EQ(to_string(LddeReadinessMode::File), "File");
    EXPECT_EQ(to_string(LddeReadinessMode::Socket), "Socket");
}

TEST_MAIN()
