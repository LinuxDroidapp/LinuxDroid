#include "lddm/recovery/recovery_types.hpp"
#include <ostream>

namespace lddm::recovery {

std::string_view to_string(RecoveryState state) noexcept {
    switch (state) {
        case RecoveryState::Idle:       return "IDLE";
        case RecoveryState::Recovering: return "RECOVERING";
        case RecoveryState::Verifying:  return "VERIFYING";
        case RecoveryState::Recovered:  return "RECOVERED";
        case RecoveryState::Failed:     return "FAILED";
    }
    return "UNKNOWN";
}

bool is_valid_recovery_transition(RecoveryState from, RecoveryState to) noexcept {
    if (from == to) {
        return true;
    }

    switch (from) {
        case RecoveryState::Idle:
            return to == RecoveryState::Recovering || to == RecoveryState::Failed;

        case RecoveryState::Recovering:
            return to == RecoveryState::Verifying || to == RecoveryState::Failed;

        case RecoveryState::Verifying:
            return to == RecoveryState::Recovered || to == RecoveryState::Failed;

        case RecoveryState::Recovered:
            return to == RecoveryState::Idle || to == RecoveryState::Recovering || to == RecoveryState::Failed;

        case RecoveryState::Failed:
            return to == RecoveryState::Idle || to == RecoveryState::Recovering;
    }

    return false;
}

std::ostream& operator<<(std::ostream& os, RecoveryState state) {
    return os << to_string(state);
}

std::string_view to_string(RecoveryReason reason) noexcept {
    switch (reason) {
        case RecoveryReason::WestonStartFailure:     return "WESTON_START_FAILURE";
        case RecoveryReason::WestonUnexpectedExit:   return "WESTON_UNEXPECTED_EXIT";
        case RecoveryReason::WestonReadinessTimeout: return "WESTON_READINESS_TIMEOUT";
        case RecoveryReason::LddeStartFailure:       return "LDDE_START_FAILURE";
        case RecoveryReason::LddeUnexpectedExit:     return "LDDE_UNEXPECTED_EXIT";
        case RecoveryReason::LddeReadinessTimeout:   return "LDDE_READINESS_TIMEOUT";
        case RecoveryReason::ProcessFailure:         return "PROCESS_FAILURE";
        case RecoveryReason::SessionStartFailure:    return "SESSION_START_FAILURE";
        case RecoveryReason::SessionRuntimeFailure:  return "SESSION_RUNTIME_FAILURE";
        case RecoveryReason::SessionShutdownFailure: return "SESSION_SHUTDOWN_FAILURE";
        case RecoveryReason::RuntimeResourceFailure: return "RUNTIME_RESOURCE_FAILURE";
        case RecoveryReason::Unknown:                return "UNKNOWN";
    }
    return "UNKNOWN";
}

std::ostream& operator<<(std::ostream& os, RecoveryReason reason) {
    return os << to_string(reason);
}

std::string_view to_string(RecoveryPolicy policy) noexcept {
    switch (policy) {
        case RecoveryPolicy::NoRecovery:       return "NO_RECOVERY";
        case RecoveryPolicy::ComponentRestart: return "COMPONENT_RESTART";
        case RecoveryPolicy::SessionRestart:   return "SESSION_RESTART";
        case RecoveryPolicy::FailSession:      return "FAIL_SESSION";
    }
    return "UNKNOWN";
}

std::ostream& operator<<(std::ostream& os, RecoveryPolicy policy) {
    return os << to_string(policy);
}

} // namespace lddm::recovery
