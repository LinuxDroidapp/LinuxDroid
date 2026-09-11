#include "lddm/weston/weston_config.hpp"
#include "lddm/core/error.hpp"
#include <filesystem>
#include <fstream>
#include <sstream>
#include <cstdlib>
#include <unistd.h>

namespace lddm::weston {

WestonConfig WestonConfig::create_default() {
    return WestonConfig{};
}

Result<void> WestonConfig::validate() const {
    if (socket_name.empty()) {
        return Result<void>::failure(Error(
            ErrorCategory::Compositor,
            ErrorCode::CompositorConfigError,
            "Weston socket_name cannot be empty"));
    }

    if (backend.empty()) {
        return Result<void>::failure(Error(
            ErrorCategory::Compositor,
            ErrorCode::CompositorConfigError,
            "Weston backend cannot be empty"));
    }

    if (startup_timeout_ms == 0) {
        return Result<void>::failure(Error(
            ErrorCategory::Compositor,
            ErrorCode::CompositorConfigError,
            "Weston startup_timeout_ms must be greater than zero"));
    }

    return Result<void>::success();
}

std::string WestonConfigWriter::generate_ini_content(const WestonConfig& config) {
    std::ostringstream oss;
    oss << "# LDDM Auto-Generated Weston Configuration\n\n";
    oss << "[core]\n";
    oss << "idle-time=" << config.idle_time_seconds << "\n";
    oss << "require-input=" << (config.require_input ? "true" : "false") << "\n";

    if (!config.modules.empty()) {
        oss << "modules=";
        for (std::size_t i = 0; i < config.modules.size(); ++i) {
            oss << config.modules[i];
            if (i + 1 < config.modules.size()) {
                oss << ",";
            }
        }
        oss << "\n";
    }

    oss << "\n[shell]\n";
    oss << "locking=false\n";

    return oss.str();
}

Result<void> WestonConfigWriter::write_config_file(const std::string& path, const WestonConfig& config) {
    std::filesystem::path p(path);
    if (p.has_parent_path()) {
        std::error_code ec;
        std::filesystem::create_directories(p.parent_path(), ec);
        if (ec) {
            return Result<void>::failure(Error(
                ErrorCategory::Compositor,
                ErrorCode::CompositorConfigError,
                "Failed to create directory for Weston configuration: " + ec.message(),
                "path=" + path));
        }
    }

    std::ofstream ofs(path, std::ios::out | std::ios::trunc);
    if (!ofs.is_open()) {
        return Result<void>::failure(Error(
            ErrorCategory::Compositor,
            ErrorCode::CompositorConfigError,
            "Failed to open Weston configuration file for writing",
            "path=" + path));
    }

    auto content = generate_ini_content(config);
    ofs << content;
    ofs.flush();
    if (!ofs.good()) {
        return Result<void>::failure(Error(
            ErrorCategory::Compositor,
            ErrorCode::CompositorConfigError,
            "Failed to write Weston configuration file",
            "path=" + path));
    }

    return Result<void>::success();
}

Result<std::string> WestonExecutableResolver::resolve(const std::string& preferred_path) {
    if (!preferred_path.empty()) {
        if (access(preferred_path.c_str(), X_OK) == 0) {
            return Result<std::string>::success(preferred_path);
        }
        return Result<std::string>::failure(Error(
            ErrorCategory::Compositor,
            ErrorCode::CompositorExecutableNotFound,
            "Configured Weston executable not found or not executable: " + preferred_path,
            "executable=" + preferred_path));
    }

    // Standard Linux paths
    const std::vector<std::string> standard_paths = {
        "/usr/bin/weston",
        "/usr/local/bin/weston",
        "/bin/weston"
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
                std::filesystem::path candidate = std::filesystem::path(dir) / "weston";
                if (access(candidate.c_str(), X_OK) == 0) {
                    return Result<std::string>::success(candidate.string());
                }
            }

            start = end + 1;
        }
    }

    return Result<std::string>::failure(Error(
        ErrorCategory::Compositor,
        ErrorCode::CompositorExecutableNotFound,
        "Weston executable could not be found in PATH or standard locations"));
}

} // namespace lddm::weston
