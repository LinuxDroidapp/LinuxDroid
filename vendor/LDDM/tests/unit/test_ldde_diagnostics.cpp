#include "test_framework.hpp"
#include "lddm/ldde/ldde_diagnostics.hpp"
#include "lddm/core/error.hpp"
#include <thread>

using namespace lddm::ldde;

TEST_CASE(LddeDiagnostics_RecordTransitions) {
    LddeDiagnostics diag;
    EXPECT_TRUE(diag.transitions().empty());

    diag.record_transition(LddeState::Created, LddeState::Preparing, "Init");
    diag.record_transition(LddeState::Preparing, LddeState::Starting, "Spawn");
    diag.record_transition(LddeState::Starting, LddeState::Running, "Ready");

    auto trs = diag.transitions();
    EXPECT_EQ(trs.size(), 3);
    EXPECT_EQ(trs[0].from, LddeState::Created);
    EXPECT_EQ(trs[0].to, LddeState::Preparing);
    EXPECT_EQ(trs[0].reason, "Init");
    EXPECT_EQ(trs[2].to, LddeState::Running);
}

TEST_CASE(LddeDiagnostics_TimestampsAndDurations) {
    LddeDiagnostics diag;

    diag.record_start_time();
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
    diag.record_ready_time();
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
    diag.record_stop_time();

    EXPECT_TRUE(diag.start_time().has_value());
    EXPECT_TRUE(diag.ready_time().has_value());
    EXPECT_TRUE(diag.stop_time().has_value());

    EXPECT_TRUE(diag.startup_duration().count() >= 5);
    EXPECT_TRUE(diag.active_duration().count() >= 15);
}

TEST_CASE(LddeDiagnostics_ProcessMetadataAndErrors) {
    LddeDiagnostics diag;

    diag.set_executable("/usr/bin/ldde-session");
    diag.set_session_target("default");
    diag.set_readiness_path("/tmp/ready");
    diag.set_log_path("/tmp/ldde.log");
    diag.set_pid(12345);

    EXPECT_EQ(diag.executable(), "/usr/bin/ldde-session");
    EXPECT_EQ(diag.session_target(), "default");
    EXPECT_EQ(diag.readiness_path(), "/tmp/ready");
    EXPECT_EQ(diag.log_path(), "/tmp/ldde.log");
    EXPECT_EQ(diag.pid().value_or(0), 12345);

    lddm::Error err(lddm::ErrorCategory::Desktop, lddm::ErrorCode::LddeCrash, "Crash test");
    diag.record_error(err);

    EXPECT_EQ(diag.errors().size(), 1);
    EXPECT_TRUE(diag.last_error().has_value());
    EXPECT_EQ(diag.last_error()->code(), lddm::ErrorCode::LddeCrash);

    std::string report = diag.format_report();
    EXPECT_FALSE(report.empty());
}

TEST_MAIN()
