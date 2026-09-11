#include "lddm/session/session_config.hpp"
#include "lddm/config/config_types.hpp"
#include <ostream>

namespace lddm {

std::string_view to_string(SessionType type) noexcept {
    switch (type) {
        case SessionType::Wayland:  return "Wayland";
        case SessionType::X11:      return "X11";
        case SessionType::Headless: return "Headless";
    }
    return "Unknown";
}

std::ostream& operator<<(std::ostream& os, SessionType type) {
    return os << to_string(type);
}

SessionConfig SessionConfig::from_lddm_config(const LddmConfig& config, const SessionId& session_id) {
    SessionConfig sc;
    sc.id = session_id;
    if (config.session.session_type == "wayland") {
        sc.type = SessionType::Wayland;
    } else if (config.session.session_type == "x11") {
        sc.type = SessionType::X11;
    } else {
        sc.type = SessionType::Headless;
    }

    sc.user = config.session.default_user;
    sc.display_number = config.session.display_number;
    sc.wayland_display = config.session.wayland_display;
    sc.base_runtime_dir = std::filesystem::path(config.server.runtime_dir) / "sessions";
    sc.startup_timeout_ms = config.process.startup_timeout_ms;
    sc.stop_timeout_ms = config.process.stop_timeout_ms;
    sc.environment_overrides = config.environment.variables;
    sc.clean_runtime_dir_on_stop = true;

    return sc;
}

} // namespace lddm
