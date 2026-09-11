#include "test_framework.hpp"
#include "lddm/session/session_diagnostics.hpp"
#include "lddm/session/session_config.hpp"
#include "lddm/session/session_paths.hpp"
#include "lddm/session/session_environment.hpp"

TEST_CASE(SessionDiagnostics_RecordingAndReport) {
    lddm::SessionId id("sess-diag-test");
    lddm::SessionDiagnostics diag(id);

    EXPECT_EQ(diag.id().str(), "sess-diag-test");
    EXPECT_FALSE(diag.last_error().has_value());

    // Record transition
    diag.record_state_transition(lddm::SessionTransitionEvent{
        .from = lddm::SessionState::CREATED,
        .to = lddm::SessionState::INITIALIZING,
        .reason = "Booting",
        .timestamp = lddm::SystemClock::now()
    });
    EXPECT_EQ(diag.transition_history().size(), 1u);

    // Record error
    diag.record_error(lddm::Error(
        lddm::ErrorCategory::Session,
        lddm::ErrorCode::SessionStartFailed,
        "Diagnostic error sample",
        "context_info"));
    EXPECT_TRUE(diag.last_error().has_value());
    EXPECT_EQ(diag.last_error()->code(), lddm::ErrorCode::SessionStartFailed);

    // Format report
    lddm::SessionConfig cfg{
        .id = id,
        .type = lddm::SessionType::Wayland,
        .user = "diaguser"
    };
    lddm::SessionPaths paths(id, "/tmp/lddm_diag_tests");
    lddm::SessionEnvironment env;
    env.set("SECRET_PASSWORD", "should_be_hidden");

    std::string report = diag.format_report(cfg, paths, env, lddm::SessionState::FAILED, 2, 1);
    EXPECT_FALSE(report.empty());
    EXPECT_TRUE(report.find("sess-diag-test") != std::string::npos);
    EXPECT_TRUE(report.find("FAILED") != std::string::npos);
    EXPECT_TRUE(report.find("Diagnostic error sample") != std::string::npos);
    EXPECT_TRUE(report.find("[REDACTED]") != std::string::npos);
}

TEST_MAIN()

