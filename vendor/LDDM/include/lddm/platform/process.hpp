#pragma once

#include "lddm/core/result.hpp"
#include "lddm/core/types.hpp"
#include "lddm/process/process_types.hpp"
#include <string>
#include <vector>
#include <filesystem>

namespace lddm::platform {

struct SpawnOptions {
    std::string executable;
    std::vector<std::string> arguments;
    std::vector<std::string> environment_vector;
    std::filesystem::path working_directory;
    int stdin_fd{-1};   // <0: untouched/inherit; >=0: dup2 to STDIN
    int stdout_fd{-1};  // <0: untouched/inherit; >=0: dup2 to STDOUT
    int stderr_fd{-1};  // <0: untouched/inherit; >=0: dup2 to STDERR
    bool enable_process_group{true};
};

[[nodiscard]] Result<ProcessId> spawn_process(const SpawnOptions& options);
[[nodiscard]] Result<ProcessExitInfo> wait_process(ProcessId pid, bool blocking);
[[nodiscard]] Result<void> send_signal(ProcessId pid, int signal, bool to_process_group = false);
[[nodiscard]] bool is_process_alive(ProcessId pid) noexcept;

} // namespace lddm::platform

