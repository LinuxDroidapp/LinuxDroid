#include "test_framework.hpp"
#include "lddm/process/process_spec.hpp"
#include "lddm/core/error.hpp"

TEST_CASE(ProcessSpec_ValidSpec) {
    lddm::ProcessSpec spec{
        .name = "test-proc",
        .executable = "/bin/true",
        .arguments = {"arg1", "arg2"},
        .working_directory = "/tmp"
    };

    auto res = spec.validate();
    EXPECT_TRUE(res.has_value());
}

TEST_CASE(ProcessSpec_EmptyExecutableFails) {
    lddm::ProcessSpec spec{
        .name = "test-proc",
        .executable = ""
    };

    auto res = spec.validate();
    EXPECT_FALSE(res.has_value());
    EXPECT_EQ(res.error().code(), lddm::ErrorCode::ProcessSpawnFailed);
}

TEST_CASE(ProcessSpec_InvalidWorkingDirectoryFails) {
    lddm::ProcessSpec spec{
        .name = "test-proc",
        .executable = "/bin/echo",
        .working_directory = "/non_existent_directory_abcdef_12345"
    };

    auto res = spec.validate();
    EXPECT_FALSE(res.has_value());
    EXPECT_EQ(res.error().code(), lddm::ErrorCode::ProcessSpawnFailed);
}

TEST_CASE(ProcessSpec_FileStreamPolicyValidation) {
    lddm::ProcessSpec spec{
        .name = "test-proc",
        .executable = "/bin/echo",
        .stdout_policy = lddm::StreamPolicy::File,
        .stdout_path = "" // Missing file path
    };

    auto res = spec.validate();
    EXPECT_FALSE(res.has_value());
    EXPECT_EQ(res.error().code(), lddm::ErrorCode::ProcessSpawnFailed);

    spec.stdout_path = "/tmp/out.log";
    auto valid_res = spec.validate();
    EXPECT_TRUE(valid_res.has_value());
}

TEST_MAIN()

