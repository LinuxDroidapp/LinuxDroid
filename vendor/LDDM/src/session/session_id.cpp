#include "lddm/session/session_id.hpp"
#include <atomic>
#include <chrono>
#include <ctime>
#include <iomanip>
#include <sstream>
#include <ostream>

namespace lddm {

namespace {
std::atomic<std::uint64_t> g_session_counter{1};
} // namespace

SessionId::SessionId(std::string id)
    : id_(std::move(id)) {}

SessionId SessionId::generate(std::string_view prefix) {
    const auto now = SystemClock::now();
    const auto time_c = SystemClock::to_time_t(now);
    std::tm tm_buf{};
    gmtime_r(&time_c, &tm_buf);

    const auto seq = g_session_counter.fetch_add(1, std::memory_order_relaxed);

    std::ostringstream oss;
    oss << prefix << '-'
        << std::put_time(&tm_buf, "%Y%m%d-%H%M%S")
        << '-' << seq;
    return SessionId(oss.str());
}

std::ostream& operator<<(std::ostream& os, const SessionId& session_id) {
    return os << session_id.str();
}

} // namespace lddm

