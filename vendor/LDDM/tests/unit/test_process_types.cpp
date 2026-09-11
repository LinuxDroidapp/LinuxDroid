#include "test_framework.hpp"
#include "lddm/process/process_types.hpp"

TEST_CASE(ProcessTypes_StateToString) {
    EXPECT_EQ(lddm::to_string(lddm::ProcessState::CREATED), "CREATED");
    EXPECT_EQ(lddm::to_string(lddm::ProcessState::STARTING), "STARTING");
    EXPECT_EQ(lddm::to_string(lddm::ProcessState::RUNNING), "RUNNING");
    EXPECT_EQ(lddm::to_string(lddm::ProcessState::STOPPING), "STOPPING");
    EXPECT_EQ(lddm::to_string(lddm::ProcessState::EXITED), "EXITED");
    EXPECT_EQ(lddm::to_string(lddm::ProcessState::FAILED), "FAILED");
}

TEST_CASE(ProcessTypes_ValidTransitions) {
    // Normal lifecycle
    EXPECT_TRUE(lddm::is_valid_process_transition(lddm::ProcessState::CREATED, lddm::ProcessState::STARTING));
    EXPECT_TRUE(lddm::is_valid_process_transition(lddm::ProcessState::STARTING, lddm::ProcessState::RUNNING));
    EXPECT_TRUE(lddm::is_valid_process_transition(lddm::ProcessState::RUNNING, lddm::ProcessState::STOPPING));
    EXPECT_TRUE(lddm::is_valid_process_transition(lddm::ProcessState::STOPPING, lddm::ProcessState::EXITED));

    // Direct exit from running (process terminates on its own)
    EXPECT_TRUE(lddm::is_valid_process_transition(lddm::ProcessState::RUNNING, lddm::ProcessState::EXITED));

    // Abort from starting to stopping
    EXPECT_TRUE(lddm::is_valid_process_transition(lddm::ProcessState::STARTING, lddm::ProcessState::STOPPING));

    // Failures
    EXPECT_TRUE(lddm::is_valid_process_transition(lddm::ProcessState::CREATED, lddm::ProcessState::FAILED));
    EXPECT_TRUE(lddm::is_valid_process_transition(lddm::ProcessState::STARTING, lddm::ProcessState::FAILED));
    EXPECT_TRUE(lddm::is_valid_process_transition(lddm::ProcessState::RUNNING, lddm::ProcessState::FAILED));
    EXPECT_TRUE(lddm::is_valid_process_transition(lddm::ProcessState::STOPPING, lddm::ProcessState::FAILED));

    // Cleanup after failure
    EXPECT_TRUE(lddm::is_valid_process_transition(lddm::ProcessState::FAILED, lddm::ProcessState::EXITED));
}

TEST_CASE(ProcessTypes_InvalidTransitions) {
    // Cannot skip directly from CREATED to EXITED
    EXPECT_FALSE(lddm::is_valid_process_transition(lddm::ProcessState::CREATED, lddm::ProcessState::EXITED));

    // Cannot transition backwards from RUNNING to STARTING
    EXPECT_FALSE(lddm::is_valid_process_transition(lddm::ProcessState::RUNNING, lddm::ProcessState::STARTING));

    // Cannot transition from STOPPING back to RUNNING
    EXPECT_FALSE(lddm::is_valid_process_transition(lddm::ProcessState::STOPPING, lddm::ProcessState::RUNNING));

    // EXITED is terminal
    EXPECT_FALSE(lddm::is_valid_process_transition(lddm::ProcessState::EXITED, lddm::ProcessState::RUNNING));
    EXPECT_FALSE(lddm::is_valid_process_transition(lddm::ProcessState::EXITED, lddm::ProcessState::STARTING));
}

TEST_CASE(ProcessTypes_ExitInfoFormatting) {
    lddm::ProcessExitInfo normal_exit{
        .pid = 1234,
        .exited_normally = true,
        .exit_code = 0
    };
    EXPECT_TRUE(normal_exit.success());
    EXPECT_TRUE(normal_exit.format().find("exited=0") != std::string::npos);

    lddm::ProcessExitInfo error_exit{
        .pid = 1234,
        .exited_normally = true,
        .exit_code = 2
    };
    EXPECT_FALSE(error_exit.success());
    EXPECT_TRUE(error_exit.format().find("exited=2") != std::string::npos);

    lddm::ProcessExitInfo signal_exit{
        .pid = 1234,
        .signaled = true,
        .term_signal = 15, // SIGTERM
        .core_dumped = false
    };
    EXPECT_FALSE(signal_exit.success());
    EXPECT_TRUE(signal_exit.format().find("signaled=15") != std::string::npos);
}

TEST_CASE(ProcessTypes_StreamPolicy) {
    EXPECT_EQ(lddm::to_string(lddm::StreamPolicy::Inherit), "Inherit");
    EXPECT_EQ(lddm::to_string(lddm::StreamPolicy::Null), "Null");
    EXPECT_EQ(lddm::to_string(lddm::StreamPolicy::Close), "Close");
    EXPECT_EQ(lddm::to_string(lddm::StreamPolicy::File), "File");
    EXPECT_EQ(lddm::to_string(lddm::StreamPolicy::Pipe), "Pipe");
}

TEST_MAIN()

