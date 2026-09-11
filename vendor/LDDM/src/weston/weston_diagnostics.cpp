#include "lddm/weston/weston_diagnostics.hpp"

namespace lddm::weston {

void WestonDiagnostics::record_transition(WestonState from, WestonState to, std::string reason) {
    state_ = to;
    transition_history_.push_back(WestonTransitionRecord{
        .from = from,
        .to = to,
        .timestamp = std::chrono::system_clock::now(),
        .reason = std::move(reason)
    });
}

std::chrono::milliseconds WestonDiagnostics::startup_duration() const noexcept {
    if (start_time_.has_value() && ready_time_.has_value()) {
        return std::chrono::duration_cast<std::chrono::milliseconds>(*ready_time_ - *start_time_);
    }
    return std::chrono::milliseconds(0);
}

std::chrono::milliseconds WestonDiagnostics::total_runtime() const noexcept {
    if (start_time_.has_value() && stop_time_.has_value()) {
        return std::chrono::duration_cast<std::chrono::milliseconds>(*stop_time_ - *start_time_);
    }
    return std::chrono::milliseconds(0);
}

} // namespace lddm::weston
