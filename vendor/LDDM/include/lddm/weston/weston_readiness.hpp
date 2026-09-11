#pragma once

#include "lddm/core/result.hpp"
#include <string>
#include <string_view>
#include <chrono>
#include <sys/types.h>

namespace lddm::weston {

enum class WaylandSocketStatus {
    SocketNotCreated,
    SocketCreatedUnusable,
    WaylandConnectionUsable,
    WestonExited
};

[[nodiscard]] std::string_view to_string(WaylandSocketStatus status) noexcept;

class WestonReadinessDetector {
public:
    [[nodiscard]] static WaylandSocketStatus check_socket(
        const std::string& socket_path,
        pid_t pid);

    [[nodiscard]] static Result<void> wait_for_readiness(
        const std::string& socket_path,
        pid_t pid,
        std::chrono::milliseconds timeout,
        std::chrono::milliseconds poll_interval = std::chrono::milliseconds(20));
};

} // namespace lddm::weston
