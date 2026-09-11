#include "lddm/logging/log_message.hpp"
#include <sstream>
#include <iomanip>

namespace lddm {

namespace {

std::string_view level_color(LogLevel level) noexcept {
    switch (level) {
        case LogLevel::TRACE: return "\033[36m";      // Cyan
        case LogLevel::DEBUG: return "\033[34m";      // Blue
        case LogLevel::INFO:  return "\033[32m";      // Green
        case LogLevel::WARN:  return "\033[33m";      // Yellow
        case LogLevel::ERROR: return "\033[31m";      // Red
        case LogLevel::FATAL: return "\033[1;35m";    // Bold Magenta
        case LogLevel::OFF:   return "";
    }
    return "";
}

constexpr std::string_view COLOR_RESET = "\033[0m";
constexpr std::string_view COLOR_DIM   = "\033[2m";

} // namespace

std::string LogMessage::format(bool colorize) const {
    std::ostringstream oss;

    // Convert timestamp to calendar time
    const auto time_c = std::chrono::system_clock::to_time_t(timestamp);
    std::tm tm_buf{};
    gmtime_r(&time_c, &tm_buf);

    const auto duration = timestamp.time_since_epoch();
    const auto millis = std::chrono::duration_cast<std::chrono::milliseconds>(duration) % 1000;

    if (colorize) {
        oss << COLOR_DIM;
    }

    oss << std::put_time(&tm_buf, "%Y-%m-%dT%H:%M:%S")
        << '.' << std::setfill('0') << std::setw(3) << millis.count() << "Z";

    if (colorize) {
        oss << COLOR_RESET;
    }

    oss << " [tid:" << thread_id << "] ";

    if (colorize) {
        oss << level_color(level);
    }
    oss << "[" << std::setfill(' ') << std::setw(5) << std::left << to_string(level) << "]";
    if (colorize) {
        oss << COLOR_RESET;
    }

    oss << " [" << to_string(subsystem) << "] " << text;

    if (!file_name.empty()) {
        if (colorize) {
            oss << COLOR_DIM;
        }
        oss << " (" << file_name << ":" << line << ")";
        if (colorize) {
            oss << COLOR_RESET;
        }
    }

    return oss.str();
}

} // namespace lddm
