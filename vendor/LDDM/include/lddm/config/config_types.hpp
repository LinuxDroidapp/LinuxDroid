#pragma once

#include "lddm/logging/log_level.hpp"
#include <cstdint>
#include <string>
#include <vector>
#include <unordered_map>

namespace lddm {

struct LoggingConfig {
    LogLevel level{LogLevel::INFO};
    std::string file_path{};
    bool console_output{true};
    bool colorize{true};
};

struct ServerConfig {
    std::string socket_path{"/run/lddm/lddm.sock"};
    std::string runtime_dir{"/run/lddm"};
    std::string pid_file{"/run/lddm/lddm.pid"};
};

struct SessionConfigData {
    std::string default_user{"root"};
    std::string session_type{"wayland"};
    std::uint32_t display_number{0};
    std::string wayland_display{"wayland-0"};
};

struct WestonConfigData {
    std::string executable{"/usr/bin/weston"};
    std::string config_path{"/etc/xdg/weston/weston.ini"};
    std::string socket_name{"wayland-0"};
    std::string backend{"headless-backend.so"};
    std::vector<std::string> additional_args{};
};

struct LddeConfigData {
    std::string executable{"/usr/bin/ldde-session"};
    std::string session_target{"default"};
    bool autostart{true};
};

struct ProcessConfigData {
    std::uint32_t startup_timeout_ms{10000};
    std::uint32_t stop_timeout_ms{5000};
    std::uint32_t max_restart_count{3};
    std::uint32_t restart_window_seconds{60};
};

struct EnvironmentConfigData {
    std::unordered_map<std::string, std::string> variables;
};

struct LddmConfig {
    LoggingConfig logging;
    ServerConfig server;
    SessionConfigData session;
    WestonConfigData weston;
    LddeConfigData ldde;
    ProcessConfigData process;
    EnvironmentConfigData environment;

    [[nodiscard]] static LddmConfig create_default();
};

} // namespace lddm

