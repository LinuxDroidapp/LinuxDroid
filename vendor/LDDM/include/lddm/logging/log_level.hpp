#pragma once

#include <cstdint>
#include <string_view>
#include <optional>
#include <iosfwd>

namespace lddm {

enum class LogLevel : std::uint8_t {
    TRACE = 0,
    DEBUG = 1,
    INFO  = 2,
    WARN  = 3,
    ERROR = 4,
    FATAL = 5,
    OFF   = 6
};

[[nodiscard]] std::string_view to_string(LogLevel level) noexcept;
[[nodiscard]] std::optional<LogLevel> parse_log_level(std::string_view str) noexcept;
std::ostream& operator<<(std::ostream& os, LogLevel level);

enum class LogSubsystem : std::uint8_t {
    LDDM     = 0,
    SESSION  = 1,
    PROCESS  = 2,
    CONFIG   = 3,
    PLATFORM = 4,
    WESTON   = 5,
    LDDE     = 6,
    RECOVERY = 7
};

[[nodiscard]] std::string_view to_string(LogSubsystem subsystem) noexcept;
[[nodiscard]] std::optional<LogSubsystem> parse_log_subsystem(std::string_view str) noexcept;
std::ostream& operator<<(std::ostream& os, LogSubsystem subsystem);

} // namespace lddm
