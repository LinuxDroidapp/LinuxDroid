#include "lddm/config/config_types.hpp"

namespace lddm {

LddmConfig LddmConfig::create_default() {
    LddmConfig cfg;
    cfg.logging = LoggingConfig{
        .level = LogLevel::INFO,
        .file_path = "",
        .console_output = true,
        .colorize = true
    };
    cfg.server = ServerConfig{
        .socket_path = "/run/lddm/lddm.sock",
        .runtime_dir = "/run/lddm",
        .pid_file = "/run/lddm/lddm.pid"
    };
    cfg.session = SessionConfigData{
        .default_user = "root",
        .session_type = "wayland",
        .display_number = 0,
        .wayland_display = "wayland-0"
    };
    cfg.weston = WestonConfigData{
        .executable = "/usr/bin/weston",
        .config_path = "/etc/xdg/weston/weston.ini",
        .socket_name = "wayland-0",
        .backend = "headless-backend.so",
        .additional_args = {}
    };
    cfg.ldde = LddeConfigData{
        .executable = "/usr/bin/ldde-session",
        .session_target = "default",
        .autostart = true
    };
    cfg.process = ProcessConfigData{
        .startup_timeout_ms = 10000,
        .stop_timeout_ms = 5000,
        .max_restart_count = 3,
        .restart_window_seconds = 60
    };
    cfg.environment = EnvironmentConfigData{
        .variables = {}
    };
    return cfg;
}

} // namespace lddm

