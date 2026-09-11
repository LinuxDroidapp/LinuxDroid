#include "test_framework.hpp"
#include "lddm/recovery/recovery_diagnostics.hpp"

using namespace lddm;
using namespace lddm::recovery;

TEST_CASE(RecoveryDiagnostics_RecordingAndMetrics) {
    RecoveryDiagnostics diag("session-diag-1");
    EXPECT_EQ(diag.session_id(), "session-diag-1");
    EXPECT_EQ(diag.current_state(), RecoveryState::Idle);
    EXPECT_EQ(diag.total_attempts(), 0u);
    EXPECT_EQ(diag.successful_recoveries(), 0u);
    EXPECT_EQ(diag.failed_recoveries(), 0u);

    // Record start
    diag.record_recovery_start(1, "ldde", RecoveryReason::LddeUnexpectedExit, RecoveryPolicy::ComponentRestart, 1);
    EXPECT_EQ(diag.total_attempts(), 1u);
    EXPECT_EQ(diag.current_state(), RecoveryState::Recovering);
    EXPECT_EQ(diag.last_component(), "ldde");
    EXPECT_EQ(diag.last_reason(), RecoveryReason::LddeUnexpectedExit);

    // Record success
    diag.record_recovery_success(1, "ldde", RecoveryReason::LddeUnexpectedExit, RecoveryPolicy::ComponentRestart, 1, std::chrono::milliseconds(45));
    EXPECT_EQ(diag.successful_recoveries(), 1u);
    EXPECT_EQ(diag.current_state(), RecoveryState::Recovered);

    auto hist = diag.history();
    EXPECT_EQ(hist.size(), 1u);
    EXPECT_EQ(hist[0].recovery_id, 1u);
    EXPECT_EQ(hist[0].component, "ldde");
    EXPECT_EQ(hist[0].duration_ms, 45u);
    EXPECT_EQ(hist[0].final_state, RecoveryState::Recovered);

    // Record failure
    Error err(ErrorCategory::Recovery, ErrorCode::RecoveryTimeout, "Timeout waiting for ready");
    diag.record_recovery_start(2, "weston", RecoveryReason::WestonReadinessTimeout, RecoveryPolicy::ComponentRestart, 2);
    diag.record_recovery_failure(2, "weston", RecoveryReason::WestonReadinessTimeout, RecoveryPolicy::ComponentRestart, 2, err, std::chrono::milliseconds(100));

    EXPECT_EQ(diag.total_attempts(), 2u);
    EXPECT_EQ(diag.failed_recoveries(), 1u);
    EXPECT_EQ(diag.current_state(), RecoveryState::Failed);
    EXPECT_EQ(diag.last_error().code(), ErrorCode::RecoveryTimeout);

    hist = diag.history();
    EXPECT_EQ(hist.size(), 2u);
    EXPECT_EQ(hist[1].component, "weston");
    EXPECT_EQ(hist[1].final_state, RecoveryState::Failed);
}

TEST_CASE(RecoveryDiagnostics_BoundedHistory) {
    RecoveryDiagnostics diag("session-diag-bounded");
    for (std::uint32_t i = 1; i <= 60; ++i) {
        diag.record_recovery_start(i, "ldde", RecoveryReason::LddeUnexpectedExit, RecoveryPolicy::ComponentRestart, 1);
        diag.record_recovery_success(i, "ldde", RecoveryReason::LddeUnexpectedExit, RecoveryPolicy::ComponentRestart, 1, std::chrono::milliseconds(10));
    }

    auto hist = diag.history();
    EXPECT_EQ(hist.size(), 50u); // capped at 50 records
    EXPECT_EQ(hist.front().recovery_id, 11u);
    EXPECT_EQ(hist.back().recovery_id, 60u);
}

TEST_MAIN()
