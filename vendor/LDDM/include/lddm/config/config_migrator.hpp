#pragma once

#include "lddm/core/result.hpp"
#include <cstdint>
#include <string>
#include <string_view>

namespace lddm {

class ConfigMigrator {
public:
    static constexpr std::uint32_t CURRENT_CONFIG_VERSION = 1;

    /// Detect the configuration schema version from file content.
    /// Returns 0 for legacy unversioned configs.
    [[nodiscard]] static std::uint32_t detect_version(std::string_view content) noexcept;

    /// Migrate in-memory configuration content to the target version (default: CURRENT_CONFIG_VERSION).
    /// Preserves existing user settings, comments, and structure while adapting schema.
    [[nodiscard]] static Result<std::string> migrate_content(
        std::string_view content,
        std::uint32_t target_version = CURRENT_CONFIG_VERSION);

    /// Migrate an existing configuration file on disk and write the result to output_path.
    /// If output_path is empty, overwrites input_path atomically.
    [[nodiscard]] static Result<void> migrate_file(
        const std::string& input_path,
        const std::string& output_path = "",
        std::uint32_t target_version = CURRENT_CONFIG_VERSION);
};

} // namespace lddm

