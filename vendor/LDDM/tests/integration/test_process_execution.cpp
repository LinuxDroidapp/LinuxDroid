#include "test_framework.hpp"
#include "lddm/process/process.hpp"
#include "lddm/core/error.hpp"
#include <fstream>
#include <filesystem>

TEST_CASE(ProcessExecution_SuccessExitZero) {
    lddm::ProcessSpec spec{
        .name = "test-true",
        .executable = "/bin/true"
    };

    lddm::Process proc(spec);
    EXPECT_EQ(proc.state(), lddm::ProcessState::CREATED);

    auto start_res = proc.start();
    EXPECT_TRUE(start_res.has_value());
    EXPECT_TRUE(proc.pid() > 0);

    auto wait_res = proc.wait();
    EXPECT_TRUE(wait_res.has_value());
    if (wait_res.has_value()) {
        EXPECT_TRUE(wait_res.value().exited_normally);
        EXPECT_EQ(wait_res.value().exit_code, 0);
        EXPECT_TRUE(wait_res.value().success());
    }
    EXPECT_EQ(proc.state(), lddm::ProcessState::EXITED);
}

TEST_CASE(ProcessExecution_FailureExitNonZero) {
    lddm::ProcessSpec spec{
        .name = "test-false",
        .executable = "/bin/false"
    };

    lddm::Process proc(spec);
    auto start_res = proc.start();
    EXPECT_TRUE(start_res.has_value());

    auto wait_res = proc.wait();
    EXPECT_TRUE(wait_res.has_value());
    if (wait_res.has_value()) {
        EXPECT_TRUE(wait_res.value().exited_normally);
        EXPECT_EQ(wait_res.value().exit_code, 1);
        EXPECT_FALSE(wait_res.value().success());
    }
    EXPECT_EQ(proc.state(), lddm::ProcessState::EXITED);
}

TEST_CASE(ProcessExecution_NonExistentBinaryFails) {
    lddm::ProcessSpec spec{
        .name = "test-nonexistent",
        .executable = "/non_existent_binary_xyz_12345"
    };

    lddm::Process proc(spec);
    auto start_res = proc.start();
    EXPECT_FALSE(start_res.has_value());
    EXPECT_EQ(start_res.error().code(), lddm::ErrorCode::ProcessExecFailed);
    EXPECT_EQ(proc.state(), lddm::ProcessState::FAILED);
}

TEST_CASE(ProcessExecution_EnvironmentPassing) {
    lddm::ProcessSpec spec{
        .name = "test-env",
        .executable = "/bin/sh",
        .arguments = {"-c", "test \"$MY_TEST_VAR\" = \"hello_lddm\""},
        .environment = {{"MY_TEST_VAR", "hello_lddm"}}
    };

    lddm::Process proc(spec);
    auto start_res = proc.start();
    EXPECT_TRUE(start_res.has_value());

    auto wait_res = proc.wait();
    EXPECT_TRUE(wait_res.has_value());
    if (wait_res.has_value()) {
        EXPECT_EQ(wait_res.value().exit_code, 0);
    }
}

TEST_CASE(ProcessExecution_WorkingDirectory) {
    auto tmp_dir = std::filesystem::temp_directory_path() / "lddm_proc_cwd_test";
    std::filesystem::create_directories(tmp_dir);

    lddm::ProcessSpec spec{
        .name = "test-cwd",
        .executable = "/bin/sh",
        .arguments = {"-c", "pwd > cwd_output.txt"},
        .working_directory = tmp_dir
    };

    lddm::Process proc(spec);
    auto start_res = proc.start();
    EXPECT_TRUE(start_res.has_value());

    auto wait_res = proc.wait();
    EXPECT_TRUE(wait_res.has_value());

    auto out_file = tmp_dir / "cwd_output.txt";
    EXPECT_TRUE(std::filesystem::exists(out_file));
    std::filesystem::remove_all(tmp_dir);
}

TEST_CASE(ProcessExecution_FileStreamRedirection) {
    auto out_file = std::filesystem::temp_directory_path() / "lddm_proc_stdout_test.txt";
    if (std::filesystem::exists(out_file)) {
        std::filesystem::remove(out_file);
    }

    lddm::ProcessSpec spec{
        .name = "test-stream-file",
        .executable = "/bin/echo",
        .arguments = {"stream_redirection_verified"},
        .stdout_policy = lddm::StreamPolicy::File,
        .stdout_path = out_file
    };

    lddm::Process proc(spec);
    auto start_res = proc.start();
    EXPECT_TRUE(start_res.has_value());

    auto wait_res = proc.wait();
    EXPECT_TRUE(wait_res.has_value());

    EXPECT_TRUE(std::filesystem::exists(out_file));
    std::ifstream ifs(out_file);
    std::string line;
    std::getline(ifs, line);
    EXPECT_TRUE(line.find("stream_redirection_verified") != std::string::npos);

    std::filesystem::remove(out_file);
}

TEST_MAIN()

