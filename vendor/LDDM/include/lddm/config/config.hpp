#pragma once

#include "lddm/config/config_types.hpp"
#include "lddm/config/config_parser.hpp"
#include "lddm/config/config_validator.hpp"
#include "lddm/core/result.hpp"
#include <optional>
#include <string>

namespace lddm {

class ConfigManager {
public:
    ConfigManager();
    explicit ConfigManager(LddmConfig initial_config);

    [[nodiscard]] const LddmConfig& config() const noexcept { return config_; }
    void set_config(LddmConfig config) noexcept { config_ = std::move(config); }

    Result<void> load_file(const std::string& filepath);
    Result<void> load_defaults();
    Result<void> load_standard(const std::optional<std::string>& override_path = std::nullopt);

    [[nodiscard]] static std::string default_system_config_path();
    [[nodiscard]] static std::string default_user_config_path();

private:
    LddmConfig config_;
    ConfigParser parser_;
};

} // namespace lddm

