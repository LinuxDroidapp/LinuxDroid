#include "lddm/logging/log_level.hpp"
#include <algorithm>

namespace lddm {

std::string_view to_string(LogLevel level) noexcept {
    switch (level) {
        case LogLevel::TRACE: return "TRACE";
        case LogLevel::DEBUG: return "DEBUG";
        case LogLevel::INFO:  return "INFO";
        case LogLevel::WARN:  return "WARN";
        case LogLevel::ERROR: return "ERROR";
        case LogLevel::FATAL: return "FATAL";
        case LogLevel::OFF:   return "OFF";
    }
    return "UNKNOWN";
}

std::optional<LogLevel> parse_log_level(std::string_view str) noexcept {
    if (str == "TRACE" || str == "trace") return LogLevel::TRACE;
    if (str == "DEBUG" || str == "debug") return LogLevel::DEBUG;
    if (str == "INFO"  || str == "info")  return LogLevel::INFO;
    if (str == "WARN"  || str == "warn" || str == "WARNING" || str == "warning") return LogLevel::WARN;
    if (str == "ERROR" || str == "error") return LogLevel::ERROR;
    if (str == "FATAL" || str == "fatal") return LogLevel::FATAL;
    if (str == "OFF"   || str == "off")   return LogLevel::OFF;
    return std::nullopt;
}

std::string_view to_string(LogSubsystem subsystem) noexcept {
    switch (subsystem) {
        case LogSubsystem::LDDM:     return "LDDM";
        case LogSubsystem::SESSION:  return "SESSION";
        case LogSubsystem::PROCESS:  return "PROCESS";
        case LogSubsystem::CONFIG:   return "CONFIG";
        case LogSubsystem::PLATFORM: return "PLATFORM";
        case LogSubsystem::WESTON:   return "WESTON";
        case LogSubsystem::LDDE:     return "LDDE";
        case LogSubsystem::RECOVERY: return "RECOVERY";
    }
    return "UNKNOWN";
}

std::optional<LogSubsystem> parse_log_subsystem(std::string_view str) noexcept {
    if (str == "LDDM"     || str == "lddm")     return LogSubsystem::LDDM;
    if (str == "SESSION"  || str == "session")  return LogSubsystem::SESSION;
    if (str == "PROCESS"  || str == "process")  return LogSubsystem::PROCESS;
    if (str == "CONFIG"   || str == "config")   return LogSubsystem::CONFIG;
    if (str == "PLATFORM" || str == "platform") return LogSubsystem::PLATFORM;
    if (str == "WESTON"   || str == "weston")   return LogSubsystem::WESTON;
    if (str == "LDDE"     || str == "ldde")     return LogSubsystem::LDDE;
    if (str == "RECOVERY" || str == "recovery") return LogSubsystem::RECOVERY;
    return std::nullopt;
}

std::ostream& operator<<(std::ostream& os, LogLevel level) {
    return os << to_string(level);
}

std::ostream& operator<<(std::ostream& os, LogSubsystem subsystem) {
    return os << to_string(subsystem);
}

} // namespace lddm
