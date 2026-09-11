#pragma once

#include "lddm/session/session_id.hpp"
#include <string>
#include <unordered_map>
#include <string_view>
#include <filesystem>
#include <iosfwd>

namespace lddm {

struct LddmConfig;

enum class SessionType : std::uint8_t {
    Wayland  = 0,
    X11      = 1,
    Headless = 2
};

[[nodiscard]] std::string_view to_string(SessionType type) noexcept;
std::ostream& operator<<(std::ostream& os, SessionType type);

struct SessionConfig {
    SessionId id{SessionId("session-0")};
    SessionType type{SessionType::Wayland};
    std::string user{"root"};
    UserId uid{0};
    GroupId gid{0};
    std::string wayland_display{"wayland-0"};
    std::string x11_display{":0"};
    std::uint32_t display_number{0};
    std::filesystem::path base_runtime_dir{};
    bool clean_runtime_dir_on_stop{true};
    std::uint32_t startup_timeout_ms{10000};
    std::uint32_t stop_timeout_ms{5000};
    std::unordered_map<std::string, std::string> environment_overrides{};

    [[nodiscard]] static SessionConfig from_lddm_config(const LddmConfig& config,
                                                       const SessionId& session_id = SessionId("session-default"));
};

} // namespace lddm
