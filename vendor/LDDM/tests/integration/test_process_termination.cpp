#include "test_framework.hpp"
#include "lddm/process/process.hpp"
#include <csignal>
#include <thread>

TEST_CASE(ProcessTermination_GracefulSIGTERM) {
    lddm::ProcessSpec spec{
        .name = "test-sleep",
        .executable = "/bin/sleep",
        .arguments = {"60"},
        .stop_timeout = std::chrono::milliseconds{2000}
    };

    lddm::Process proc(spec);
    auto start_res = proc.start();
    EXPECT_TRUE(start_res.has_value());
    EXPECT_TRUE(proc.is_running());

    auto stop_res = proc.stop(std::chrono::milliseconds{1000});
    EXPECT_TRUE(stop_res.has_value());
    EXPECT_TRUE(proc.is_terminated());

    auto exit_info = proc.exit_info();
    EXPECT_TRUE(exit_info.has_value());
    if (exit_info) {
        EXPECT_TRUE(exit_info->signaled);
        EXPECT_EQ(exit_info->term_signal, SIGTERM);
    }
}

TEST_CASE(ProcessTermination_EscalationToSIGKILL) {
    // A process that traps and ignores SIGTERM
    lddm::ProcessSpec spec{
        .name = "test-traps-sigterm",
        .executable = "/bin/sh",
        .arguments = {"-c", "trap '' TERM; while true; do sleep 1; done"},
        .stop_timeout = std::chrono::milliseconds{300},
        .kill_timeout = std::chrono::milliseconds{1000}
    };

    lddm::Process proc(spec);
    auto start_res = proc.start();
    EXPECT_TRUE(start_res.has_value());
    EXPECT_TRUE(proc.is_running());

    // Give shell a moment to execute trap command
    std::this_thread::sleep_for(std::chrono::milliseconds{50});

    // Stop will try SIGTERM, time out in 300ms, and escalate to SIGKILL
    auto stop_res = proc.stop(std::chrono::milliseconds{300});
    EXPECT_TRUE(stop_res.has_value());
    EXPECT_TRUE(proc.is_terminated());

    auto exit_info = proc.exit_info();
    EXPECT_TRUE(exit_info.has_value());
    if (exit_info) {
        EXPECT_TRUE(exit_info->signaled);
        EXPECT_EQ(exit_info->term_signal, SIGKILL);
    }
}

TEST_MAIN()

