#include "lddm/ldde/ldde_manager.hpp"
#include "lddm/ldde/ldde_readiness.hpp"
#include "lddm/logging/logger.hpp"
#include "lddm/core/error.hpp"

#include <filesystem>
#include <sys/stat.h>
#include <unistd.h>

namespace lddm::ldde {

LddeManager::LddeManager(LddeConfig config)
    : config_(std::move(config)) {
}

LddeManager::~LddeManager() {
    (void)stop();
}

const std::string& LddeManager::name() const noexcept {
    return name_;
}

bool LddeManager::is_running() const noexcept {
    std::lock_guard lock(mutex_);
    return state_ == LddeState::Running;
}

LddeState LddeManager::state() const noexcept {
    std::lock_guard lock(mutex_);
    return state_;
}

const LddeDiagnostics& LddeManager::diagnostics() const noexcept {
    std::lock_guard lock(mutex_);
    return diagnostics_;
}

const LddeConfig& LddeManager::configuration() const noexcept {
    std::lock_guard lock(mutex_);
    return config_;
}

std::shared_ptr<process::Process> LddeManager::process() const noexcept {
    std::lock_guard lock(mutex_);
    return process_;
}

std::optional<std::string> LddeManager::process_handle() const noexcept {
    std::lock_guard lock(mutex_);
    return process_handle_;
}

const std::string& LddeManager::readiness_path() const noexcept {
    std::lock_guard lock(mutex_);
    return readiness_path_;
}

void LddeManager::set_supervisor(std::shared_ptr<ProcessSupervisor> supervisor) {
    std::lock_guard lock(mutex_);
    supervisor_ = std::move(supervisor);
    if (supervisor_) {
        supervisor_->register_listener(
            [this](const process::ProcessEvent& event) {
                on_process_event(event);
            });
    }
}

Result<void> LddeManager::transition_to(LddeState target, std::string reason) {
    if (!is_valid_ldde_transition(state_, target)) {
        std::string err_msg = "Invalid LDDE state transition from " +
                              std::string(to_string(state_)) + " to " +
                              std::string(to_string(target));
        LDDM_LOG_WARN(LogSubsystem::LDDE, "{}", err_msg);
        return Result<void>::failure(Error(
            ErrorCategory::Desktop,
            ErrorCode::LddeInvalidState,
            err_msg));
    }

    auto old_state = state_;
    state_ = target;
    diagnostics_.record_transition(old_state, target, reason);

    LDDM_LOG_INFO(LogSubsystem::LDDE, "LDDE transition: {} -> {} ({})",
                  to_string(old_state), to_string(target),
                  reason.empty() ? "none" : reason);

    return Result<void>::success();
}

Result<void> LddeManager::initialize(const SessionContext& context) {
    std::lock_guard lock(mutex_);

    runtime_dir_ = context.paths.runtime_dir().string();
    log_file_ = (context.paths.log_dir() / "ldde.log").string();

    std::string wayland_disp = "wayland-0";
    auto disp_opt = context.environment.get("WAYLAND_DISPLAY");
    if (disp_opt && !disp_opt->empty()) {
        wayland_disp = *disp_opt;
    }
    wayland_display_ = wayland_disp;

    std::string ready_name = (config_.readiness_mode == LddeReadinessMode::File)
                                 ? config_.readiness_file_name
                                 : config_.readiness_socket_name;
    readiness_path_ = (std::filesystem::path(runtime_dir_) / ready_name).string();

    diagnostics_.set_readiness_path(readiness_path_);
    diagnostics_.set_log_path(log_file_);
    diagnostics_.set_session_target(config_.session_target);

    if (context.supervisor) {
        supervisor_ = context.supervisor;
        supervisor_->register_listener(
            [this](const process::ProcessEvent& event) {
                on_process_event(event);
            });
    }

    // Resolve LDDE executable
    auto res_exe = LddeExecutableResolver::resolve(config_.executable);
    if (!res_exe.has_value()) {
        diagnostics_.record_error(res_exe.error());
        (void)transition_to(LddeState::Failed, "Executable resolution failed: " + res_exe.error().message());
        return Result<void>::failure(res_exe.error());
    }

    config_.executable = res_exe.value();
    diagnostics_.set_executable(config_.executable);

    LDDM_LOG_INFO(LogSubsystem::LDDE, "LDDE initialized: exe={}, target={}, readiness_path={}",
                  config_.executable, config_.session_target, readiness_path_);

    return Result<void>::success();
}

Result<void> LddeManager::prepare() {
    std::lock_guard lock(mutex_);

    if (state_ == LddeState::Created) {
        auto tr = transition_to(LddeState::Preparing, "Preparing LDDE environment");
        if (!tr.has_value()) {
            return tr;
        }
    } else if (state_ != LddeState::Preparing) {
        return Result<void>::failure(Error(
            ErrorCategory::Desktop,
            ErrorCode::LddeInvalidState,
            "Cannot prepare LDDE in state " + std::string(to_string(state_))));
    }

    // 1. Ensure runtime directory exists with 0700 permissions
    if (!runtime_dir_.empty()) {
        std::error_code ec;
        std::filesystem::create_directories(runtime_dir_, ec);
        if (ec) {
            auto err = Error(ErrorCategory::Platform, ErrorCode::PlatformPathResolution,
                             "Failed to create runtime directory: " + ec.message(),
                             "runtime_dir=" + runtime_dir_);
            diagnostics_.record_error(err);
            (void)transition_to(LddeState::Failed, err.message());
            return Result<void>::failure(err);
        }
        chmod(runtime_dir_.c_str(), 0700);
    }

    // 2. Ensure log directory exists
    if (!log_file_.empty()) {
        std::filesystem::path lp(log_file_);
        if (lp.has_parent_path()) {
            std::error_code ec;
            std::filesystem::create_directories(lp.parent_path(), ec);
        }
    }

    // 3. Remove stale readiness file from previous run
    if (!readiness_path_.empty()) {
        std::error_code ec;
        std::filesystem::remove(readiness_path_, ec);
    }

    // 4. Build LddeSpec
    spec_.executable = config_.executable;
    spec_.arguments = LddeSpec::build_arguments(config_);
    spec_.working_directory = runtime_dir_;
    spec_.session_target = config_.session_target;
    spec_.startup_timeout = std::chrono::milliseconds(config_.startup_timeout_ms);
    spec_.shutdown_timeout = std::chrono::milliseconds(config_.stop_timeout_ms);
    spec_.readiness_timeout = std::chrono::milliseconds(config_.readiness_timeout_ms);
    spec_.readiness_path = readiness_path_;
    spec_.readiness_mode = config_.readiness_mode;
    spec_.stdout_policy = process::StreamPolicy::File;
    spec_.stdout_file = log_file_;
    spec_.stderr_policy = process::StreamPolicy::File;
    spec_.stderr_file = log_file_;

    // Environment variables contract
    spec_.environment["XDG_RUNTIME_DIR"] = runtime_dir_;
    spec_.environment["WAYLAND_DISPLAY"] = wayland_display_;
    spec_.environment["XDG_SESSION_TYPE"] = "wayland";
    spec_.environment["XDG_CURRENT_DESKTOP"] = "LDDE";
    spec_.environment["XDG_SESSION_DESKTOP"] = "LDDE";
    spec_.environment["LDDE_SESSION_TARGET"] = config_.session_target;
    spec_.environment["LDDE_READINESS_FILE"] = readiness_path_;
    spec_.environment["LDDE_CONTRACT_VERSION"] = std::to_string(config_.contract_version);

    // Apply configuration overrides
    for (const auto& [k, v] : config_.environment_overrides) {
        spec_.environment[k] = v;
    }

    auto val_res = spec_.validate();
    if (!val_res.has_value()) {
        diagnostics_.record_error(val_res.error());
        (void)transition_to(LddeState::Failed, "Spec validation failed: " + val_res.error().message());
        return val_res;
    }

    LDDM_LOG_INFO(LogSubsystem::LDDE, "LDDE prepared successfully");
    return Result<void>::success();
}

Result<void> LddeManager::start() {
    std::unique_lock lock(mutex_);

    if (state_ == LddeState::Created) {
        lock.unlock();
        auto prep_res = prepare();
        if (!prep_res.has_value()) {
            return prep_res;
        }
        lock.lock();
    }

    if (state_ != LddeState::Preparing) {
        return Result<void>::failure(Error(
            ErrorCategory::Desktop,
            ErrorCode::LddeInvalidState,
            "Cannot start LDDE in state " + std::string(to_string(state_))));
    }

    if (!supervisor_) {
        auto err = Error(ErrorCategory::Desktop, ErrorCode::LddeSpawnFailed,
                         "Cannot start LDDE without an attached ProcessSupervisor");
        diagnostics_.record_error(err);
        (void)transition_to(LddeState::Failed, err.message());
        return Result<void>::failure(err);
    }

    auto tr = transition_to(LddeState::Starting, "Spawning LDDE process");
    if (!tr.has_value()) {
        return tr;
    }

    // Convert LddeSpec to ProcessSpec
    auto proc_spec = spec_.to_process_spec();
    auto spawn_res = supervisor_->start_process(proc_spec);
    if (!spawn_res.has_value()) {
        diagnostics_.record_error(spawn_res.error());
        (void)transition_to(LddeState::Failed, "Spawn failed: " + spawn_res.error().message());
        return Result<void>::failure(Error(
            ErrorCategory::Desktop,
            ErrorCode::LddeSpawnFailed,
            "Failed to spawn LDDE process: " + spawn_res.error().message()));
    }

    process_ = spawn_res.value();
    process_handle_ = process_->handle();

    diagnostics_.set_pid(process_->pid());
    diagnostics_.record_start_time();

    LDDM_LOG_INFO(LogSubsystem::LDDE, "[INFO] LDDE process started");
    LDDM_LOG_INFO(LogSubsystem::LDDE, "[INFO] Waiting for LDDE readiness");

    (void)transition_to(LddeState::WaitingReady, "Waiting for LDDE readiness");

    // Unlock mutex during blocking readiness wait to prevent deadlock
    auto ready_path_copy = readiness_path_;
    auto mode_copy = spec_.readiness_mode;
    auto pid_copy = process_->pid();
    auto timeout_copy = spec_.readiness_timeout;
    lock.unlock();

    auto ready_res = LddeReadinessDetector::wait_for_readiness(
        ready_path_copy, mode_copy, pid_copy, timeout_copy);

    lock.lock();

    if (!ready_res.has_value()) {
        diagnostics_.record_error(ready_res.error());
        (void)transition_to(LddeState::Failed, "Readiness check failed: " + ready_res.error().message());

        // Stop the failed process
        if (process_handle_ && supervisor_) {
            (void)supervisor_->stop_process(*process_handle_, std::chrono::milliseconds(1000));
        }

        cleanup_resources();
        return ready_res;
    }

    (void)transition_to(LddeState::Running, "LDDE verified ready");
    diagnostics_.record_ready_time();

    LDDM_LOG_INFO(LogSubsystem::LDDE, "[INFO] LDDE ready");
    LDDM_LOG_INFO(LogSubsystem::LDDE, "LDDE running (PID: {}, Target: {})",
                  process_->pid(), config_.session_target);

    return Result<void>::success();
}

Result<void> LddeManager::wait_until_ready(std::chrono::milliseconds timeout) {
    std::string ready_path_copy;
    LddeReadinessMode mode_copy;
    pid_t pid_copy = -1;

    {
        std::lock_guard lock(mutex_);
        if (state_ == LddeState::Running) {
            return Result<void>::success();
        }
        if (state_ != LddeState::WaitingReady) {
            return Result<void>::failure(Error(
                ErrorCategory::Desktop,
                ErrorCode::LddeInvalidState,
                "Cannot wait for readiness in state " + std::string(to_string(state_))));
        }

        ready_path_copy = readiness_path_;
        mode_copy = spec_.readiness_mode;
        pid_copy = process_ ? process_->pid() : -1;
        if (timeout == std::chrono::milliseconds(0)) {
            timeout = spec_.readiness_timeout;
        }
    }

    return LddeReadinessDetector::wait_for_readiness(ready_path_copy, mode_copy, pid_copy, timeout);
}

Result<void> LddeManager::stop() {
    std::lock_guard lock(mutex_);

    if (state_ == LddeState::Stopped) {
        return Result<void>::success();
    }

    if (state_ != LddeState::Running &&
        state_ != LddeState::WaitingReady &&
        state_ != LddeState::Starting &&
        state_ != LddeState::Preparing &&
        state_ != LddeState::Created &&
        state_ != LddeState::Failed) {
        return Result<void>::failure(Error(
            ErrorCategory::Desktop,
            ErrorCode::LddeInvalidState,
            "Cannot stop LDDE in state " + std::string(to_string(state_))));
    }

    (void)transition_to(LddeState::Stopping, "LDDE shutdown requested");

    if (supervisor_ && process_handle_) {
        (void)supervisor_->stop_process(*process_handle_, spec_.shutdown_timeout);
        process_handle_ = std::nullopt;
        process_ = nullptr;
    }

    cleanup_resources();

    diagnostics_.record_stop_time();
    (void)transition_to(LddeState::Stopped, "LDDE stopped cleanly");

    LDDM_LOG_INFO(LogSubsystem::LDDE, "LDDE stopped cleanly");
    return Result<void>::success();
}

void LddeManager::on_process_event(const process::ProcessEvent& event) {
    std::lock_guard lock(mutex_);

    if (process_ && event.pid == process_->pid()) {
        if (event.type == process::ProcessEventType::Exited ||
            event.type == process::ProcessEventType::Signaled) {
            diagnostics_.record_exit_info(event.exit_info);

            if (state_ == LddeState::Running ||
                state_ == LddeState::WaitingReady ||
                state_ == LddeState::Starting) {
                std::string reason = "LDDE process exited unexpectedly (" + event.exit_info.format() + ")";
                LDDM_LOG_ERROR(LogSubsystem::LDDE, "{}", reason);

                diagnostics_.record_error(Error(
                    ErrorCategory::Desktop,
                    ErrorCode::LddeCrash,
                    reason));

                (void)transition_to(LddeState::Failed, reason);
            }
        }
    }
}

void LddeManager::cleanup_resources() noexcept {
    if (!readiness_path_.empty()) {
        std::error_code ec;
        std::filesystem::remove(readiness_path_, ec);
    }
}

void LddeManager::reset() {
    std::lock_guard lock(mutex_);
    if (process_handle_ && supervisor_) {
        (void)supervisor_->stop_process(*process_handle_, std::chrono::milliseconds(1000));
    }
    cleanup_resources();
    process_ = nullptr;
    process_handle_ = std::nullopt;
    state_ = LddeState::Created;
}

} // namespace lddm::ldde
