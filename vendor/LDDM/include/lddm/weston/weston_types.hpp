#pragma once

#include <cstdint>
#include <string_view>
#include <iosfwd>

namespace lddm::weston {

enum class WestonState : std::uint8_t {
    Created      = 0,
    Preparing    = 1,
    Starting     = 2,
    WaitingReady = 3,
    Running      = 4,
    Stopping     = 5,
    Stopped      = 6,
    Failed       = 7
};

[[nodiscard]] std::string_view to_string(WestonState state) noexcept;
std::ostream& operator<<(std::ostream& os, WestonState state);

[[nodiscard]] bool is_valid_weston_transition(WestonState from, WestonState to) noexcept;

} // namespace lddm::weston
