#pragma once

#include <cstdint>
#include <string_view>
#include <iosfwd>

namespace lddm::recovery {

enum class RecoveryState : std::uint8_t {
    Idle       = 0,
    Recovering = 1,
    Verifying  = 2,
    Recovered  = 3,
    Failed     = 4
};

[[nodiscard]] std::string_view to_string(RecoveryState state) noexcept;
[[nodiscard]] bool is_valid_recovery_transition(RecoveryState from, RecoveryState to) noexcept;
std::ostream& operator<<(std::ostream& os, RecoveryState state);

enum class RecoveryReason : std::uint32_t {
    WestonStartFailure      = 1,
    WestonUnexpectedExit    = 2,
    WestonReadinessTimeout  = 3,
    LddeStartFailure        = 4,
    LddeUnexpectedExit      = 5,
    LddeReadinessTimeout    = 6,
    ProcessFailure          = 7,
    SessionStartFailure     = 8,
    SessionRuntimeFailure   = 9,
    SessionShutdownFailure  = 10,
    RuntimeResourceFailure  = 11,
    Unknown                 = 99
};

[[nodiscard]] std::string_view to_string(RecoveryReason reason) noexcept;
std::ostream& operator<<(std::ostream& os, RecoveryReason reason);

enum class RecoveryPolicy : std::uint8_t {
    NoRecovery       = 0,
    ComponentRestart = 1,
    SessionRestart   = 2,
    FailSession      = 3
};

[[nodiscard]] std::string_view to_string(RecoveryPolicy policy) noexcept;
std::ostream& operator<<(std::ostream& os, RecoveryPolicy policy);

} // namespace lddm::recovery
