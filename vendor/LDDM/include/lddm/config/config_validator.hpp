#pragma once

#include "lddm/config/config_types.hpp"
#include "lddm/core/result.hpp"

namespace lddm {

class ConfigValidator {
public:
    [[nodiscard]] static Result<void> validate(const LddmConfig& config);
};

} // namespace lddm

