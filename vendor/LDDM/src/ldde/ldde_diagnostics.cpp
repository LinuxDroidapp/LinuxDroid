#include "lddm/ldde/ldde_diagnostics.hpp"
#include <sstream>

namespace lddm::ldde {

void LddeDiagnostics::record_transition(LddeState from, LddeState to, std::string reason) {
    std::lock_guard lock(mutex_);
    transitions_.push_back(LddeTransitionRecord{
        .from = from,
        .to = to,
        .timestamp = SystemClock::now(),
        .reason = std::move(reason)
    });
}

void LddeDiagnostics::record_start_time() {
    std::lock_guard lock(mutex_);
    start_time_ = SystemClock::now();
}

void LddeDiagnostics::record_ready_time() {
    std::lock_guard lock(mutex_);
    ready_time_ = SystemClock::now();
}

void LddeDiagnostics::record_stop_time() {
    std::lock_guard lock(mutex_);
    stop_time_ = SystemClock::now();
}

void LddeDiagnostics::record_exit_info(const process::ProcessExitInfo& exit_info) {
    std::lock_guard lock(mutex_);
    exit_info_ = exit_info;
}

void LddeDiagnostics::record_error(const Error& error) {
    std::lock_guard lock(mutex_);
    errors_.push_back(error);
}

void LddeDiagnostics::set_executable(std::string exe) {
    std::lock_guard lock(mutex_);
    executable_ = std::move(exe);
}

void LddeDiagnostics::set_session_target(std::string target) {
    std::lock_guard lock(mutex_);
    session_target_ = std::move(target);
}

void LddeDiagnostics::set_readiness_path(std::string path) {
    std::lock_guard lock(mutex_);
    readiness_path_ = std::move(path);
}

void LddeDiagnostics::set_log_path(std::string path) {
    std::lock_guard lock(mutex_);
    log_path_ = std::move(path);
}

void LddeDiagnostics::set_pid(pid_t pid) {
    std::lock_guard lock(mutex_);
    pid_ = pid;
}

std::vector<LddeTransitionRecord> LddeDiagnostics::transitions() const {
    std::lock_guard lock(mutex_);
    return transitions_;
}

std::optional<SystemTimePoint> LddeDiagnostics::start_time() const {
    std::lock_guard lock(mutex_);
    return start_time_;
}

std::optional<SystemTimePoint> LddeDiagnostics::ready_time() const {
    std::lock_guard lock(mutex_);
    return ready_time_;
}

std::optional<SystemTimePoint> LddeDiagnostics::stop_time() const {
    std::lock_guard lock(mutex_);
    return stop_time_;
}

std::chrono::milliseconds LddeDiagnostics::startup_duration() const {
    std::lock_guard lock(mutex_);
    if (start_time_ && ready_time_) {
        return std::chrono::duration_cast<std::chrono::milliseconds>(*ready_time_ - *start_time_);
    }
    return std::chrono::milliseconds(0);
}

std::chrono::milliseconds LddeDiagnostics::active_duration() const {
    std::lock_guard lock(mutex_);
    if (start_time_) {
        auto end = stop_time_.value_or(SystemClock::now());
        return std::chrono::duration_cast<std::chrono::milliseconds>(end - *start_time_);
    }
    return std::chrono::milliseconds(0);
}

std::optional<pid_t> LddeDiagnostics::pid() const {
    std::lock_guard lock(mutex_);
    return pid_;
}

std::optional<process::ProcessExitInfo> LddeDiagnostics::exit_info() const {
    std::lock_guard lock(mutex_);
    return exit_info_;
}

std::vector<Error> LddeDiagnostics::errors() const {
    std::lock_guard lock(mutex_);
    return errors_;
}

std::optional<Error> LddeDiagnostics::last_error() const {
    std::lock_guard lock(mutex_);
    if (errors_.empty()) {
        return std::nullopt;
    }
    return errors_.back();
}

std::string LddeDiagnostics::executable() const {
    std::lock_guard lock(mutex_);
    return executable_;
}

std::string LddeDiagnostics::session_target() const {
    std::lock_guard lock(mutex_);
    return session_target_;
}

std::string LddeDiagnostics::readiness_path() const {
    std::lock_guard lock(mutex_);
    return readiness_path_;
}

std::string LddeDiagnostics::log_path() const {
    std::lock_guard lock(mutex_);
    return log_path_;
}

std::string LddeDiagnostics::format_report() const {
    std::lock_guard lock(mutex_);
    std::ostringstream oss;
    oss << "=== LDDE Diagnostics Report ===\n";
    oss << "Executable: " << (executable_.empty() ? "<none>" : executable_) << "\n";
    oss << "Target: " << (session_target_.empty() ? "<default>" : session_target_) << "\n";
    oss << "Readiness Path: " << (readiness_path_.empty() ? "<none>" : readiness_path_) << "\n";
    oss << "Log: " << (log_path_.empty() ? "<none>" : log_path_) << "\n";
    oss << "PID: " << (pid_ ? std::to_string(*pid_) : "<none>") << "\n";
    if (start_time_ && ready_time_) {
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(*ready_time_ - *start_time_).count();
        oss << "Startup Duration: " << ms << " ms\n";
    }
    oss << "Transitions (" << transitions_.size() << "):\n";
    for (const auto& tr : transitions_) {
        oss << "  - " << to_string(tr.from) << " -> " << to_string(tr.to)
            << " [" << (tr.reason.empty() ? "none" : tr.reason) << "]\n";
    }
    oss << "Errors: " << errors_.size() << "\n";
    for (const auto& err : errors_) {
        oss << "  - " << err.to_string() << "\n";
    }
    return oss.str();
}

} // namespace lddm::ldde
