#include "lddm/recovery/recovery_config.hpp"
#include "lddm/core/error.hpp"
#include <cmath>
#include <algorithm>

namespace lddm::recovery {

RecoveryConfig RecoveryConfig::create_default() noexcept {
    return RecoveryConfig{};
}

Result<void> RecoveryConfig::validate() const {
    if (max_attempts == 0) {
        return Result<void>::failure(Error(
            ErrorCategory::Recovery,
            ErrorCode::RecoveryPreconditionFailed,
            "max_attempts must be greater than 0"));
    }

    if (window_ms < 100) {
        return Result<void>::failure(Error(
            ErrorCategory::Recovery,
            ErrorCode::RecoveryPreconditionFailed,
            "window_ms must be at least 100ms"));
    }

    if (backoff_multiplier < 1.0) {
        return Result<void>::failure(Error(
            ErrorCategory::Recovery,
            ErrorCode::RecoveryPreconditionFailed,
            "backoff_multiplier must be >= 1.0"));
    }

    if (backoff_max_ms < backoff_initial_ms) {
        return Result<void>::failure(Error(
            ErrorCategory::Recovery,
            ErrorCode::RecoveryPreconditionFailed,
            "backoff_max_ms must be >= backoff_initial_ms"));
    }

    return Result<void>::success();
}

std::chrono::milliseconds RecoveryConfig::calculate_backoff(std::uint32_t attempt) const noexcept {
    if (attempt == 0 || backoff_initial_ms == 0) {
        return std::chrono::milliseconds(0);
    }

    double delay = static_cast<double>(backoff_initial_ms) *
                   std::pow(backoff_multiplier, static_cast<double>(attempt - 1));

    std::uint32_t delay_ms = static_cast<std::uint32_t>(std::min<double>(delay, static_cast<double>(backoff_max_ms)));
    return std::chrono::milliseconds(delay_ms);
}

} // namespace lddm::recovery
