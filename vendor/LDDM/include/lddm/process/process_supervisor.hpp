#pragma once

#include "lddm/core/result.hpp"
#include "lddm/process/process.hpp"
#include "lddm/process/process_registry.hpp"
#include "lddm/process/process_events.hpp"
#include "lddm/process/process_spec.hpp"
#include <vector>
#include <memory>
#include <mutex>
#include <chrono>
#include <unordered_set>

namespace lddm {

struct ProcessSupervisorConfig {
    std::chrono::milliseconds default_startup_timeout{5000};
    std::chrono::milliseconds default_stop_timeout{5000};
    std::chrono::milliseconds default_kill_timeout{2000};
};

class ProcessSupervisor {
public:
    explicit ProcessSupervisor(ProcessSupervisorConfig config = {});
    ~ProcessSupervisor();

    ProcessSupervisor(const ProcessSupervisor&) = delete;
    ProcessSupervisor& operator=(const ProcessSupervisor&) = delete;
    ProcessSupervisor(ProcessSupervisor&&) = delete;
    ProcessSupervisor& operator=(ProcessSupervisor&&) = delete;

    Result<std::shared_ptr<Process>> start_process(ProcessSpec spec);

    Result<void> stop_process(ProcessId pid, std::optional<std::chrono::milliseconds> timeout = std::nullopt);
    Result<void> stop_process(const std::string& handle, std::optional<std::chrono::milliseconds> timeout = std::nullopt);
    Result<void> stop_process(std::shared_ptr<Process> process, std::optional<std::chrono::milliseconds> timeout = std::nullopt);

    Result<void> kill_process(ProcessId pid, std::optional<std::chrono::milliseconds> timeout = std::nullopt);

    Result<void> stop_all(std::optional<std::chrono::milliseconds> stop_timeout = std::nullopt,
                          std::optional<std::chrono::milliseconds> kill_timeout = std::nullopt);

    void reset() noexcept;

    std::vector<ProcessExitInfo> reap_exited_processes();

    void set_base_environment(std::vector<std::string> env);
    [[nodiscard]] std::vector<std::string> base_environment() const;

    void register_listener(ProcessEventListener listener);
    void clear_listeners() noexcept;

    [[nodiscard]] std::shared_ptr<Process> find_by_pid(ProcessId pid) const;
    [[nodiscard]] std::shared_ptr<Process> find_by_handle(const std::string& handle) const;
    [[nodiscard]] std::vector<std::shared_ptr<Process>> find_by_name(const std::string& name) const;

    [[nodiscard]] std::vector<std::shared_ptr<Process>> all_processes() const;
    [[nodiscard]] std::size_t active_process_count() const;
    [[nodiscard]] std::size_t total_process_count() const noexcept;

private:
    mutable std::mutex mutex_;
    ProcessSupervisorConfig config_;
    ProcessRegistry registry_;
    std::vector<ProcessEventListener> listeners_;
    std::vector<std::string> base_environment_;
    std::unordered_set<ProcessId> emitted_exit_pids_;
    bool shutting_down_{false};

    void emit_event(const ProcessEvent& event);
};

namespace process {
using lddm::ProcessSupervisor;
}

} // namespace lddm

