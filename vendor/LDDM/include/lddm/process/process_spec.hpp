#pragma once

#include "lddm/core/result.hpp"
#include "lddm/process/process_types.hpp"
#include <string>
#include <vector>
#include <unordered_map>
#include <filesystem>
#include <chrono>

namespace lddm {

struct ProcessSpec {
    std::string name{"process"};
    std::string executable;
    std::vector<std::string> arguments{};
    std::unordered_map<std::string, std::string> environment{};
    std::filesystem::path working_directory{};

    StreamPolicy stdin_policy{StreamPolicy::Null};
    StreamPolicy stdout_policy{StreamPolicy::Inherit};
    StreamPolicy stderr_policy{StreamPolicy::Inherit};

    std::filesystem::path stdout_path{};
    std::filesystem::path stderr_path{};

    bool enable_process_group{true};
    std::chrono::milliseconds startup_timeout{5000};
    std::chrono::milliseconds stop_timeout{5000};
    std::chrono::milliseconds kill_timeout{2000};

    [[nodiscard]] Result<void> validate() const noexcept;
};

namespace process {
using lddm::ProcessSpec;
}

} // namespace lddm
