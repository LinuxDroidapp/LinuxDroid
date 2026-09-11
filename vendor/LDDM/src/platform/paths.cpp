#include "lddm/platform/paths.hpp"
#include "lddm/platform/environment.hpp"
#include <system_error>

namespace lddm {

std::string Paths::runtime_dir() {
    if (auto xdg = Environment::get("XDG_RUNTIME_DIR"); xdg && !xdg->empty()) {
        return *xdg + "/lddm";
    }
    return "/run/lddm";
}

std::string Paths::config_dir() {
    if (auto xdg = Environment::get("XDG_CONFIG_HOME"); xdg && !xdg->empty()) {
        return *xdg + "/lddm";
    }
    if (auto home = Environment::get("HOME"); home && !home->empty()) {
        return *home + "/.config/lddm";
    }
    return "/etc/lddm";
}

std::string Paths::log_dir() {
    if (auto state = Environment::get("XDG_STATE_HOME"); state && !state->empty()) {
        return *state + "/lddm/logs";
    }
    if (auto home = Environment::get("HOME"); home && !home->empty()) {
        return *home + "/.local/state/lddm/logs";
    }
    return "/var/log/lddm";
}

std::string Paths::home_dir() {
    if (auto home = Environment::get("HOME"); home && !home->empty()) {
        return *home;
    }
    return "/root";
}

Result<void> Paths::ensure_directory(const std::filesystem::path& path) {
    std::error_code ec;
    if (std::filesystem::exists(path, ec)) {
        return Result<void>::success();
    }

    if (std::filesystem::create_directories(path, ec)) {
        return Result<void>::success();
    }

    if (ec) {
        return Result<void>::failure(Error(
            ErrorCategory::Platform,
            ErrorCode::PlatformPathResolution,
            "Failed to create directory " + path.string() + ": " + ec.message()));
    }

    return Result<void>::success();
}

} // namespace lddm

