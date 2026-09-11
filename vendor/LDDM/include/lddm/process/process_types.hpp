#pragma once

#include "lddm/core/types.hpp"
#include <string>
#include <string_view>
#include <vector>
#include <unordered_map>
#include <optional>
#include <iosfwd>

namespace lddm {

enum class ProcessState : std::uint8_t {
    CREATED  = 0,
    STARTING = 1,
    RUNNING  = 2,
    STOPPING = 3,
    EXITED   = 4,
    FAILED   = 5,

    // Backward-compatibility aliases with L0
    NotStarted = 0,
    Running    = 2,
    Exited     = 4,
    Signaled   = 4,
    Failed     = 5
};

[[nodiscard]] std::string_view to_string(ProcessState state) noexcept;
[[nodiscard]] bool is_valid_process_transition(ProcessState from, ProcessState to) noexcept;
std::ostream& operator<<(std::ostream& os, ProcessState state);

enum class StreamPolicy : std::uint8_t {
    Inherit = 0,
    Null    = 1,
    Close   = 2,
    File    = 3,
    Pipe    = 4
};

[[nodiscard]] std::string_view to_string(StreamPolicy policy) noexcept;
std::ostream& operator<<(std::ostream& os, StreamPolicy policy);

struct ProcessExitInfo {
    ProcessId pid{0};
    bool exited_normally{false};
    int exit_code{0};
    bool signaled{false};
    int term_signal{0};
    bool core_dumped{false};
    SystemTimePoint exit_time{SystemClock::now()};

    [[nodiscard]] bool success() const noexcept {
        return exited_normally && exit_code == 0 && !signaled;
    }

    [[nodiscard]] std::string format() const;
};

// Backward-compatibility alias with L0
using ProcessExitStatus = ProcessExitInfo;

struct ProcessLaunchOptions {
    std::string executable;
    std::vector<std::string> arguments;
    std::unordered_map<std::string, std::string> environment;
    std::string working_directory;
    std::optional<UserId> run_as_user;
    std::optional<GroupId> run_as_group;
};

namespace process {
using lddm::ProcessState;
using lddm::StreamPolicy;
using lddm::ProcessExitInfo;
using lddm::ProcessExitStatus;
using lddm::ProcessLaunchOptions;
}

} // namespace lddm
