#include "lddm/session/session_environment.hpp"
#include "lddm/session/session_config.hpp"
#include "lddm/session/session_paths.hpp"
#include "lddm/platform/environment.hpp"
#include <algorithm>
#include <cctype>

namespace lddm {

SessionEnvironment::SessionEnvironment() {
    // Inherit standard base system variables if available
    const std::string_view base_keys[] = {
        "PATH", "TERM", "LANG", "LC_ALL", "SHELL"
    };

    for (const auto& k : base_keys) {
        if (auto val = Environment::get(k)) {
            variables_[std::string(k)] = std::move(*val);
        }
    }

    if (variables_.find("PATH") == variables_.end()) {
        variables_["PATH"] = "/usr/local/bin:/usr/bin:/bin";
    }
    if (variables_.find("TERM") == variables_.end()) {
        variables_["TERM"] = "xterm-256color";
    }
    if (variables_.find("LANG") == variables_.end()) {
        variables_["LANG"] = "C.UTF-8";
    }
    if (variables_.find("SHELL") == variables_.end()) {
        variables_["SHELL"] = "/bin/bash";
    }
}

std::optional<std::string> SessionEnvironment::get(std::string_view key) const {
    auto it = variables_.find(std::string(key));
    if (it != variables_.end()) {
        return it->second;
    }
    return std::nullopt;
}

void SessionEnvironment::set(std::string key, std::string value) {
    variables_[std::move(key)] = std::move(value);
}

void SessionEnvironment::unset(std::string_view key) {
    variables_.erase(std::string(key));
}

bool SessionEnvironment::has(std::string_view key) const noexcept {
    return variables_.find(std::string(key)) != variables_.end();
}

void SessionEnvironment::merge(const std::unordered_map<std::string, std::string>& overrides) {
    for (const auto& [k, v] : overrides) {
        variables_[k] = v;
    }
}

void SessionEnvironment::populate_session_defaults(const SessionConfig& config, const SessionPaths& paths) {
    variables_["USER"] = config.user;
    variables_["LOGNAME"] = config.user;

    std::string home = (config.user == "root") ? "/root" : ("/home/" + config.user);
    if (auto env_home = Environment::get("HOME"); env_home && !env_home->empty()) {
        home = *env_home;
    }
    variables_["HOME"] = home;

    variables_["XDG_RUNTIME_DIR"] = paths.runtime_dir().string();
    variables_["XDG_CONFIG_HOME"] = home + "/.config";
    variables_["XDG_DATA_HOME"] = home + "/.local/share";
    variables_["XDG_CACHE_HOME"] = home + "/.cache";

    if (config.type == SessionType::Wayland) {
        variables_["WAYLAND_DISPLAY"] = paths.wayland_socket_name();
        variables_["XDG_SESSION_TYPE"] = "wayland";
        variables_["QT_QPA_PLATFORM"] = "wayland";
        variables_["GDK_BACKEND"] = "wayland";
    } else if (config.type == SessionType::X11) {
        variables_["DISPLAY"] = config.x11_display;
        variables_["XDG_SESSION_TYPE"] = "x11";
        variables_["QT_QPA_PLATFORM"] = "xcb";
        variables_["GDK_BACKEND"] = "x11";
    } else {
        variables_["XDG_SESSION_TYPE"] = "headless";
    }

    variables_["XDG_CURRENT_DESKTOP"] = "LDDE";
    variables_["XDG_SESSION_DESKTOP"] = "LDDE";

    // Merge session-specific overrides
    merge(config.environment_overrides);
}

std::vector<std::string> SessionEnvironment::to_vector() const {
    std::vector<std::string> result;
    result.reserve(variables_.size());
    for (const auto& [k, v] : variables_) {
        result.push_back(k + "=" + v);
    }
    return result;
}

bool SessionEnvironment::is_sensitive_key(std::string_view key) noexcept {
    std::string upper_key(key);
    std::transform(upper_key.begin(), upper_key.end(), upper_key.begin(), [](unsigned char c) {
        return static_cast<char>(std::toupper(c));
    });

    const std::string_view sensitive_patterns[] = {
        "SECRET", "PASSWORD", "PASSWD", "TOKEN", "KEY", "AUTH", "CREDENTIAL", "PRIVATE"
    };

    for (const auto& pattern : sensitive_patterns) {
        if (upper_key.find(pattern) != std::string::npos) {
            return true;
        }
    }
    return false;
}

std::unordered_map<std::string, std::string> SessionEnvironment::redacted_summary() const {
    std::unordered_map<std::string, std::string> result;
    result.reserve(variables_.size());

    for (const auto& [k, v] : variables_) {
        if (is_sensitive_key(k)) {
            result[k] = "[REDACTED]";
        } else {
            result[k] = v;
        }
    }
    return result;
}

} // namespace lddm

