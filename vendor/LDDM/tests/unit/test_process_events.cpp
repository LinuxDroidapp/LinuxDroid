#include "test_framework.hpp"
#include "lddm/process/process_supervisor.hpp"

TEST_CASE(ProcessEvents_ListenerInvoked) {
    lddm::ProcessSupervisor supervisor;
    std::vector<lddm::ProcessEventType> received_events;

    supervisor.register_listener([&](const lddm::ProcessEvent& ev) {
        received_events.push_back(ev.type);
    });

    lddm::ProcessSpec spec{
        .name = "event-test-proc",
        .executable = "/bin/true"
    };

    auto start_res = supervisor.start_process(spec);
    EXPECT_TRUE(start_res.has_value());

    // Allow process to finish
    if (start_res.has_value()) {
        (void)start_res.value()->wait();
    }

    // Reap process
    (void)supervisor.reap_exited_processes();

    EXPECT_TRUE(received_events.size() >= 2);
    EXPECT_EQ(received_events[0], lddm::ProcessEventType::Started);
    EXPECT_EQ(received_events[1], lddm::ProcessEventType::Exited);
}

TEST_MAIN()

