#include "lddm/ldde/ldde_config.hpp"
#include "lddm/core/error.hpp"
#include <filesystem>
#include <cstdlib>
#include <unistd.h>

namespace lddm::ldde {

LddeConfig LddeConfig::create_default() {
    return LddeConfig{};
}

Result<void> LddeConfig::validate() const {
    if (executable.empty()) {
        return Result<void>::failure(Error(
            ErrorCategory::Desktop,
            ErrorCode::LddeConfigError,
            "LDDE executable cannot be empty"));
    }

    if (startup_timeout_ms == 0) {
        return Result<void>::failure(Error(
            ErrorCategory::Desktop,
            ErrorCode::LddeConfigError,
            "LDDE startup_timeout_ms must be greater than zero"));
    }

    if (stop_timeout_ms == 0) {
        return Result<void>::failure(Error(
            ErrorCategory::Desktop,
            ErrorCode::LddeConfigError,
            "LDDE stop_timeout_ms must be greater than zero"));
    }

    if (readiness_timeout_ms == 0) {
        return Result<void>::failure(Error(
            ErrorCategory::Desktop,
            ErrorCode::LddeConfigError,
            "LDDE readiness_timeout_ms must be greater than zero"));
    }

    if (readiness_mode == LddeReadinessMode::File && readiness_file_name.empty()) {
        return Result<void>::failure(Error(
            ErrorCategory::Desktop,
            ErrorCode::LddeConfigError,
            "LDDE readiness_file_name cannot be empty in File readiness mode"));
    }

    if (readiness_mode == LddeReadinessMode::Socket && readiness_socket_name.empty()) {
        return Result<void>::failure(Error(
            ErrorCategory::Desktop,
            ErrorCode::LddeConfigError,
            "LDDE readiness_socket_name cannot be empty in Socket readiness mode"));
    }

    return Result<void>::success();
}

Result<std::string> LddeExecutableResolver::resolve(const std::string& preferred_path) {
    if (!preferred_path.empty()) {
        if (access(preferred_path.c_str(), X_OK) == 0) {
            return Result<std::string>::success(preferred_path);
        }
        // If preferred_path is not one of the standard default names, fail immediately
        if (preferred_path != "/usr/bin/ldde" && preferred_path != "/usr/bin/ldde-session" &&
            preferred_path != "ldde" && preferred_path != "ldde-session") {
            return Result<std::string>::failure(Error(
                ErrorCategory::Desktop,
                ErrorCode::LddeExecutableNotFound,
                "Configured LDDE executable not found or not executable: " + preferred_path,
                "executable=" + preferred_path));
        }
    }

    // Standard Linux paths
    const std::vector<std::string> standard_paths = {
        "/usr/bin/ldde",
        "/usr/bin/ldde-session",
        "/usr/local/bin/ldde",
        "/usr/local/bin/ldde-session",
        "/opt/linuxdroid/bin/ldde",
        "/opt/linuxdroid/bin/ldde-session"
    };

    for (const auto& path : standard_paths) {
        if (access(path.c_str(), X_OK) == 0) {
            return Result<std::string>::success(path);
        }
    }

    // Search PATH
    const char* path_env = std::getenv("PATH");
    if (path_env != nullptr) {
        std::string path_str(path_env);
        std::size_t start = 0;
        while (start < path_str.length()) {
            std::size_t end = path_str.find(':', start);
            if (end == std::string::npos) {
                end = path_str.length();
            }

            std::string dir = path_str.substr(start, end - start);
            if (!dir.empty()) {
                std::filesystem::path candidate1 = std::filesystem::path(dir) / "ldde";
                if (access(candidate1.c_str(), X_OK) == 0) {
                    return Result<std::string>::success(candidate1.string());
                }
                std::filesystem::path candidate2 = std::filesystem::path(dir) / "ldde-session";
                if (access(candidate2.c_str(), X_OK) == 0) {
                    return Result<std::string>::success(candidate2.string());
                }
            }

            start = end + 1;
        }
    }

    return Result<std::string>::failure(Error(
        ErrorCategory::Desktop,
        ErrorCode::LddeExecutableNotFound,
        "LDDE executable could not be found in PATH or standard locations"));
}

} // namespace lddm::ldde
