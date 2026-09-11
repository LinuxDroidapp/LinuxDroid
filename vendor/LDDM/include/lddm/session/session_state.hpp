#pragma once

#include "lddm/core/result.hpp"
#include "lddm/core/types.hpp"
#include <cstdint>
#include <string_view>
#include <string>
#include <iosfwd>
#include <functional>
#include <mutex>
#include <vector>

namespace lddm {

enum class SessionState : std::uint8_t {
    CREATED      = 0,
    INITIALIZING = 1,
    READY        = 2,
    STARTING     = 3,
    RUNNING      = 4,
    STOPPING     = 5,
    STOPPED      = 6,
    FAILED       = 7,
    RECOVERING   = 8
};

// Backwards compatibility alias
using SessionLifecycleState = SessionState;

[[nodiscard]] std::string_view to_string(SessionState state) noexcept;
[[nodiscard]] bool is_valid_session_transition(SessionState from, SessionState to) noexcept;
std::ostream& operator<<(std::ostream& os, SessionState state);

struct SessionTransitionEvent {
    SessionState from{SessionState::CREATED};
    SessionState to{SessionState::CREATED};
    std::string reason{};
    SystemTimePoint timestamp{SystemClock::now()};
};

using SessionStateObserver = std::function<void(const SessionTransitionEvent&)>;

class SessionStateMachine {
public:
    explicit SessionStateMachine(std::string session_id_str, SessionState initial_state = SessionState::CREATED);
    ~SessionStateMachine() = default;

    SessionStateMachine(const SessionStateMachine&) = delete;
    SessionStateMachine& operator=(const SessionStateMachine&) = delete;
    SessionStateMachine(SessionStateMachine&&) = delete;
    SessionStateMachine& operator=(SessionStateMachine&&) = delete;

    [[nodiscard]] SessionState state() const noexcept;
    [[nodiscard]] bool is_terminal() const noexcept;
    [[nodiscard]] bool is_running() const noexcept;
    [[nodiscard]] bool is_active() const noexcept;

    [[nodiscard]] bool can_transition_to(SessionState target) const noexcept;

    Result<void> transition_to(SessionState target, std::string reason = "");

    void register_observer(SessionStateObserver observer);
    void clear_observers();

    [[nodiscard]] std::vector<SessionTransitionEvent> history() const;

private:
    std::string session_id_;
    mutable std::mutex mutex_;
    SessionState current_state_{SessionState::CREATED};
    std::vector<SessionStateObserver> observers_;
    std::vector<SessionTransitionEvent> history_;
};

} // namespace lddm
