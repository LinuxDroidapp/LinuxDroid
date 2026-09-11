#include "test_framework.hpp"
#include "lddm/recovery/recovery_manager.hpp"
#include "lddm/session/session.hpp"

using namespace lddm;
using namespace lddm::recovery;

TEST_CASE(RecoveryPreconditions_NullSession) {
    RecoveryManager rm(nullptr);
    auto res = rm.recover(RecoveryReason::SessionRuntimeFailure);
    EXPECT_FALSE(res.has_value());
    EXPECT_EQ(res.error().code(), ErrorCode::RecoveryPreconditionFailed);
    EXPECT_EQ(rm.state(), RecoveryState::Failed);
}

TEST_CASE(RecoveryPreconditions_DisabledConfig) {
    SessionConfig scfg;
    scfg.base_runtime_dir = "/tmp/lddm_test_recovery_pre_disabled";
    Session session(scfg);
    (void)session.initialize();

    RecoveryConfig rcfg;
    rcfg.enabled = false;

    RecoveryManager rm(&session, rcfg);
    auto res = rm.recover(RecoveryReason::SessionRuntimeFailure);
    EXPECT_FALSE(res.has_value());
    EXPECT_EQ(res.error().code(), ErrorCode::RecoveryNotAllowed);
    EXPECT_EQ(rm.state(), RecoveryState::Failed);
}

TEST_CASE(RecoveryPreconditions_SupervisorMissing) {
    SessionConfig scfg;
    scfg.base_runtime_dir = "/tmp/lddm_test_recovery_pre_nosup";
    Session session(scfg);
    // Note: session is CREATED, not initialized, so supervisor is null!

    RecoveryManager rm(&session);
    auto res = rm.recover(RecoveryReason::SessionRuntimeFailure);
    EXPECT_FALSE(res.has_value());
    EXPECT_EQ(res.error().code(), ErrorCode::RecoveryPreconditionFailed);
}

TEST_MAIN()
