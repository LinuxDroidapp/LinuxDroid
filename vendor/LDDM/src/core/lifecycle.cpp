#include "lddm/core/lifecycle.hpp"
#include "lddm/logging/logger.hpp"

namespace lddm {

std::string_view to_string(LifecycleState state) noexcept {
    switch (state) {
        case LifecycleState::CREATED:      return "CREATED";
        case LifecycleState::INITIALIZING: return "INITIALIZING";
        case LifecycleState::READY:        return "READY";
        case LifecycleState::STARTING:     return "STARTING";
        case LifecycleState::RUNNING:      return "RUNNING";
        case LifecycleState::STOPPING:     return "STOPPING";
        case LifecycleState::STOPPED:      return "STOPPED";
        case LifecycleState::FAILED:       return "FAILED";
    }
    return "UNKNOWN";
}

std::ostream& operator<<(std::ostream& os, LifecycleState state) {
    return os << to_string(state);
}

LifecycleStateMachine::LifecycleStateMachine(LifecycleState initial_state)
    : current_state_(initial_state) {}

LifecycleState LifecycleStateMachine::state() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return current_state_;
}

bool LifecycleStateMachine::is_terminal() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return current_state_ == LifecycleState::STOPPED || current_state_ == LifecycleState::FAILED;
}

bool LifecycleStateMachine::is_running() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return current_state_ == LifecycleState::RUNNING;
}

bool LifecycleStateMachine::is_valid_transition(LifecycleState from, LifecycleState to) noexcept {
    if (from == to) {
        return true;
    }

    switch (from) {
        case LifecycleState::CREATED:
            return to == LifecycleState::INITIALIZING;

        case LifecycleState::INITIALIZING:
            return to == LifecycleState::READY || to == LifecycleState::FAILED;

        case LifecycleState::READY:
            return to == LifecycleState::STARTING || to == LifecycleState::STOPPING || to == LifecycleState::FAILED;

        case LifecycleState::STARTING:
            return to == LifecycleState::RUNNING || to == LifecycleState::STOPPING || to == LifecycleState::FAILED;

        case LifecycleState::RUNNING:
            return to == LifecycleState::STOPPING || to == LifecycleState::FAILED;

        case LifecycleState::STOPPING:
            return to == LifecycleState::STOPPED || to == LifecycleState::FAILED;

        case LifecycleState::STOPPED:
            // Allow restarting from STOPPED
            return to == LifecycleState::INITIALIZING;

        case LifecycleState::FAILED:
            // Allow recovery restart from FAILED
            return to == LifecycleState::INITIALIZING;
    }

    return false;
}

bool LifecycleStateMachine::can_transition_to(LifecycleState target) const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return is_valid_transition(current_state_, target);
}

Result<void> LifecycleStateMachine::transition_to(LifecycleState target, std::string reason) {
    std::vector<StateChangeCallback> callbacks_copy;
    StateTransitionEvent event;

    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (current_state_ == target) {
            return Result<void>::success();
        }

        if (!is_valid_transition(current_state_, target)) {
            std::string err_msg = "Invalid lifecycle state transition from ";
            err_msg += to_string(current_state_);
            err_msg += " to ";
            err_msg += to_string(target);
            if (!reason.empty()) {
                err_msg += " (reason: ";
                err_msg += reason;
                err_msg += ")";
            }
            LDDM_LOG_ERROR(LogSubsystem::LDDM, "{}", err_msg);
            return Result<void>::failure(Error(ErrorCategory::Internal,
                                              ErrorCode::InternalInvalidState,
                                              std::move(err_msg)));
        }

        event.from = current_state_;
        event.to = target;
        event.reason = std::move(reason);
        current_state_ = target;
        callbacks_copy = observers_;
    }

    LDDM_LOG_INFO(LogSubsystem::LDDM, "Lifecycle transition: {} -> {}{}",
                  to_string(event.from),
                  to_string(event.to),
                  event.reason.empty() ? "" : (" (reason: " + event.reason + ")"));

    for (const auto& observer : callbacks_copy) {
        if (observer) {
            observer(event);
        }
    }

    return Result<void>::success();
}

void LifecycleStateMachine::register_observer(StateChangeCallback callback) {
    std::lock_guard<std::mutex> lock(mutex_);
    observers_.push_back(std::move(callback));
}

void LifecycleStateMachine::clear_observers() {
    std::lock_guard<std::mutex> lock(mutex_);
    observers_.clear();
}

} // namespace lddm
