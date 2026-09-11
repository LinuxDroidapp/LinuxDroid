#include "test_framework.hpp"
#include "lddm/session/session_manager.hpp"

TEST_CASE(SessionManager_CreateAndFind) {
    lddm::SessionManager mgr;
    EXPECT_EQ(mgr.session_count(), 0u);
    EXPECT_EQ(mgr.active_session(), nullptr);

    lddm::SessionConfig cfg1{
        .id = lddm::SessionId("sess-mgr-1"),
        .base_runtime_dir = "/tmp/lddm_mgr_tests"
    };

    auto res1 = mgr.create_session(cfg1);
    EXPECT_TRUE(res1.has_value());
    EXPECT_EQ(mgr.session_count(), 1u);

    // Automatically set as active
    EXPECT_NE(mgr.active_session(), nullptr);
    if (mgr.active_session()) {
        EXPECT_EQ(mgr.active_session()->id().str(), "sess-mgr-1");
    }

    // Find session
    auto found = mgr.find_session(lddm::SessionId("sess-mgr-1"));
    EXPECT_NE(found, nullptr);

    auto not_found = mgr.find_session(lddm::SessionId("non-existent"));
    EXPECT_EQ(not_found, nullptr);
}

TEST_CASE(SessionManager_DuplicateRejection) {
    lddm::SessionManager mgr;
    lddm::SessionConfig cfg{
        .id = lddm::SessionId("duplicate-id"),
        .base_runtime_dir = "/tmp/lddm_mgr_tests"
    };

    auto res1 = mgr.create_session(cfg);
    EXPECT_TRUE(res1.has_value());

    auto res2 = mgr.create_session(cfg);
    EXPECT_FALSE(res2.has_value());
    EXPECT_EQ(res2.error().code(), lddm::ErrorCode::SessionAlreadyExists);
    EXPECT_EQ(mgr.session_count(), 1u);
}

TEST_CASE(SessionManager_ActiveSessionSwitching) {
    lddm::SessionManager mgr;
    lddm::SessionConfig cfg1{
        .id = lddm::SessionId("sess-switch-1"),
        .base_runtime_dir = "/tmp/lddm_mgr_tests"
    };
    lddm::SessionConfig cfg2{
        .id = lddm::SessionId("sess-switch-2"),
        .base_runtime_dir = "/tmp/lddm_mgr_tests"
    };

    EXPECT_TRUE(mgr.create_session(cfg1).has_value());
    EXPECT_TRUE(mgr.create_session(cfg2).has_value());
    EXPECT_EQ(mgr.session_count(), 2u);

    // Initial active is sess-switch-1
    EXPECT_EQ(mgr.active_session()->id().str(), "sess-switch-1");

    // Switch active to sess-switch-2
    EXPECT_TRUE(mgr.set_active_session(lddm::SessionId("sess-switch-2")).has_value());
    EXPECT_EQ(mgr.active_session()->id().str(), "sess-switch-2");

    // Switch to invalid returns error
    auto invalid_res = mgr.set_active_session(lddm::SessionId("unknown"));
    EXPECT_FALSE(invalid_res.has_value());
    EXPECT_EQ(invalid_res.error().code(), lddm::ErrorCode::SessionNotFound);

    // Clear active session
    mgr.clear_active_session();
    EXPECT_EQ(mgr.active_session(), nullptr);
}

TEST_CASE(SessionManager_RemoveAndStopAll) {
    lddm::SessionManager mgr;
    lddm::SessionConfig cfg{
        .id = lddm::SessionId("sess-remove-test"),
        .base_runtime_dir = "/tmp/lddm_mgr_tests"
    };

    auto create_res = mgr.create_session(cfg);
    EXPECT_TRUE(create_res.has_value());
    auto sess = create_res.value();
    EXPECT_TRUE(sess->initialize().has_value());
    EXPECT_TRUE(sess->start().has_value());

    // Remove while running stops and cleans up
    auto remove_res = mgr.remove_session(lddm::SessionId("sess-remove-test"));
    EXPECT_TRUE(remove_res.has_value());
    EXPECT_EQ(mgr.session_count(), 0u);
    EXPECT_EQ(mgr.active_session(), nullptr);
}

TEST_MAIN()

