#pragma once

#include <cstdint>
#include <string_view>
#include <iosfwd>

namespace lddm::ldde {

enum class LddeState : std::uint8_t {
    Created      = 0,
    Preparing    = 1,
    Starting     = 2,
    WaitingReady = 3,
    Running      = 4,
    Stopping     = 5,
    Stopped      = 6,
    Failed       = 7
};

enum class LddeReadinessMode : std::uint8_t {
    File   = 0,
    Socket = 1
};

[[nodiscard]] std::string_view to_string(LddeState state) noexcept;
std::ostream& operator<<(std::ostream& os, LddeState state);

[[nodiscard]] std::string_view to_string(LddeReadinessMode mode) noexcept;

[[nodiscard]] bool is_valid_ldde_transition(LddeState from, LddeState to) noexcept;

} // namespace lddm::ldde
