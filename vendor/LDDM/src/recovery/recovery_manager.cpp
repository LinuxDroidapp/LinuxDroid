#include "lddm/recovery/recovery_manager.hpp"
#include "lddm/session/session.hpp"
#include "lddm/weston/weston_manager.hpp"
#include "lddm/ldde/ldde_manager.hpp"
#include "lddm/logging/logger.hpp"

#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <cstring>
#include <filesystem>
#include <thread>

namespace lddm::recovery {

RecoveryManager::RecoveryManager(Session* session, RecoveryConfig config)
    : session_(session)
    , config_(config)
    , diagnostics_(session ? session->id().str() : "") {
}

RecoveryState RecoveryManager::state() const noexcept {
    std::lock_guard lock(mutex_);
    return state_;
}

const RecoveryDiagnostics& RecoveryManager::diagnostics() const noexcept {
    std::lock_guard lock(mutex_);
    return diagnostics_;
}

const RecoveryConfig& RecoveryManager::config() const noexcept {
    std::lock_guard lock(mutex_);
    return config_;
}

void RecoveryManager::set_config(RecoveryConfig config) {
    std::lock_guard lock(mutex_);
    config_ = config;
}

void RecoveryManager::reset() {
    std::lock_guard lock(mutex_);
    state_ = RecoveryState::Idle;
    attempt_timestamps_.clear();
    diagnostics_.set_current_state(RecoveryState::Idle);
}

void RecoveryManager::purge_expired_attempts(const SystemTimePoint& now) {
    auto window = std::chrono::milliseconds(config_.window_ms);
    auto it = attempt_timestamps_.begin();
    while (it != attempt_timestamps_.end()) {
        if (now - *it > window) {
            it = attempt_timestamps_.erase(it);
        } else {
            ++it;
        }
    }
}

Result<void> RecoveryManager::validate_preconditions(RecoveryReason /*reason*/, const std::string& /*component*/) {
    if (!session_) {
        return Result<void>::failure(Error(
            ErrorCategory::Recovery,
            ErrorCode::RecoveryPreconditionFailed,
            "No active Session attached to RecoveryManager"));
    }

    if (!config_.enabled) {
        return Result<void>::failure(Error(
            ErrorCategory::Recovery,
            ErrorCode::RecoveryNotAllowed,
            "Session recovery is disabled in configuration"));
    }

    auto cfg_val = config_.validate();
    if (!cfg_val.has_value()) {
        return cfg_val;
    }

    if (!session_->supervisor()) {
        return Result<void>::failure(Error(
            ErrorCategory::Recovery,
            ErrorCode::RecoveryPreconditionFailed,
            "ProcessSupervisor is not initialized in Session"));
    }

    return Result<void>::success();
}

RecoveryPolicy RecoveryManager::determine_policy(RecoveryReason reason,
                                                const std::string& component,
                                                std::uint32_t attempt) {
    if (!config_.enabled) {
        return RecoveryPolicy::NoRecovery;
    }

    if (attempt > config_.max_attempts) {
        return RecoveryPolicy::FailSession;
    }

    if (reason == RecoveryReason::SessionStartFailure ||
        reason == RecoveryReason::SessionRuntimeFailure ||
        reason == RecoveryReason::SessionShutdownFailure) {
        return config_.allow_session_restart ? RecoveryPolicy::SessionRestart : RecoveryPolicy::FailSession;
    }

    if (component == "weston" || component == "ldde") {
        if (config_.allow_component_restart) {
            return RecoveryPolicy::ComponentRestart;
        }
        if (config_.allow_session_restart) {
            return RecoveryPolicy::SessionRestart;
        }
        return RecoveryPolicy::FailSession;
    }

    return config_.default_policy;
}

bool RecoveryManager::is_wayland_socket_active(const std::string& socket_path) noexcept {
    if (socket_path.empty()) {
        return false;
    }

    std::error_code ec;
    if (!std::filesystem::exists(socket_path, ec)) {
        return false;
    }

    int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        return false;
    }

    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, socket_path.c_str(), sizeof(addr.sun_path) - 1);

    int ret = ::connect(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    int err = errno;
    ::close(fd);

    if (ret == 0) {
        return true;
    }

    if (err == EINPROGRESS || err == EAGAIN) {
        return true;
    }

    return false;
}

void RecoveryManager::clean_stale_wayland_socket(const std::string& socket_path) {
    if (socket_path.empty()) {
        return;
    }

    std::error_code ec;
    if (std::filesystem::exists(socket_path, ec)) {
        if (!is_wayland_socket_active(socket_path)) {
            LDDM_LOG_WARN(LogSubsystem::RECOVERY, "Removing stale Wayland socket: {}", socket_path);
            std::filesystem::remove(socket_path, ec);
        }
    }

    std::string lock_file = socket_path + ".lock";
    if (std::filesystem::exists(lock_file, ec)) {
        std::filesystem::remove(lock_file, ec);
    }
}

void RecoveryManager::clean_stale_ldde_resources() {
    if (!session_) {
        return;
    }

    std::error_code ec;
    auto desk = dynamic_cast<ldde::LddeManager*>(session_->desktop());
    if (desk && !desk->readiness_path().empty()) {
        std::filesystem::remove(desk->readiness_path(), ec);
    }

    auto runtime_dir = session_->paths().runtime_dir();
    if (runtime_dir.empty()) {
        return;
    }

    const std::string candidate_files[] = {
        "ldde-ready", "ldde.ready", "ldde-session.ready",
        "ldde-ready.sock", "ldde.ready.sock", "ldde-session.ready.sock"
    };
    for (const auto& name : candidate_files) {
        auto p = runtime_dir / name;
        if (std::filesystem::exists(p, ec)) {
            std::filesystem::remove(p, ec);
        }
    }
}

Result<void> RecoveryManager::verify_graphical_session_readiness() {
    if (!session_) {
        return Result<void>::failure(Error(
            ErrorCategory::Recovery,
            ErrorCode::RecoveryReadinessFailed,
            "No active session to verify"));
    }

    auto comp = session_->compositor();
    if (comp) {
        if (!comp->is_running() || !is_wayland_socket_active(comp->socket_path())) {
            return Result<void>::failure(Error(
                ErrorCategory::Recovery,
                ErrorCode::RecoveryReadinessFailed,
                "Compositor is not operational after recovery"));
        }
    }

    auto desk = session_->desktop();
    if (desk) {
        if (!desk->is_running()) {
            return Result<void>::failure(Error(
                ErrorCategory::Recovery,
                ErrorCode::RecoveryReadinessFailed,
                "Desktop environment is not operational after recovery"));
        }
    }

    return Result<void>::success();
}

Result<void> RecoveryManager::recover_ldde_component(RecoveryReason /*reason*/) {
    LDDM_LOG_WARN(LogSubsystem::RECOVERY, "[WARN] LDDE exited unexpectedly");
    LDDM_LOG_WARN(LogSubsystem::RECOVERY, "[WARN] Graphical session degraded");
    if (session_) {
        session_->write_state_file("GRAPHICAL_SESSION_RECOVERING");
    }

    auto comp = session_->compositor();
    if (comp && (!comp->is_running() || !is_wayland_socket_active(comp->socket_path()))) {
        LDDM_LOG_WARN(LogSubsystem::RECOVERY, "Weston compositor is not ready; escalating LDDE failure to Weston recovery");
        return recover_weston_component(RecoveryReason::WestonUnexpectedExit);
    }

    auto desk = dynamic_cast<ldde::LddeManager*>(session_->desktop());
    if (!desk) {
        return Result<void>::failure(Error(
            ErrorCategory::Recovery,
            ErrorCode::RecoveryPreconditionFailed,
            "No LddeManager desktop attached to session"));
    }

    // 1. Quiesce LDDE
    LDDM_LOG_INFO(LogSubsystem::RECOVERY, "[INFO] Stopping LDDE");
    (void)desk->stop();

    // 2. Clean LDDE resources
    LDDM_LOG_INFO(LogSubsystem::RECOVERY, "[INFO] Cleaning stale LDDE resources");
    desk->cleanup_resources();
    clean_stale_ldde_resources();

    // 3. Reset LDDE state for clean restart
    desk->reset();

    // 4. Verify & restart
    state_ = RecoveryState::Verifying;
    diagnostics_.set_current_state(RecoveryState::Verifying);

    LDDM_LOG_INFO(LogSubsystem::RECOVERY, "[INFO] Restarting LDDE");
    if (session_) {
        session_->write_state_file("LDDE_STARTING");
    }

    auto start_res = desk->start();
    if (!start_res.has_value()) {
        LDDM_LOG_ERROR(LogSubsystem::RECOVERY, "Failed to restart LDDE: {}", start_res.error().message());
        return start_res;
    }

    auto ready_res = desk->wait_until_ready();
    if (!ready_res.has_value()) {
        LDDM_LOG_ERROR(LogSubsystem::RECOVERY, "LDDE failed readiness check after restart: {}", ready_res.error().message());
        return ready_res;
    }

    LDDM_LOG_INFO(LogSubsystem::RECOVERY, "[INFO] LDDE ready");
    if (session_) {
        session_->write_state_file("LDDE_READY");
    }

    auto v_res = verify_graphical_session_readiness();
    if (v_res.has_value()) {
        LDDM_LOG_INFO(LogSubsystem::RECOVERY, "[INFO] GUI ready");
        if (session_) {
            session_->write_state_file("GUI_READY");
        }
    }
    return v_res;
}

Result<void> RecoveryManager::recover_weston_component(RecoveryReason /*reason*/) {
    LDDM_LOG_WARN(LogSubsystem::RECOVERY, "[WARN] Weston exited unexpectedly");
    LDDM_LOG_WARN(LogSubsystem::RECOVERY, "[WARN] Graphical session degraded");
    if (session_) {
        session_->write_state_file("GRAPHICAL_SESSION_RECOVERING");
    }

    auto comp = dynamic_cast<weston::WestonManager*>(session_->compositor());
    if (!comp) {
        return Result<void>::failure(Error(
            ErrorCategory::Recovery,
            ErrorCode::RecoveryPreconditionFailed,
            "No WestonManager compositor attached to session"));
    }

    auto desk = dynamic_cast<ldde::LddeManager*>(session_->desktop());

    // 1. Stop LDDE first (reverse dependency ordering)
    if (desk) {
        LDDM_LOG_INFO(LogSubsystem::RECOVERY, "[INFO] Stopping LDDE");
        (void)desk->stop();
        desk->cleanup_resources();
        clean_stale_ldde_resources();
    }

    // 2. Stop Weston
    (void)comp->stop();
    comp->cleanup_resources();
    LDDM_LOG_INFO(LogSubsystem::RECOVERY, "[INFO] Cleaning stale Wayland state");
    clean_stale_wayland_socket(comp->socket_path());

    // 3. Reset Weston
    comp->reset();

    // 4. Verifying state
    state_ = RecoveryState::Verifying;
    diagnostics_.set_current_state(RecoveryState::Verifying);

    LDDM_LOG_INFO(LogSubsystem::RECOVERY, "[INFO] Restarting Weston");
    if (session_) {
        session_->write_state_file("WESTON_STARTING");
    }

    // 5. Restart Weston
    auto weston_start = comp->start();
    if (!weston_start.has_value()) {
        LDDM_LOG_ERROR(LogSubsystem::RECOVERY, "Failed to restart Weston: {}", weston_start.error().message());
        return weston_start;
    }

    auto weston_ready = comp->wait_until_ready();
    if (!weston_ready.has_value()) {
        LDDM_LOG_ERROR(LogSubsystem::RECOVERY, "Weston failed readiness check after restart: {}", weston_ready.error().message());
        return weston_ready;
    }

    LDDM_LOG_INFO(LogSubsystem::RECOVERY, "[INFO] Weston ready");
    if (session_) {
        session_->write_state_file("WESTON_READY");
    }

    // 6. Restart LDDE against the recovered compositor
    if (desk) {
        desk->reset();
        LDDM_LOG_INFO(LogSubsystem::RECOVERY, "[INFO] Restarting LDDE");
        if (session_) {
            session_->write_state_file("LDDE_STARTING");
        }

        auto ldde_start = desk->start();
        if (!ldde_start.has_value()) {
            LDDM_LOG_ERROR(LogSubsystem::RECOVERY, "Failed to restart LDDE against recovered Weston: {}", ldde_start.error().message());
            return ldde_start;
        }

        auto ldde_ready = desk->wait_until_ready();
        if (!ldde_ready.has_value()) {
            LDDM_LOG_ERROR(LogSubsystem::RECOVERY, "LDDE failed readiness after Weston recovery: {}", ldde_ready.error().message());
            return ldde_ready;
        }

        LDDM_LOG_INFO(LogSubsystem::RECOVERY, "[INFO] LDDE ready");
        if (session_) {
            session_->write_state_file("LDDE_READY");
        }
    }

    auto v_res = verify_graphical_session_readiness();
    if (v_res.has_value()) {
        LDDM_LOG_INFO(LogSubsystem::RECOVERY, "[INFO] GUI ready");
        if (session_) {
            session_->write_state_file("GUI_READY");
        }
    }
    return v_res;
}

Result<void> RecoveryManager::execute_session_restart(RecoveryReason /*reason*/) {
    LDDM_LOG_INFO(LogSubsystem::RECOVERY, "Executing full session restart");

    auto comp = dynamic_cast<weston::WestonManager*>(session_->compositor());
    auto desk = dynamic_cast<ldde::LddeManager*>(session_->desktop());

    // 1. Quiesce all components in reverse order
    if (desk) {
        (void)desk->stop();
        desk->cleanup_resources();
    }
    if (comp) {
        (void)comp->stop();
        comp->cleanup_resources();
    }

    if (session_->supervisor()) {
        (void)session_->supervisor()->stop_all();
        session_->supervisor()->reset();
        session_->supervisor()->set_base_environment(session_->environment().to_vector());
    }

    clean_stale_ldde_resources();
    if (comp) {
        clean_stale_wayland_socket(comp->socket_path());
        comp->reset();
    }
    if (desk) {
        desk->reset();
    }

    // 2. Reset session state to READY
    auto s_state = session_->state();
    if (s_state == SessionState::RECOVERING || s_state == SessionState::RUNNING || s_state == SessionState::STOPPING) {
        (void)session_->transition_to(SessionState::STOPPED, "Quiesced for session restart");
    }

    // From STOPPED / FAILED transition to INITIALIZING -> READY
    auto init_trans = session_->transition_to(SessionState::INITIALIZING, "Re-initializing session");
    if (!init_trans.has_value()) {
        return init_trans;
    }

    auto ctx = session_->context();
    if (comp) {
        comp->set_supervisor(session_->supervisor());
        (void)comp->initialize(ctx);
    }
    if (desk) {
        desk->set_supervisor(session_->supervisor());
        (void)desk->initialize(ctx);
    }

    auto ready_trans = session_->transition_to(SessionState::READY, "Session ready for restart");
    if (!ready_trans.has_value()) {
        return ready_trans;
    }

    state_ = RecoveryState::Verifying;
    diagnostics_.set_current_state(RecoveryState::Verifying);

    auto start_res = session_->start();
    if (!start_res.has_value()) {
        LDDM_LOG_ERROR(LogSubsystem::RECOVERY, "Full session restart failed: {}", start_res.error().message());
        return start_res;
    }

    return verify_graphical_session_readiness();
}

Result<void> RecoveryManager::execute_recovery(RecoveryReason reason,
                                              const std::string& component,
                                              RecoveryPolicy policy) {
    if (policy == RecoveryPolicy::ComponentRestart) {
        if (component == "ldde") {
            return recover_ldde_component(reason);
        }
        if (component == "weston") {
            return recover_weston_component(reason);
        }
        return execute_session_restart(reason);
    }

    if (policy == RecoveryPolicy::SessionRestart) {
        return execute_session_restart(reason);
    }

    return Result<void>::failure(Error(
        ErrorCategory::Recovery,
        ErrorCode::RecoveryNotAllowed,
        "Cannot execute recovery with policy " + std::string(to_string(policy))));
}

Result<void> RecoveryManager::recover(RecoveryReason reason, const std::string& component) {
    std::unique_lock lock(mutex_);

    if (state_ == RecoveryState::Recovering || state_ == RecoveryState::Verifying) {
        LDDM_LOG_WARN(LogSubsystem::RECOVERY, "Recovery request ignored: recovery already in progress (state: {})",
                      to_string(state_));
        return Result<void>::failure(Error(
            ErrorCategory::Recovery,
            ErrorCode::RecoveryInProgress,
            "Recovery operation is currently active"));
    }

    std::string comp = component;
    if (comp.empty()) {
        if (reason == RecoveryReason::WestonStartFailure ||
            reason == RecoveryReason::WestonUnexpectedExit ||
            reason == RecoveryReason::WestonReadinessTimeout) {
            comp = "weston";
        } else if (reason == RecoveryReason::LddeStartFailure ||
                   reason == RecoveryReason::LddeUnexpectedExit ||
                   reason == RecoveryReason::LddeReadinessTimeout) {
            comp = "ldde";
        } else {
            comp = "session";
        }
    }

    auto now = SystemClock::now();
    purge_expired_attempts(now);

    auto pre_res = validate_preconditions(reason, comp);
    if (!pre_res.has_value()) {
        LDDM_LOG_ERROR(LogSubsystem::RECOVERY, "Preconditions failed for recovery: {}", pre_res.error().message());
        diagnostics_.record_recovery_failure(current_recovery_id_, comp, reason, RecoveryPolicy::FailSession, 0, pre_res.error(), std::chrono::milliseconds(0));
        state_ = RecoveryState::Failed;
        if (session_) {
            (void)session_->fail(pre_res.error());
        }
        return pre_res;
    }

    attempt_timestamps_.push_back(now);
    std::uint32_t attempt = static_cast<std::uint32_t>(attempt_timestamps_.size());
    current_recovery_id_++;

    auto policy = determine_policy(reason, comp, attempt);
    LDDM_LOG_INFO(LogSubsystem::RECOVERY, "Failure detected: {} (component: {}, attempt: {}/{}, policy: {})",
                  to_string(reason), comp, attempt, config_.max_attempts, to_string(policy));

    if (policy == RecoveryPolicy::FailSession || attempt > config_.max_attempts) {
        auto err = Error(
            ErrorCategory::Recovery,
            ErrorCode::RecoveryExhausted,
            "Recovery attempts exhausted (" + std::to_string(attempt) + "/" + std::to_string(config_.max_attempts) + ") for " + comp);
        LDDM_LOG_ERROR(LogSubsystem::RECOVERY, "{}", err.message());
        diagnostics_.record_recovery_failure(current_recovery_id_, comp, reason, policy, attempt, err, std::chrono::milliseconds(0));
        state_ = RecoveryState::Failed;
        if (session_) {
            session_->write_state_file("GRAPHICAL_SESSION_FAILED");
            (void)session_->fail(err);
        }
        return Result<void>::failure(err);
    }

    if (policy == RecoveryPolicy::NoRecovery) {
        auto err = Error(
            ErrorCategory::Recovery,
            ErrorCode::RecoveryNotAllowed,
            "Recovery policy is NoRecovery for " + comp);
        diagnostics_.record_recovery_failure(current_recovery_id_, comp, reason, policy, attempt, err, std::chrono::milliseconds(0));
        state_ = RecoveryState::Failed;
        if (session_) {
            session_->write_state_file("GRAPHICAL_SESSION_FAILED");
            (void)session_->fail(err);
        }
        return Result<void>::failure(err);
    }

    state_ = RecoveryState::Recovering;
    diagnostics_.record_recovery_start(current_recovery_id_, comp, reason, policy, attempt);

    if (session_ && session_->state() != SessionState::FAILED && session_->state() != SessionState::STOPPED) {
        (void)session_->transition_to(SessionState::RECOVERING, "Recovery in progress: " + std::string(to_string(reason)));
    }

    auto backoff = config_.calculate_backoff(attempt);
    if (backoff.count() > 0) {
        LDDM_LOG_INFO(LogSubsystem::RECOVERY, "Applying backoff delay of {}ms before retry", backoff.count());
        lock.unlock();
        std::this_thread::sleep_for(backoff);
        lock.lock();
    }

    auto start_time = SystemClock::now();
    auto exec_res = execute_recovery(reason, comp, policy);
    auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(SystemClock::now() - start_time);

    if (!exec_res.has_value()) {
        LDDM_LOG_ERROR(LogSubsystem::RECOVERY, "Recovery attempt #{} failed for {}: {}",
                       attempt, comp, exec_res.error().message());
        diagnostics_.record_recovery_failure(current_recovery_id_, comp, reason, policy, attempt, exec_res.error(), duration);
        state_ = RecoveryState::Failed;
        if (session_) {
            session_->write_state_file("GRAPHICAL_SESSION_FAILED");
            (void)session_->fail(exec_res.error());
        }
        return exec_res;
    }

    state_ = RecoveryState::Recovered;
    diagnostics_.record_recovery_success(current_recovery_id_, comp, reason, policy, attempt, duration);

    if (session_ && session_->state() == SessionState::RECOVERING) {
        (void)session_->transition_to(SessionState::RUNNING, "Session recovery completed successfully");
    }

    LDDM_LOG_INFO(LogSubsystem::RECOVERY, "Session successfully recovered (component: {}, duration: {}ms)",
                  comp, duration.count());
    return Result<void>::success();
}

Result<void> RecoveryManager::recover_component(const std::string& component, RecoveryReason reason) {
    return recover(reason, component);
}

Result<void> RecoveryManager::restart_session(RecoveryReason reason) {
    std::unique_lock lock(mutex_);
    auto pre_res = validate_preconditions(reason, "session");
    if (!pre_res.has_value()) {
        return pre_res;
    }
    return recover(reason, "session");
}

void RecoveryManager::on_process_event(const process::ProcessEvent& event) {
    std::unique_lock lock(mutex_);

    if (state_ == RecoveryState::Recovering || state_ == RecoveryState::Verifying) {
        return;
    }

    if (!session_ || session_->state() != SessionState::RUNNING) {
        return;
    }

    if (event.type == process::ProcessEventType::Exited ||
        event.type == process::ProcessEventType::Signaled) {
        if (event.process_name.find("weston") != std::string::npos) {
            lock.unlock();
            (void)recover(RecoveryReason::WestonUnexpectedExit, "weston");
        } else if (event.process_name.find("ldde") != std::string::npos) {
            lock.unlock();
            (void)recover(RecoveryReason::LddeUnexpectedExit, "ldde");
        }
    }
}

} // namespace lddm::recovery
