#include "lddm/session/session_diagnostics.hpp"
#include "lddm/session/session_config.hpp"
#include "lddm/session/session_paths.hpp"
#include "lddm/session/session_environment.hpp"
#include "lddm/platform/clock.hpp"
#include <sstream>

namespace lddm {

SessionDiagnostics::SessionDiagnostics(SessionId id)
    : id_(std::move(id)) {}

void SessionDiagnostics::record_state_transition(const SessionTransitionEvent& event) {
    std::lock_guard<std::mutex> lock(mutex_);
    transitions_.push_back(event);
}

void SessionDiagnostics::record_error(Error error) {
    std::lock_guard<std::mutex> lock(mutex_);
    last_error_ = std::move(error);
}

void SessionDiagnostics::set_initialized_at(SystemTimePoint tp) {
    std::lock_guard<std::mutex> lock(mutex_);
    initialized_at_ = tp;
}

void SessionDiagnostics::set_started_at(SystemTimePoint tp) {
    std::lock_guard<std::mutex> lock(mutex_);
    started_at_ = tp;
}

void SessionDiagnostics::set_stopped_at(SystemTimePoint tp) {
    std::lock_guard<std::mutex> lock(mutex_);
    stopped_at_ = tp;
}

std::optional<Error> SessionDiagnostics::last_error() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return last_error_;
}

std::vector<SessionTransitionEvent> SessionDiagnostics::transition_history() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return transitions_;
}

std::string SessionDiagnostics::format_report(const SessionConfig& config,
                                              const SessionPaths& paths,
                                              const SessionEnvironment& env,
                                              SessionState current_state,
                                              std::size_t tracked_fds,
                                              std::size_t tracked_files) const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::ostringstream oss;

    oss << "=== Session Diagnostics: " << id_.str() << " ===\n";
    oss << "State: " << to_string(current_state) << "\n";
    oss << "Type: " << to_string(config.type) << "\n";
    oss << "User: " << config.user << " (uid: " << config.uid << ", gid: " << config.gid << ")\n";
    oss << "Created at: " << ClockUtil::format_iso8601(created_at_) << "\n";
    if (initialized_at_) {
        oss << "Initialized at: " << ClockUtil::format_iso8601(*initialized_at_) << "\n";
    }
    if (started_at_) {
        oss << "Started at: " << ClockUtil::format_iso8601(*started_at_) << "\n";
    }
    if (stopped_at_) {
        oss << "Stopped at: " << ClockUtil::format_iso8601(*stopped_at_) << "\n";
    }

    oss << "\n--- Runtime Paths ---\n";
    oss << "Session Root: " << paths.session_root().string() << "\n";
    oss << "Runtime Dir:  " << paths.runtime_dir().string() << "\n";
    oss << "Wayland Sock: " << paths.wayland_socket_path().string() << "\n";
    oss << "IPC Socket:   " << paths.ipc_socket_path().string() << "\n";
    oss << "State Dir:    " << paths.state_dir().string() << "\n";
    oss << "Log Dir:      " << paths.log_dir().string() << "\n";

    oss << "\n--- Tracked Resources ---\n";
    oss << "Open File Descriptors: " << tracked_fds << "\n";
    oss << "Temporary Files:       " << tracked_files << "\n";

    if (last_error_) {
        oss << "\n--- Last Error ---\n";
        oss << last_error_->to_string() << "\n";
    }

    oss << "\n--- State Transition History (" << transitions_.size() << " events) ---\n";
    for (const auto& ev : transitions_) {
        oss << "  [" << ClockUtil::format_iso8601(ev.timestamp) << "] "
            << to_string(ev.from) << " -> " << to_string(ev.to)
            << (ev.reason.empty() ? "" : (" (reason: " + ev.reason + ")")) << "\n";
    }

    oss << "\n--- Environment Summary (Redacted) ---\n";
    auto redacted = env.redacted_summary();
    for (const auto& [k, v] : redacted) {
        oss << "  " << k << "=" << v << "\n";
    }

    return oss.str();
}

} // namespace lddm

