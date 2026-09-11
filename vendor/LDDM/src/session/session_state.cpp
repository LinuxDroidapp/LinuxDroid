#include "lddm/session/session_state.hpp"
#include "lddm/logging/logger.hpp"
#include <ostream>

namespace lddm {

std::string_view to_string(SessionState state) noexcept {
    switch (state) {
        case SessionState::CREATED:      return "CREATED";
        case SessionState::INITIALIZING: return "INITIALIZING";
        case SessionState::READY:        return "READY";
        case SessionState::STARTING:     return "STARTING";
        case SessionState::RUNNING:      return "RUNNING";
        case SessionState::STOPPING:     return "STOPPING";
        case SessionState::STOPPED:      return "STOPPED";
        case SessionState::FAILED:       return "FAILED";
        case SessionState::RECOVERING:   return "RECOVERING";
    }
    return "UNKNOWN";
}

bool is_valid_session_transition(SessionState from, SessionState to) noexcept {
    if (from == to) {
        return true;
    }

    switch (from) {
        case SessionState::CREATED:
            return to == SessionState::INITIALIZING || to == SessionState::FAILED;

        case SessionState::INITIALIZING:
            return to == SessionState::READY || to == SessionState::FAILED;

        case SessionState::READY:
            return to == SessionState::STARTING || to == SessionState::STOPPING || to == SessionState::FAILED;

        case SessionState::STARTING:
            return to == SessionState::RUNNING || to == SessionState::STOPPING || to == SessionState::FAILED || to == SessionState::RECOVERING;

        case SessionState::RUNNING:
            return to == SessionState::STOPPING || to == SessionState::FAILED || to == SessionState::RECOVERING;

        case SessionState::STOPPING:
            return to == SessionState::STOPPED || to == SessionState::FAILED;

        case SessionState::STOPPED:
            // Allow re-initialization / restart from STOPPED
            return to == SessionState::INITIALIZING || to == SessionState::RECOVERING;

        case SessionState::FAILED:
            // Allow recovery restart from FAILED
            return to == SessionState::INITIALIZING || to == SessionState::RECOVERING;

        case SessionState::RECOVERING:
            return to == SessionState::RUNNING || to == SessionState::STARTING || to == SessionState::INITIALIZING || to == SessionState::STOPPING || to == SessionState::FAILED;
    }

    return false;
}

std::ostream& operator<<(std::ostream& os, SessionState state) {
    return os << to_string(state);
}

SessionStateMachine::SessionStateMachine(std::string session_id_str, SessionState initial_state)
    : session_id_(std::move(session_id_str))
    , current_state_(initial_state) {
    history_.push_back(SessionTransitionEvent{
        .from = initial_state,
        .to = initial_state,
        .reason = "Initial state",
        .timestamp = SystemClock::now()
    });
}

SessionState SessionStateMachine::state() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return current_state_;
}

bool SessionStateMachine::is_terminal() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return current_state_ == SessionState::STOPPED || current_state_ == SessionState::FAILED;
}

bool SessionStateMachine::is_running() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return current_state_ == SessionState::RUNNING;
}

bool SessionStateMachine::is_active() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return current_state_ == SessionState::STARTING ||
           current_state_ == SessionState::RUNNING ||
           current_state_ == SessionState::STOPPING ||
           current_state_ == SessionState::RECOVERING;
}

bool SessionStateMachine::can_transition_to(SessionState target) const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return is_valid_session_transition(current_state_, target);
}

Result<void> SessionStateMachine::transition_to(SessionState target, std::string reason) {
    std::vector<SessionStateObserver> observers_copy;
    SessionTransitionEvent event;

    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (current_state_ == target) {
            return Result<void>::success();
        }

        if (!is_valid_session_transition(current_state_, target)) {
            std::string err_msg = "Invalid session state transition for session '" + session_id_ + "' from ";
            err_msg += to_string(current_state_);
            err_msg += " to ";
            err_msg += to_string(target);
            if (!reason.empty()) {
                err_msg += " (reason: ";
                err_msg += reason;
                err_msg += ")";
            }
            LDDM_LOG_ERROR(LogSubsystem::SESSION, "{}", err_msg);
            return Result<void>::failure(Error(
                ErrorCategory::Session,
                ErrorCode::SessionInvalidState,
                std::move(err_msg),
                "session_id=" + session_id_));
        }

        event.from = current_state_;
        event.to = target;
        event.reason = std::move(reason);
        event.timestamp = SystemClock::now();

        current_state_ = target;
        history_.push_back(event);
        observers_copy = observers_;
    }

    LDDM_LOG_INFO(LogSubsystem::SESSION, "[Session '{}'] State transition: {} -> {}{}",
                  session_id_,
                  to_string(event.from),
                  to_string(event.to),
                  event.reason.empty() ? "" : (" (reason: " + event.reason + ")"));

    for (const auto& observer : observers_copy) {
        if (observer) {
            observer(event);
        }
    }

    return Result<void>::success();
}

void SessionStateMachine::register_observer(SessionStateObserver observer) {
    std::lock_guard<std::mutex> lock(mutex_);
    observers_.push_back(std::move(observer));
}

void SessionStateMachine::clear_observers() {
    std::lock_guard<std::mutex> lock(mutex_);
    observers_.clear();
}

std::vector<SessionTransitionEvent> SessionStateMachine::history() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return history_;
}

} // namespace lddm
