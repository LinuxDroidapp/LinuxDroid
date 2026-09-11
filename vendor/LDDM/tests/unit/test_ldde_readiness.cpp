#include "test_framework.hpp"
#include "lddm/ldde/ldde_readiness.hpp"
#include <filesystem>
#include <fstream>
#include <unistd.h>

using namespace lddm::ldde;

TEST_CASE(LddeReadiness_FileNotReady) {
    std::string path = "/tmp/lddm_ldde_nonexistent_ready_file";
    std::filesystem::remove(path);

    auto status = LddeReadinessDetector::check_readiness(path, LddeReadinessMode::File, getpid());
    EXPECT_EQ(status, LddeReadinessStatus::NotReady);
}

TEST_CASE(LddeReadiness_ProcessExited) {
    std::string path = "/tmp/lddm_ldde_nonexistent_ready_file";
    // PID 999999 does not exist
    auto status = LddeReadinessDetector::check_readiness(path, LddeReadinessMode::File, 999999);
    EXPECT_EQ(status, LddeReadinessStatus::ProcessExited);
}

TEST_CASE(LddeReadiness_FileReady) {
    std::string path = "/tmp/lddm_ldde_test_ready_file";
    {
        std::ofstream ofs(path);
        ofs << "STATUS=READY\nVERSION=1\nPID=123\n";
    }

    auto status = LddeReadinessDetector::check_readiness(path, LddeReadinessMode::File, getpid());
    EXPECT_EQ(status, LddeReadinessStatus::Ready);

    auto wait_res = LddeReadinessDetector::wait_for_readiness(
        path, LddeReadinessMode::File, getpid(), std::chrono::milliseconds(500), std::chrono::milliseconds(10));
    EXPECT_TRUE(wait_res.has_value());

    std::filesystem::remove(path);
}

TEST_CASE(LddeReadiness_ProtocolError) {
    std::string path = "/tmp/lddm_ldde_test_fail_file";
    {
        std::ofstream ofs(path);
        ofs << "STATUS=FAILED: Component initialization error\n";
    }

    auto status = LddeReadinessDetector::check_readiness(path, LddeReadinessMode::File, getpid());
    EXPECT_EQ(status, LddeReadinessStatus::ProtocolError);

    auto wait_res = LddeReadinessDetector::wait_for_readiness(
        path, LddeReadinessMode::File, getpid(), std::chrono::milliseconds(100), std::chrono::milliseconds(10));
    EXPECT_FALSE(wait_res.has_value());
    EXPECT_EQ(wait_res.error().code(), lddm::ErrorCode::LddeProtocolError);

    std::filesystem::remove(path);
}

TEST_CASE(LddeReadiness_WaitTimeout) {
    std::string path = "/tmp/lddm_ldde_timeout_ready_file";
    std::filesystem::remove(path);

    auto wait_res = LddeReadinessDetector::wait_for_readiness(
        path, LddeReadinessMode::File, getpid(), std::chrono::milliseconds(100), std::chrono::milliseconds(20));
    EXPECT_FALSE(wait_res.has_value());
    EXPECT_EQ(wait_res.error().code(), lddm::ErrorCode::LddeTimeout);
}

TEST_MAIN()
