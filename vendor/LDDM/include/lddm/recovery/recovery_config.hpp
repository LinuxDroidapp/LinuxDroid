#pragma once

#include "lddm/core/result.hpp"
#include "lddm/recovery/recovery_types.hpp"
#include <cstdint>
#include <chrono>

namespace lddm::recovery {

struct RecoveryConfig {
    bool enabled{true};
    std::uint32_t max_attempts{3};
    std::uint32_t window_ms{60000};
    std::uint32_t backoff_initial_ms{50};
    double backoff_multiplier{2.0};
    std::uint32_t backoff_max_ms{1000};
    bool allow_component_restart{true};
    bool allow_session_restart{true};
    RecoveryPolicy default_policy{RecoveryPolicy::ComponentRestart};

    [[nodiscard]] Result<void> validate() const;
    [[nodiscard]] std::chrono::milliseconds calculate_backoff(std::uint32_t attempt) const noexcept;
    [[nodiscard]] static RecoveryConfig create_default() noexcept;
};

} // namespace lddm::recovery
