#pragma once

#include "lddm/config/config_types.hpp"
#include "lddm/core/result.hpp"
#include <string_view>
#include <string>

namespace lddm {

class ConfigParser {
public:
    ConfigParser() = default;

    Result<LddmConfig> parse_string(std::string_view content, const LddmConfig& base = LddmConfig::create_default());
    Result<LddmConfig> parse_file(const std::string& filepath, const LddmConfig& base = LddmConfig::create_default());
};

} // namespace lddm

