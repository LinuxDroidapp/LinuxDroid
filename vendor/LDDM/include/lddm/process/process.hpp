#pragma once

#include "lddm/core/result.hpp"
#include "lddm/core/types.hpp"
#include "lddm/process/process_types.hpp"
#include "lddm/process/process_spec.hpp"
#include "lddm/process/process_diagnostics.hpp"
#include <string>
#include <vector>
#include <optional>
#include <mutex>
#include <memory>
#include <chrono>

namespace lddm {

class Process {
public:
    explicit Process(ProcessSpec spec, std::string handle = "");
    ~Process();

    Process(const Process&) = delete;
    Process& operator=(const Process&) = delete;
    Process(Process&&) = delete;
    Process& operator=(Process&&) = delete;

    [[nodiscard]] const std::string& handle() const noexcept { return handle_; }
    [[nodiscard]] const std::string& name() const noexcept { return spec_.name; }
    [[nodiscard]] const ProcessSpec& spec() const noexcept { return spec_; }
    [[nodiscard]] ProcessId pid() const noexcept;
    [[nodiscard]] ProcessState state() const noexcept;
    [[nodiscard]] bool is_running() const noexcept;
    [[nodiscard]] bool is_terminated() const noexcept;
    [[nodiscard]] std::optional<ProcessExitInfo> exit_info() const noexcept;
    [[nodiscard]] const ProcessDiagnostics& diagnostics() const noexcept { return diagnostics_; }

    Result<void> start(const std::vector<std::string>& base_environment = {});
    Result<void> stop(std::optional<std::chrono::milliseconds> timeout = std::nullopt);
    Result<void> kill(std::optional<std::chrono::milliseconds> timeout = std::nullopt);

    Result<ProcessExitInfo> wait();
    Result<std::optional<ProcessExitInfo>> poll();

    Result<void> transition_to(ProcessState target, std::string reason = "");

private:
    mutable std::mutex mutex_;
    std::string handle_;
    ProcessSpec spec_;
    ProcessId pid_{0};
    ProcessState state_{ProcessState::CREATED};
    SystemTimePoint start_time_{};
    std::optional<ProcessExitInfo> exit_info_{std::nullopt};
    ProcessDiagnostics diagnostics_;

    Result<void> stop_locked(std::optional<std::chrono::milliseconds> timeout);
    Result<void> kill_locked(std::optional<std::chrono::milliseconds> timeout);
};

using ProcessHandle = std::string;

namespace process {
using lddm::Process;
using lddm::ProcessHandle;
}

} // namespace lddm

