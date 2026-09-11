#pragma once

#include "lddm/process/process_spec.hpp"
#include "lddm/process/process_types.hpp"
#include <optional>
#include <string>
#include <chrono>

namespace lddm {

class ProcessDiagnostics {
public:
    explicit ProcessDiagnostics(ProcessSpec spec, ProcessId pid = 0);

    void set_pid(ProcessId pid) noexcept { pid_ = pid; }
    void set_state(ProcessState state) noexcept { state_ = state; }
    void set_start_time(SystemTimePoint tp) noexcept { start_time_ = tp; }
    void set_exit_info(const ProcessExitInfo& info) noexcept {
        exit_info_ = info;
        exit_time_ = info.exit_time;
    }

    [[nodiscard]] ProcessId pid() const noexcept { return pid_; }
    [[nodiscard]] ProcessState state() const noexcept { return state_; }
    [[nodiscard]] const ProcessSpec& spec() const noexcept { return spec_; }
    [[nodiscard]] SystemTimePoint start_time() const noexcept { return start_time_; }
    [[nodiscard]] std::optional<SystemTimePoint> exit_time() const noexcept { return exit_time_; }
    [[nodiscard]] const std::optional<ProcessExitInfo>& exit_info() const noexcept { return exit_info_; }

    [[nodiscard]] std::chrono::milliseconds runtime_duration() const;
    [[nodiscard]] std::string format_report() const;

private:
    ProcessSpec spec_;
    ProcessId pid_{0};
    ProcessState state_{ProcessState::CREATED};
    SystemTimePoint start_time_{SystemClock::now()};
    std::optional<SystemTimePoint> exit_time_{std::nullopt};
    std::optional<ProcessExitInfo> exit_info_{std::nullopt};
};

} // namespace lddm

