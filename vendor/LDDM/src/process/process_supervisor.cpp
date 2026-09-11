#include "lddm/process/process_supervisor.hpp"
#include "lddm/logging/logger.hpp"
#include "lddm/core/error.hpp"
#include <thread>

namespace lddm {

ProcessSupervisor::ProcessSupervisor(ProcessSupervisorConfig config)
    : config_(config) {
}

ProcessSupervisor::~ProcessSupervisor() {
    (void)stop_all();
}

void ProcessSupervisor::set_base_environment(std::vector<std::string> env) {
    std::lock_guard lock(mutex_);
    base_environment_ = std::move(env);
}

std::vector<std::string> ProcessSupervisor::base_environment() const {
    std::lock_guard lock(mutex_);
    return base_environment_;
}

void ProcessSupervisor::register_listener(ProcessEventListener listener) {
    std::lock_guard lock(mutex_);
    listeners_.push_back(std::move(listener));
}

void ProcessSupervisor::clear_listeners() noexcept {
    std::lock_guard lock(mutex_);
    listeners_.clear();
}

void ProcessSupervisor::emit_event(const ProcessEvent& event) {
    std::vector<ProcessEventListener> listeners_copy;
    {
        std::lock_guard lock(mutex_);
        listeners_copy = listeners_;
    }

    for (const auto& listener : listeners_copy) {
        if (listener) {
            listener(event);
        }
    }
}

Result<std::shared_ptr<Process>> ProcessSupervisor::start_process(ProcessSpec spec) {
    std::vector<std::string> base_env_copy;
    {
        std::lock_guard lock(mutex_);
        if (shutting_down_) {
            return Result<std::shared_ptr<Process>>::failure(Error(
                ErrorCategory::Process,
                ErrorCode::ProcessInvalidState,
                "Cannot start process; supervisor is shutting down"));
        }
        base_env_copy = base_environment_;
    }

    auto process = std::make_shared<Process>(std::move(spec));
    auto reg_res = registry_.register_process(process);
    if (!reg_res.has_value()) {
        return Result<std::shared_ptr<Process>>::failure(reg_res.error());
    }

    auto start_res = process->start(base_env_copy);
    if (!start_res.has_value()) {
        emit_event(ProcessEvent{
            .type = ProcessEventType::StartupFailed,
            .pid = 0,
            .process_name = process->name(),
            .timestamp = SystemClock::now(),
            .message = start_res.error().message()
        });
        return Result<std::shared_ptr<Process>>::failure(start_res.error());
    }

    emit_event(ProcessEvent{
        .type = ProcessEventType::Started,
        .pid = process->pid(),
        .process_name = process->name(),
        .timestamp = SystemClock::now(),
        .message = "Process spawned successfully"
    });

    return Result<std::shared_ptr<Process>>::success(process);
}

Result<void> ProcessSupervisor::stop_process(ProcessId pid, std::optional<std::chrono::milliseconds> timeout) {
    auto proc = registry_.find_by_pid(pid);
    if (!proc) {
        return Result<void>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessNotFound,
            "Process with PID " + std::to_string(pid) + " not found"));
    }
    return stop_process(proc, timeout);
}

Result<void> ProcessSupervisor::stop_process(const std::string& handle, std::optional<std::chrono::milliseconds> timeout) {
    auto proc = registry_.find_by_handle(handle);
    if (!proc) {
        return Result<void>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessNotFound,
            "Process with handle '" + handle + "' not found"));
    }
    return stop_process(proc, timeout);
}

Result<void> ProcessSupervisor::stop_process(std::shared_ptr<Process> process, std::optional<std::chrono::milliseconds> timeout) {
    if (!process) {
        return Result<void>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessNotFound,
            "Null process pointer"));
    }

    emit_event(ProcessEvent{
        .type = ProcessEventType::TerminationRequested,
        .pid = process->pid(),
        .process_name = process->name(),
        .timestamp = SystemClock::now(),
        .message = "Termination requested"
    });

    auto res = process->stop(timeout.value_or(config_.default_stop_timeout));
    auto exit_info = process->exit_info().value_or(ProcessExitInfo{});

    emit_event(ProcessEvent{
        .type = ProcessEventType::Terminated,
        .pid = process->pid(),
        .process_name = process->name(),
        .exit_info = exit_info,
        .timestamp = SystemClock::now(),
        .message = "Process terminated"
    });

    {
        std::lock_guard lock(mutex_);
        emitted_exit_pids_.insert(process->pid());
    }

    return res;
}

Result<void> ProcessSupervisor::kill_process(ProcessId pid, std::optional<std::chrono::milliseconds> timeout) {
    auto proc = registry_.find_by_pid(pid);
    if (!proc) {
        return Result<void>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessNotFound,
            "Process with PID " + std::to_string(pid) + " not found"));
    }

    auto res = proc->kill(timeout.value_or(config_.default_kill_timeout));
    auto exit_info = proc->exit_info().value_or(ProcessExitInfo{});

    emit_event(ProcessEvent{
        .type = ProcessEventType::Terminated,
        .pid = proc->pid(),
        .process_name = proc->name(),
        .exit_info = exit_info,
        .timestamp = SystemClock::now(),
        .message = "Process killed with SIGKILL"
    });

    {
        std::lock_guard lock(mutex_);
        emitted_exit_pids_.insert(proc->pid());
    }

    return res;
}

Result<void> ProcessSupervisor::stop_all(std::optional<std::chrono::milliseconds> stop_timeout,
                                        std::optional<std::chrono::milliseconds> kill_timeout) {
    std::vector<std::shared_ptr<Process>> procs;
    {
        std::lock_guard lock(mutex_);
        shutting_down_ = true;
        procs = registry_.all_processes();
    }

    if (procs.empty()) {
        return Result<void>::success();
    }

    LDDM_LOG_INFO(LogSubsystem::PROCESS, "[Supervisor] Stopping {} supervised process(es)", procs.size());

    auto s_timeout = stop_timeout.value_or(config_.default_stop_timeout);
    auto k_timeout = kill_timeout.value_or(config_.default_kill_timeout);

    // Stop all processes (they stop gracefully, then escalate to SIGKILL if needed)
    for (const auto& proc : procs) {
        if (proc && proc->is_running()) {
            (void)stop_process(proc, s_timeout);
        }
    }

    // Ensure any remaining are killed
    for (const auto& proc : procs) {
        if (proc && proc->is_running()) {
            (void)proc->kill(k_timeout);
        }
    }

    registry_.clear();
    return Result<void>::success();
}

void ProcessSupervisor::reset() noexcept {
    std::lock_guard lock(mutex_);
    shutting_down_ = false;
    registry_.clear();
    emitted_exit_pids_.clear();
}

std::vector<ProcessExitInfo> ProcessSupervisor::reap_exited_processes() {
    auto procs = registry_.all_processes();
    std::vector<ProcessExitInfo> reaped;

    for (const auto& proc : procs) {
        if (!proc) {
            continue;
        }
        auto pid = proc->pid();
        if (pid <= 0) {
            continue;
        }

        {
            std::lock_guard lock(mutex_);
            if (emitted_exit_pids_.find(pid) != emitted_exit_pids_.end()) {
                continue;
            }
        }

        auto poll_res = proc->poll();
        if (poll_res.has_value() && poll_res.value().has_value()) {
            const auto& exit_info = *poll_res.value();
            reaped.push_back(exit_info);

            {
                std::lock_guard lock(mutex_);
                emitted_exit_pids_.insert(pid);
            }

            emit_event(ProcessEvent{
                .type = exit_info.signaled ? ProcessEventType::Signaled : ProcessEventType::Exited,
                .pid = pid,
                .process_name = proc->name(),
                .exit_info = exit_info,
                .timestamp = exit_info.exit_time,
                .message = "Process reaped: " + exit_info.format()
            });
        }
    }

    return reaped;
}

std::shared_ptr<Process> ProcessSupervisor::find_by_pid(ProcessId pid) const {
    return registry_.find_by_pid(pid);
}

std::shared_ptr<Process> ProcessSupervisor::find_by_handle(const std::string& handle) const {
    return registry_.find_by_handle(handle);
}

std::vector<std::shared_ptr<Process>> ProcessSupervisor::find_by_name(const std::string& name) const {
    return registry_.find_by_name(name);
}

std::vector<std::shared_ptr<Process>> ProcessSupervisor::all_processes() const {
    return registry_.all_processes();
}

std::size_t ProcessSupervisor::active_process_count() const {
    auto procs = registry_.all_processes();
    std::size_t active = 0;
    for (const auto& proc : procs) {
        if (proc && proc->is_running()) {
            ++active;
        }
    }
    return active;
}

std::size_t ProcessSupervisor::total_process_count() const noexcept {
    return registry_.count();
}

} // namespace lddm

