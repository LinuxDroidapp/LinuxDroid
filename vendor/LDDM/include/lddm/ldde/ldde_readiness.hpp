#pragma once

#include "lddm/core/result.hpp"
#include "lddm/ldde/ldde_types.hpp"
#include <string>
#include <chrono>
#include <sys/types.h>

namespace lddm::ldde {

enum class LddeReadinessStatus {
    NotReady,
    Ready,
    ProcessExited,
    ProtocolError
};

[[nodiscard]] std::string_view to_string(LddeReadinessStatus status) noexcept;

class LddeReadinessDetector {
public:
    [[nodiscard]] static LddeReadinessStatus check_readiness(
        const std::string& path,
        LddeReadinessMode mode,
        pid_t pid);

    [[nodiscard]] static Result<void> wait_for_readiness(
        const std::string& path,
        LddeReadinessMode mode,
        pid_t pid,
        std::chrono::milliseconds timeout,
        std::chrono::milliseconds poll_interval = std::chrono::milliseconds(20));
};

} // namespace lddm::ldde
