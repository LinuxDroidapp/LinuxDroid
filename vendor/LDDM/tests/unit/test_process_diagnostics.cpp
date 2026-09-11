#include "test_framework.hpp"
#include "lddm/process/process_diagnostics.hpp"

TEST_CASE(ProcessDiagnostics_ReportFormatting) {
    lddm::ProcessSpec spec{
        .name = "test-worker",
        .executable = "/bin/sleep",
        .arguments = {"5"},
        .working_directory = "/tmp"
    };

    lddm::ProcessDiagnostics diag(spec, 4321);
    diag.set_state(lddm::ProcessState::RUNNING);

    lddm::ProcessExitInfo exit_info{
        .pid = 4321,
        .exited_normally = true,
        .exit_code = 0
    };
    diag.set_exit_info(exit_info);
    diag.set_state(lddm::ProcessState::EXITED);

    auto report = diag.format_report();
    EXPECT_TRUE(report.find("test-worker") != std::string::npos);
    EXPECT_TRUE(report.find("4321") != std::string::npos);
    EXPECT_TRUE(report.find("EXITED") != std::string::npos);
    EXPECT_TRUE(report.find("exited=0") != std::string::npos);
}

TEST_MAIN()

