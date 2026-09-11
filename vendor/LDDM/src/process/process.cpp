#include "lddm/process/process.hpp"
#include "lddm/platform/process.hpp"
#include "lddm/platform/unique_fd.hpp"
#include "lddm/logging/logger.hpp"
#include "lddm/core/error.hpp"
#include <fcntl.h>
#include <signal.h>
#include <thread>
#include <atomic>

namespace lddm {

namespace {
std::atomic<std::uint64_t> g_process_handle_counter{0};
}

Process::Process(ProcessSpec spec, std::string handle)
    : handle_(handle.empty() ? (spec.name + "-" + std::to_string(++g_process_handle_counter)) : std::move(handle))
    , spec_(std::move(spec))
    , diagnostics_(spec_) {
}

Process::~Process() {
    if (is_running()) {
        LDDM_LOG_WARN(LogSubsystem::PROCESS, "[Process '{}' (pid={})] Destroying running process; terminating forcefully",
                      name(), pid_);
        (void)kill(std::chrono::milliseconds{1000});
    }
}

ProcessId Process::pid() const noexcept {
    std::lock_guard lock(mutex_);
    return pid_;
}

ProcessState Process::state() const noexcept {
    std::lock_guard lock(mutex_);
    return state_;
}

bool Process::is_running() const noexcept {
    std::lock_guard lock(mutex_);
    return state_ == ProcessState::RUNNING || state_ == ProcessState::STARTING;
}

bool Process::is_terminated() const noexcept {
    std::lock_guard lock(mutex_);
    return state_ == ProcessState::EXITED || state_ == ProcessState::FAILED;
}

std::optional<ProcessExitInfo> Process::exit_info() const noexcept {
    std::lock_guard lock(mutex_);
    return exit_info_;
}

Result<void> Process::transition_to(ProcessState target, std::string reason) {
    std::lock_guard lock(mutex_);
    if (!is_valid_process_transition(state_, target)) {
        return Result<void>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessInvalidState,
            "Invalid process state transition from " + std::string(to_string(state_)) + " to " + std::string(to_string(target)),
            "name=" + name() + " reason=" + reason));
    }

    state_ = target;
    diagnostics_.set_state(target);
    LDDM_LOG_DEBUG(LogSubsystem::PROCESS, "[Process '{}' (pid={})] Transitioned to {} ({})",
                   name(), pid_, to_string(target), reason.empty() ? "none" : reason);
    return Result<void>::success();
}

Result<void> Process::start(const std::vector<std::string>& base_environment) {
    std::lock_guard lock(mutex_);

    if (state_ != ProcessState::CREATED) {
        return Result<void>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessInvalidState,
            "Cannot start process in state " + std::string(to_string(state_)),
            "name=" + name()));
    }

    auto val_res = spec_.validate();
    if (!val_res.has_value()) {
        state_ = ProcessState::FAILED;
        diagnostics_.set_state(ProcessState::FAILED);
        return val_res;
    }

    state_ = ProcessState::STARTING;
    diagnostics_.set_state(ProcessState::STARTING);

    // Setup stream file descriptors
    UniqueFd in_fd;
    UniqueFd out_fd;
    UniqueFd err_fd;

    if (spec_.stdin_policy == StreamPolicy::Null || spec_.stdin_policy == StreamPolicy::Close) {
        in_fd = UniqueFd(open("/dev/null", O_RDONLY | O_CLOEXEC));
    }

    if (spec_.stdout_policy == StreamPolicy::Null) {
        out_fd = UniqueFd(open("/dev/null", O_WRONLY | O_CLOEXEC));
    } else if (spec_.stdout_policy == StreamPolicy::File) {
        out_fd = UniqueFd(open(spec_.stdout_path.c_str(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644));
        if (!out_fd.is_valid()) {
            state_ = ProcessState::FAILED;
            diagnostics_.set_state(ProcessState::FAILED);
            return Result<void>::failure(Error(
                ErrorCategory::Process,
                ErrorCode::ProcessSpawnFailed,
                "Failed to open stdout file: " + spec_.stdout_path.string(),
                "name=" + name()));
        }
    }

    if (spec_.stderr_policy == StreamPolicy::Null) {
        err_fd = UniqueFd(open("/dev/null", O_WRONLY | O_CLOEXEC));
    } else if (spec_.stderr_policy == StreamPolicy::File) {
        err_fd = UniqueFd(open(spec_.stderr_path.c_str(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644));
        if (!err_fd.is_valid()) {
            state_ = ProcessState::FAILED;
            diagnostics_.set_state(ProcessState::FAILED);
            return Result<void>::failure(Error(
                ErrorCategory::Process,
                ErrorCode::ProcessSpawnFailed,
                "Failed to open stderr file: " + spec_.stderr_path.string(),
                "name=" + name()));
        }
    }

    // Merge environment
    std::unordered_map<std::string, std::string> env_map;
    for (const auto& entry : base_environment) {
        auto eq = entry.find('=');
        if (eq != std::string::npos) {
            env_map[entry.substr(0, eq)] = entry.substr(eq + 1);
        }
    }
    for (const auto& [k, v] : spec_.environment) {
        env_map[k] = v;
    }

    std::vector<std::string> final_env;
    final_env.reserve(env_map.size());
    for (const auto& [k, v] : env_map) {
        final_env.push_back(k + "=" + v);
    }

    platform::SpawnOptions spawn_opt{
        .executable = spec_.executable,
        .arguments = spec_.arguments,
        .environment_vector = final_env,
        .working_directory = spec_.working_directory,
        .stdin_fd = in_fd.get(),
        .stdout_fd = out_fd.get(),
        .stderr_fd = err_fd.get(),
        .enable_process_group = spec_.enable_process_group
    };

    LDDM_LOG_INFO(LogSubsystem::PROCESS, "[Process '{}'] Spawning binary '{}'", name(), spec_.executable);
    auto spawn_res = platform::spawn_process(spawn_opt);
    if (!spawn_res.has_value()) {
        state_ = ProcessState::FAILED;
        diagnostics_.set_state(ProcessState::FAILED);
        LDDM_LOG_ERROR(LogSubsystem::PROCESS, "[Process '{}'] Spawn failed: {}", name(), spawn_res.error().to_string());
        return Result<void>::failure(spawn_res.error());
    }

    pid_ = spawn_res.value();
    start_time_ = SystemClock::now();
    state_ = ProcessState::RUNNING;

    diagnostics_.set_pid(pid_);
    diagnostics_.set_start_time(start_time_);
    diagnostics_.set_state(ProcessState::RUNNING);

    LDDM_LOG_INFO(LogSubsystem::PROCESS, "[Process '{}'] Running with PID {}", name(), pid_);
    return Result<void>::success();
}

Result<void> Process::stop(std::optional<std::chrono::milliseconds> timeout) {
    std::lock_guard lock(mutex_);
    return stop_locked(timeout);
}

Result<void> Process::stop_locked(std::optional<std::chrono::milliseconds> timeout) {
    if (state_ == ProcessState::EXITED || state_ == ProcessState::FAILED) {
        return Result<void>::success();
    }

    if (state_ == ProcessState::CREATED) {
        state_ = ProcessState::EXITED;
        diagnostics_.set_state(ProcessState::EXITED);
        return Result<void>::success();
    }

    state_ = ProcessState::STOPPING;
    diagnostics_.set_state(ProcessState::STOPPING);

    auto term_timeout = timeout.value_or(spec_.stop_timeout);
    LDDM_LOG_INFO(LogSubsystem::PROCESS, "[Process '{}' (pid={})] Stopping gracefully (SIGTERM, timeout={}ms)",
                  name(), pid_, term_timeout.count());

    // Send SIGTERM
    (void)platform::send_signal(pid_, SIGTERM, spec_.enable_process_group);

    // Poll for exit
    auto deadline = SystemClock::now() + term_timeout;
    while (SystemClock::now() < deadline) {
        auto wait_res = platform::wait_process(pid_, false);
        if (wait_res.has_value()) {
            exit_info_ = wait_res.value();
            state_ = ProcessState::EXITED;
            diagnostics_.set_exit_info(*exit_info_);
            diagnostics_.set_state(ProcessState::EXITED);
            LDDM_LOG_INFO(LogSubsystem::PROCESS, "[Process '{}' (pid={})] Exited cleanly: {}",
                          name(), pid_, exit_info_->format());
            return Result<void>::success();
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }

    // Escalate to SIGKILL
    LDDM_LOG_WARN(LogSubsystem::PROCESS, "[Process '{}' (pid={})] Did not stop within timeout; escalating to SIGKILL",
                  name(), pid_);
    return kill_locked(spec_.kill_timeout);
}

Result<void> Process::kill(std::optional<std::chrono::milliseconds> timeout) {
    std::lock_guard lock(mutex_);
    return kill_locked(timeout);
}

Result<void> Process::kill_locked(std::optional<std::chrono::milliseconds> timeout) {
    if (state_ == ProcessState::EXITED || state_ == ProcessState::FAILED) {
        return Result<void>::success();
    }

    if (pid_ <= 0) {
        state_ = ProcessState::EXITED;
        diagnostics_.set_state(ProcessState::EXITED);
        return Result<void>::success();
    }

    state_ = ProcessState::STOPPING;
    diagnostics_.set_state(ProcessState::STOPPING);

    auto kill_timeout = timeout.value_or(spec_.kill_timeout);
    LDDM_LOG_WARN(LogSubsystem::PROCESS, "[Process '{}' (pid={})] Sending SIGKILL", name(), pid_);

    (void)platform::send_signal(pid_, SIGKILL, spec_.enable_process_group);

    auto deadline = SystemClock::now() + kill_timeout;
    while (SystemClock::now() < deadline) {
        auto wait_res = platform::wait_process(pid_, false);
        if (wait_res.has_value()) {
            exit_info_ = wait_res.value();
            state_ = ProcessState::EXITED;
            diagnostics_.set_exit_info(*exit_info_);
            diagnostics_.set_state(ProcessState::EXITED);
            LDDM_LOG_INFO(LogSubsystem::PROCESS, "[Process '{}' (pid={})] Terminated: {}",
                          name(), pid_, exit_info_->format());
            return Result<void>::success();
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }

    // Final fallback blocking wait
    auto final_res = platform::wait_process(pid_, true);
    if (final_res.has_value()) {
        exit_info_ = final_res.value();
    } else {
        ProcessExitInfo fallback_info{
            .pid = pid_,
            .signaled = true,
            .term_signal = SIGKILL,
            .exit_time = SystemClock::now()
        };
        exit_info_ = fallback_info;
    }

    state_ = ProcessState::EXITED;
    diagnostics_.set_exit_info(*exit_info_);
    diagnostics_.set_state(ProcessState::EXITED);
    return Result<void>::success();
}

Result<ProcessExitInfo> Process::wait() {
    std::lock_guard lock(mutex_);
    if (exit_info_) {
        return Result<ProcessExitInfo>::success(*exit_info_);
    }

    if (pid_ <= 0 || state_ == ProcessState::CREATED) {
        return Result<ProcessExitInfo>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessNotFound,
            "Process was not started",
            "name=" + name()));
    }

    auto wait_res = platform::wait_process(pid_, true);
    if (!wait_res.has_value()) {
        return wait_res;
    }

    exit_info_ = wait_res.value();
    state_ = ProcessState::EXITED;
    diagnostics_.set_exit_info(*exit_info_);
    diagnostics_.set_state(ProcessState::EXITED);

    LDDM_LOG_INFO(LogSubsystem::PROCESS, "[Process '{}' (pid={})] Reaped: {}",
                  name(), pid_, exit_info_->format());
    return Result<ProcessExitInfo>::success(*exit_info_);
}

Result<std::optional<ProcessExitInfo>> Process::poll() {
    std::lock_guard lock(mutex_);
    if (exit_info_) {
        return Result<std::optional<ProcessExitInfo>>::success(exit_info_);
    }

    if (pid_ <= 0 || state_ == ProcessState::CREATED) {
        return Result<std::optional<ProcessExitInfo>>::success(std::nullopt);
    }

    auto wait_res = platform::wait_process(pid_, false);
    if (wait_res.has_value()) {
        exit_info_ = wait_res.value();
        state_ = ProcessState::EXITED;
        diagnostics_.set_exit_info(*exit_info_);
        diagnostics_.set_state(ProcessState::EXITED);

        LDDM_LOG_INFO(LogSubsystem::PROCESS, "[Process '{}' (pid={})] Polled exit: {}",
                      name(), pid_, exit_info_->format());
        return Result<std::optional<ProcessExitInfo>>::success(exit_info_);
    }

    return Result<std::optional<ProcessExitInfo>>::success(std::nullopt);
}

} // namespace lddm

