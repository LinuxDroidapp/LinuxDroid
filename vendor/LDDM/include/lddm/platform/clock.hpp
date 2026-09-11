#pragma once

#include "lddm/core/types.hpp"
#include <chrono>
#include <string>

namespace lddm {

class ClockUtil {
public:
    [[nodiscard]] static std::uint64_t monotonic_nanos() noexcept;
    [[nodiscard]] static std::uint64_t monotonic_millis() noexcept;

    [[nodiscard]] static std::string format_iso8601(SystemTimePoint tp);
    [[nodiscard]] static std::string current_time_iso8601();
};

} // namespace lddm

