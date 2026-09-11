#include "test_framework.hpp"
#include "lddm/weston/weston_types.hpp"

using namespace lddm::weston;

TEST_CASE(WestonTypes_StateTransitions) {
    // Valid forward transitions
    EXPECT_TRUE(is_valid_weston_transition(WestonState::Created, WestonState::Preparing));
    EXPECT_TRUE(is_valid_weston_transition(WestonState::Created, WestonState::Stopping));
    EXPECT_TRUE(is_valid_weston_transition(WestonState::Preparing, WestonState::Starting));
    EXPECT_TRUE(is_valid_weston_transition(WestonState::Preparing, WestonState::Stopping));
    EXPECT_TRUE(is_valid_weston_transition(WestonState::Starting, WestonState::WaitingReady));
    EXPECT_TRUE(is_valid_weston_transition(WestonState::Starting, WestonState::Stopping));
    EXPECT_TRUE(is_valid_weston_transition(WestonState::WaitingReady, WestonState::Running));
    EXPECT_TRUE(is_valid_weston_transition(WestonState::Running, WestonState::Stopping));
    EXPECT_TRUE(is_valid_weston_transition(WestonState::Stopping, WestonState::Stopped));

    // Valid failure transitions
    EXPECT_TRUE(is_valid_weston_transition(WestonState::Created, WestonState::Failed));
    EXPECT_TRUE(is_valid_weston_transition(WestonState::Preparing, WestonState::Failed));
    EXPECT_TRUE(is_valid_weston_transition(WestonState::Starting, WestonState::Failed));
    EXPECT_TRUE(is_valid_weston_transition(WestonState::WaitingReady, WestonState::Failed));
    EXPECT_TRUE(is_valid_weston_transition(WestonState::Running, WestonState::Failed));
    EXPECT_TRUE(is_valid_weston_transition(WestonState::Stopping, WestonState::Failed));

    // Recovery from failed
    EXPECT_TRUE(is_valid_weston_transition(WestonState::Failed, WestonState::Stopped));

    // Identity transitions
    EXPECT_TRUE(is_valid_weston_transition(WestonState::Created, WestonState::Created));
    EXPECT_TRUE(is_valid_weston_transition(WestonState::Running, WestonState::Running));
    EXPECT_TRUE(is_valid_weston_transition(WestonState::Stopped, WestonState::Stopped));

    // Invalid transitions
    EXPECT_FALSE(is_valid_weston_transition(WestonState::Created, WestonState::Running));
    EXPECT_FALSE(is_valid_weston_transition(WestonState::Created, WestonState::Stopped));
    EXPECT_FALSE(is_valid_weston_transition(WestonState::Starting, WestonState::Stopped));
    EXPECT_FALSE(is_valid_weston_transition(WestonState::Stopped, WestonState::Running));
    EXPECT_FALSE(is_valid_weston_transition(WestonState::Stopped, WestonState::Preparing));
}

TEST_CASE(WestonTypes_StateStrings) {
    EXPECT_EQ(to_string(WestonState::Created), "CREATED");
    EXPECT_EQ(to_string(WestonState::Preparing), "PREPARING");
    EXPECT_EQ(to_string(WestonState::Starting), "STARTING");
    EXPECT_EQ(to_string(WestonState::WaitingReady), "WAITING_READY");
    EXPECT_EQ(to_string(WestonState::Running), "RUNNING");
    EXPECT_EQ(to_string(WestonState::Stopping), "STOPPING");
    EXPECT_EQ(to_string(WestonState::Stopped), "STOPPED");
    EXPECT_EQ(to_string(WestonState::Failed), "FAILED");
}

TEST_MAIN()
