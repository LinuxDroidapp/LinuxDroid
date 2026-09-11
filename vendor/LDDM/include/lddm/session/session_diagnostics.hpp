#pragma once

#include "lddm/session/session_id.hpp"
#include "lddm/session/session_state.hpp"
#include "lddm/core/error.hpp"
#include <optional>
#include <vector>
#include <string>
#include <unordered_map>
#include <mutex>

namespace lddm {

class SessionPaths;
class SessionEnvironment;
struct SessionConfig;

class SessionDiagnostics {
public:
    explicit SessionDiagnostics(SessionId id);

    void record_state_transition(const SessionTransitionEvent& event);
    void record_error(Error error);
    void set_initialized_at(SystemTimePoint tp);
    void set_started_at(SystemTimePoint tp);
    void set_stopped_at(SystemTimePoint tp);

    [[nodiscard]] const SessionId& id() const noexcept { return id_; }
    [[nodiscard]] std::optional<Error> last_error() const;
    [[nodiscard]] std::vector<SessionTransitionEvent> transition_history() const;

    [[nodiscard]] std::string format_report(const SessionConfig& config,
                                            const SessionPaths& paths,
                                            const SessionEnvironment& env,
                                            SessionState current_state,
                                            std::size_t tracked_fds,
                                            std::size_t tracked_files) const;

private:
    SessionId id_;
    mutable std::mutex mutex_;
    std::optional<Error> last_error_;
    std::vector<SessionTransitionEvent> transitions_;
    SystemTimePoint created_at_{SystemClock::now()};
    std::optional<SystemTimePoint> initialized_at_;
    std::optional<SystemTimePoint> started_at_;
    std::optional<SystemTimePoint> stopped_at_;
};

} // namespace lddm

