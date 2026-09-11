#include "lddm/weston/weston_types.hpp"
#include <ostream>

namespace lddm::weston {

std::string_view to_string(WestonState state) noexcept {
    switch (state) {
        case WestonState::Created:      return "CREATED";
        case WestonState::Preparing:    return "PREPARING";
        case WestonState::Starting:     return "STARTING";
        case WestonState::WaitingReady: return "WAITING_READY";
        case WestonState::Running:      return "RUNNING";
        case WestonState::Stopping:     return "STOPPING";
        case WestonState::Stopped:      return "STOPPED";
        case WestonState::Failed:       return "FAILED";
    }
    return "UNKNOWN";
}

std::ostream& operator<<(std::ostream& os, WestonState state) {
    return os << to_string(state);
}

bool is_valid_weston_transition(WestonState from, WestonState to) noexcept {
    if (from == to) {
        return true;
    }

    switch (from) {
        case WestonState::Created:
            return to == WestonState::Preparing || to == WestonState::Stopping || to == WestonState::Failed;

        case WestonState::Preparing:
            return to == WestonState::Starting || to == WestonState::Stopping || to == WestonState::Failed;

        case WestonState::Starting:
            return to == WestonState::WaitingReady || to == WestonState::Stopping || to == WestonState::Failed;

        case WestonState::WaitingReady:
            return to == WestonState::Running || to == WestonState::Stopping || to == WestonState::Failed;

        case WestonState::Running:
            return to == WestonState::Stopping || to == WestonState::Failed;

        case WestonState::Stopping:
            return to == WestonState::Stopped || to == WestonState::Failed;

        case WestonState::Failed:
            return to == WestonState::Stopped;

        case WestonState::Stopped:
            return false; // Terminal state
    }

    return false;
}

} // namespace lddm::weston
