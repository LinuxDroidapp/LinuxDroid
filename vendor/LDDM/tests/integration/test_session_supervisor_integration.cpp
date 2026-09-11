#include "test_framework.hpp"
#include "lddm/session/session.hpp"

TEST_CASE(SessionSupervisor_FullLifecycleOrchestration) {
    lddm::SessionConfig cfg{
        .id = lddm::SessionId("session-sup-test"),
        .type = lddm::SessionType::Wayland,
        .user = "root",
        .base_runtime_dir = "/tmp/lddm_session_sup_test"
    };

    lddm::Session session(cfg);
    auto init_res = session.initialize();
    EXPECT_TRUE(init_res.has_value());

    auto supervisor = session.supervisor();
    EXPECT_TRUE(supervisor != nullptr);

    // Launch a child process under the session's supervisor
    lddm::ProcessSpec spec{
        .name = "session-worker",
        .executable = "/bin/sleep",
        .arguments = {"60"}
    };

    auto proc_res = supervisor->start_process(spec);
    EXPECT_TRUE(proc_res.has_value());
    EXPECT_EQ(supervisor->active_process_count(), 1);

    auto start_res = session.start();
    EXPECT_TRUE(start_res.has_value());
    EXPECT_EQ(session.state(), lddm::SessionState::RUNNING);

    // Stopping session must cleanly stop and reap all supervised processes
    auto stop_res = session.stop();
    EXPECT_TRUE(stop_res.has_value());
    EXPECT_EQ(session.state(), lddm::SessionState::STOPPED);

    // Verify supervisor has reaped everything
    EXPECT_EQ(supervisor->active_process_count(), 0);
    EXPECT_EQ(supervisor->total_process_count(), 0);
}

TEST_MAIN()

