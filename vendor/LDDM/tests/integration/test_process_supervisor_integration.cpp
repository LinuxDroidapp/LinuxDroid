#include "test_framework.hpp"
#include "lddm/process/process_supervisor.hpp"
#include <thread>

TEST_CASE(ProcessSupervisor_StartMultipleAndStopAll) {
    lddm::ProcessSupervisor supervisor;

    lddm::ProcessSpec s1{.name = "worker-1", .executable = "/bin/sleep", .arguments = {"10"}};
    lddm::ProcessSpec s2{.name = "worker-2", .executable = "/bin/sleep", .arguments = {"10"}};
    lddm::ProcessSpec s3{.name = "worker-3", .executable = "/bin/sleep", .arguments = {"10"}};

    auto p1 = supervisor.start_process(s1);
    auto p2 = supervisor.start_process(s2);
    auto p3 = supervisor.start_process(s3);

    EXPECT_TRUE(p1.has_value());
    EXPECT_TRUE(p2.has_value());
    EXPECT_TRUE(p3.has_value());

    EXPECT_EQ(supervisor.total_process_count(), 3);
    EXPECT_EQ(supervisor.active_process_count(), 3);

    // Stop one individually
    if (p1.has_value()) {
        auto stop1 = supervisor.stop_process(p1.value());
        EXPECT_TRUE(stop1.has_value());
        EXPECT_FALSE(p1.value()->is_running());
    }

    EXPECT_EQ(supervisor.active_process_count(), 2);

    // Stop all remaining
    auto stop_all_res = supervisor.stop_all(std::chrono::milliseconds{1000}, std::chrono::milliseconds{500});
    EXPECT_TRUE(stop_all_res.has_value());
    EXPECT_EQ(supervisor.total_process_count(), 0);
    EXPECT_EQ(supervisor.active_process_count(), 0);
}

TEST_CASE(ProcessSupervisor_ReapingExited) {
    lddm::ProcessSupervisor supervisor;

    lddm::ProcessSpec quick_proc{
        .name = "quick-exit",
        .executable = "/bin/sh",
        .arguments = {"-c", "exit 0"}
    };

    auto p = supervisor.start_process(quick_proc);
    EXPECT_TRUE(p.has_value());

    if (p.has_value()) {
        // Wait for it to finish so reap_exited_processes can reap it
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }

    auto reaped = supervisor.reap_exited_processes();
    // At least the quick process is reaped
    EXPECT_TRUE(!reaped.empty());
}

TEST_MAIN()

