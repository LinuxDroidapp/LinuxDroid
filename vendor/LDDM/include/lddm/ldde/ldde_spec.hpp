#pragma once

#include "lddm/core/result.hpp"
#include "lddm/ldde/ldde_types.hpp"
#include "lddm/ldde/ldde_config.hpp"
#include "lddm/process/process_spec.hpp"
#include <string>
#include <vector>
#include <unordered_map>
#include <chrono>

namespace lddm::ldde {

struct LddeSpec {
    std::string executable{};
    std::vector<std::string> arguments{};
    std::unordered_map<std::string, std::string> environment{};
    std::string working_directory{};
    std::string session_target{"default"};

    std::chrono::milliseconds startup_timeout{10000};
    std::chrono::milliseconds shutdown_timeout{5000};
    std::chrono::milliseconds readiness_timeout{10000};

    std::string readiness_path{};
    LddeReadinessMode readiness_mode{LddeReadinessMode::File};

    process::StreamPolicy stdout_policy{process::StreamPolicy::File};
    std::string stdout_file{};
    process::StreamPolicy stderr_policy{process::StreamPolicy::File};
    std::string stderr_file{};

    [[nodiscard]] Result<void> validate() const;
    [[nodiscard]] process::ProcessSpec to_process_spec() const;

    [[nodiscard]] static std::vector<std::string> build_arguments(const LddeConfig& config);
};

} // namespace lddm::ldde
