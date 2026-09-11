#include "lddm/platform/clock.hpp"
#include <ctime>
#include <iomanip>
#include <sstream>

namespace lddm {

std::uint64_t ClockUtil::monotonic_nanos() noexcept {
    struct timespec ts{};
    if (::clock_gettime(CLOCK_MONOTONIC, &ts) == 0) {
        return static_cast<std::uint64_t>(ts.tv_sec) * 1'000'000'000ULL +
               static_cast<std::uint64_t>(ts.tv_nsec);
    }
    return 0;
}

std::uint64_t ClockUtil::monotonic_millis() noexcept {
    return monotonic_nanos() / 1'000'000ULL;
}

std::string ClockUtil::format_iso8601(SystemTimePoint tp) {
    const auto time_c = std::chrono::system_clock::to_time_t(tp);
    std::tm tm_buf{};
    gmtime_r(&time_c, &tm_buf);

    const auto duration = tp.time_since_epoch();
    const auto millis = std::chrono::duration_cast<std::chrono::milliseconds>(duration) % 1000;

    std::ostringstream oss;
    oss << std::put_time(&tm_buf, "%Y-%m-%dT%H:%M:%S")
        << '.' << std::setfill('0') << std::setw(3) << millis.count() << "Z";
    return oss.str();
}

std::string ClockUtil::current_time_iso8601() {
    return format_iso8601(std::chrono::system_clock::now());
}

} // namespace lddm

