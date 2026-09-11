#pragma once

#include "lddm/core/result.hpp"
#include "lddm/process/process_spec.hpp"
#include "lddm/weston/weston_config.hpp"
#include <string>
#include <vector>
#include <unordered_map>
#include <chrono>

namespace lddm::weston {

struct WestonSpec {
    std::string executable{};
    std::vector<std::string> arguments{};
    std::unordered_map<std::string, std::string> environment{};
    std::string working_directory{};
    std::string config_file{};
    std::string wayland_display{"wayland-0"};
    std::string log_file{};
    std::chrono::milliseconds startup_timeout{10000};
    std::chrono::milliseconds shutdown_timeout{5000};
    lddm::process::StreamPolicy stdout_policy{lddm::process::StreamPolicy::File};
    std::string stdout_file{};
    lddm::process::StreamPolicy stderr_policy{lddm::process::StreamPolicy::File};
    std::string stderr_file{};

    [[nodiscard]] Result<void> validate() const;
    [[nodiscard]] lddm::process::ProcessSpec to_process_spec() const;

    [[nodiscard]] static std::vector<std::string> build_arguments(
        const WestonConfig& config,
        const std::string& log_file = "");
};

} // namespace lddm::weston
