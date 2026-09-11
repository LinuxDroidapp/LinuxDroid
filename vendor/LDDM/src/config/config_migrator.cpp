#include "lddm/config/config_migrator.hpp"
#include "lddm/core/error.hpp"
#include "lddm/logging/logger.hpp"

#include <algorithm>
#include <fstream>
#include <sstream>
#include <filesystem>

namespace lddm {

namespace {

std::string trim_view(std::string_view sv) {
    auto start = sv.find_first_not_of(" \t\r\n");
    if (start == std::string_view::npos) {
        return "";
    }
    auto end = sv.find_last_not_of(" \t\r\n");
    return std::string(sv.substr(start, end - start + 1));
}

} // namespace

std::uint32_t ConfigMigrator::detect_version(std::string_view content) noexcept {
    std::istringstream stream{std::string(content)};
    std::string line;
    std::string current_section;

    while (std::getline(stream, line)) {
        auto trimmed = trim_view(line);
        if (trimmed.empty()) {
            continue;
        }

        // Check for comment with version header: e.g. # version: 1
        if (trimmed.rfind("# version:", 0) == 0 || trimmed.rfind("# Version:", 0) == 0) {
            auto val_str = trim_view(trimmed.substr(10));
            try {
                return static_cast<std::uint32_t>(std::stoul(val_str));
            } catch (...) {
                // fall through
            }
        }

        if (trimmed.front() == '[' && trimmed.back() == ']') {
            current_section = trimmed.substr(1, trimmed.size() - 2);
            continue;
        }

        if (trimmed.front() == '#' || trimmed.front() == ';') {
            continue;
        }

        auto eq_pos = trimmed.find('=');
        if (eq_pos != std::string::npos) {
            auto key = trim_view(trimmed.substr(0, eq_pos));
            auto val = trim_view(trimmed.substr(eq_pos + 1));

            if (key == "version" && (current_section.empty() || current_section == "meta" || current_section == "core")) {
                try {
                    return static_cast<std::uint32_t>(std::stoul(val));
                } catch (...) {
                    return 0;
                }
            }
        }
    }

    return 0; // Legacy unversioned config (v0)
}

Result<std::string> ConfigMigrator::migrate_content(
    std::string_view content,
    std::uint32_t target_version) {

    std::uint32_t src_version = detect_version(content);

    if (src_version == target_version) {
        LDDM_LOG_DEBUG(LogSubsystem::CONFIG, "Configuration is already at target schema version {}", target_version);
        return Result<std::string>::success(std::string(content));
    }

    if (src_version > target_version) {
        return Result<std::string>::failure(Error(
            ErrorCategory::Configuration,
            ErrorCode::ConfigSchemaInvalid,
            "Cannot downgrade configuration: source version " + std::to_string(src_version) +
            " is newer than target version " + std::to_string(target_version)));
    }

    if (target_version > CURRENT_CONFIG_VERSION) {
        return Result<std::string>::failure(Error(
            ErrorCategory::Configuration,
            ErrorCode::ConfigSchemaInvalid,
            "Target version " + std::to_string(target_version) +
            " exceeds maximum supported version " + std::to_string(CURRENT_CONFIG_VERSION)));
    }

    std::string working(content);

    // Migration from v0 to v1:
    // 1. Inject schema metadata header with version = 1
    // 2. Preserve all user settings and sections exactly
    if (src_version == 0 && target_version >= 1) {
        LDDM_LOG_INFO(LogSubsystem::CONFIG, "Migrating configuration from v0 (unversioned) to v1");

        std::ostringstream out;
        out << "# LDDM Configuration File (Schema Version 1)\n";
        out << "[meta]\n";
        out << "version = 1\n\n";

        // Append remaining original content without duplicate version lines
        std::istringstream in{working};
        std::string line;
        bool in_meta = false;

        while (std::getline(in, line)) {
            auto trimmed = trim_view(line);
            if (trimmed.front() == '[' && trimmed.back() == ']') {
                auto sec = trimmed.substr(1, trimmed.size() - 2);
                if (sec == "meta") {
                    in_meta = true;
                    continue;
                } else {
                    in_meta = false;
                }
            }
            if (in_meta) {
                continue;
            }
            out << line << "\n";
        }

        working = out.str();
        src_version = 1;
    }

    LDDM_LOG_INFO(LogSubsystem::CONFIG, "Configuration migration completed successfully (new schema version: {})", src_version);
    return Result<std::string>::success(working);
}

Result<void> ConfigMigrator::migrate_file(
    const std::string& input_path,
    const std::string& output_path,
    std::uint32_t target_version) {

    if (!std::filesystem::exists(input_path)) {
        return Result<void>::failure(Error(
            ErrorCategory::Configuration,
            ErrorCode::ConfigFileNotFound,
            "Configuration file to migrate does not exist: " + input_path));
    }

    std::ifstream in(input_path);
    if (!in.is_open()) {
        return Result<void>::failure(Error(
            ErrorCategory::Configuration,
            ErrorCode::ConfigFileReadError,
            "Failed to open configuration file for reading: " + input_path));
    }

    std::stringstream buffer;
    buffer << in.rdbuf();
    in.close();

    auto mig_res = migrate_content(buffer.str(), target_version);
    if (!mig_res.has_value()) {
        return Result<void>::failure(mig_res.error());
    }

    std::string out_file = output_path.empty() ? input_path : output_path;
    std::string tmp_file = out_file + ".tmp." + std::to_string(::getpid());

    std::ofstream out(tmp_file, std::ios::trunc);
    if (!out.is_open()) {
        return Result<void>::failure(Error(
            ErrorCategory::Configuration,
            ErrorCode::ConfigMigrationFailed,
            "Failed to open temporary file for migrated configuration: " + tmp_file));
    }

    out << mig_res.value();
    out.close();

    std::error_code ec;
    std::filesystem::rename(tmp_file, out_file, ec);
    if (ec) {
        std::filesystem::remove(tmp_file);
        return Result<void>::failure(Error(
            ErrorCategory::Configuration,
            ErrorCode::ConfigMigrationFailed,
            "Failed to replace configuration file with migrated content: " + ec.message()));
    }

    LDDM_LOG_INFO(LogSubsystem::CONFIG, "Migrated configuration written to {}", out_file);
    return Result<void>::success();
}

} // namespace lddm
