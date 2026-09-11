#pragma once

#include "lddm/core/result.hpp"
#include <string>
#include <vector>
#include <cstdint>

namespace lddm::weston {

struct WestonConfig {
    std::string executable{"/usr/bin/weston"};
    std::string config_path{};
    std::string socket_name{"wayland-0"};
    std::string backend{"headless-backend.so"};
    std::string shell{"desktop-shell.so"};
    std::string renderer{"auto"};
    std::uint32_t idle_time_seconds{0};
    std::uint32_t startup_timeout_ms{10000};
    std::uint32_t stop_timeout_ms{5000};
    std::vector<std::string> modules{};
    std::vector<std::string> additional_args{};
    bool auto_generate_config{false};
    bool require_input{false};
    std::uint32_t width{1024};
    std::uint32_t height{768};

    [[nodiscard]] static WestonConfig create_default();
    [[nodiscard]] Result<void> validate() const;
};

class WestonConfigWriter {
public:
    [[nodiscard]] static std::string generate_ini_content(const WestonConfig& config);
    static Result<void> write_config_file(const std::string& path, const WestonConfig& config);
};

class WestonExecutableResolver {
public:
    [[nodiscard]] static Result<std::string> resolve(const std::string& preferred_path = "");
};

} // namespace lddm::weston
