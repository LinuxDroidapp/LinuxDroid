#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <chrono>
#include <compare>

namespace lddm {

using ProcessId = std::int32_t;
using UserId = std::uint32_t;
using GroupId = std::uint32_t;

class SessionId;

using Clock = std::chrono::steady_clock;
using TimePoint = Clock::time_point;
using SystemClock = std::chrono::system_clock;
using SystemTimePoint = SystemClock::time_point;

} // namespace lddm

