#include "lddm/ldde/ldde_types.hpp"
#include <ostream>

namespace lddm::ldde {

std::string_view to_string(LddeState state) noexcept {
    switch (state) {
        case LddeState::Created:      return "CREATED";
        case LddeState::Preparing:    return "PREPARING";
        case LddeState::Starting:     return "STARTING";
        case LddeState::WaitingReady: return "WAITING_READY";
        case LddeState::Running:      return "RUNNING";
        case LddeState::Stopping:     return "STOPPING";
        case LddeState::Stopped:      return "STOPPED";
        case LddeState::Failed:       return "FAILED";
    }
    return "UNKNOWN";
}

std::ostream& operator<<(std::ostream& os, LddeState state) {
    return os << to_string(state);
}

std::string_view to_string(LddeReadinessMode mode) noexcept {
    switch (mode) {
        case LddeReadinessMode::File:   return "File";
        case LddeReadinessMode::Socket: return "Socket";
    }
    return "Unknown";
}

bool is_valid_ldde_transition(LddeState from, LddeState to) noexcept {
    if (from == to) {
        return true;
    }

    switch (from) {
        case LddeState::Created:
            return to == LddeState::Preparing || to == LddeState::Stopping || to == LddeState::Failed;

        case LddeState::Preparing:
            return to == LddeState::Starting || to == LddeState::Stopping || to == LddeState::Failed;

        case LddeState::Starting:
            return to == LddeState::WaitingReady || to == LddeState::Stopping || to == LddeState::Failed;

        case LddeState::WaitingReady:
            return to == LddeState::Running || to == LddeState::Stopping || to == LddeState::Failed;

        case LddeState::Running:
            return to == LddeState::Stopping || to == LddeState::Failed;

        case LddeState::Stopping:
            return to == LddeState::Stopped || to == LddeState::Failed;

        case LddeState::Failed:
            return to == LddeState::Stopped || to == LddeState::Stopping;

        case LddeState::Stopped:
            return false; // Terminal state
    }

    return false;
}

} // namespace lddm::ldde
