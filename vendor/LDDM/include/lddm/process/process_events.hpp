#pragma once

#include "lddm/core/types.hpp"
#include "lddm/process/process_types.hpp"
#include <string>
#include <string_view>
#include <functional>

namespace lddm {

enum class ProcessEventType : std::uint8_t {
    Started,
    StartupFailed,
    Exited,
    Signaled,
    TerminationRequested,
    Terminated,
    Failed
};

[[nodiscard]] constexpr std::string_view to_string(ProcessEventType type) noexcept {
    switch (type) {
        case ProcessEventType::Started:              return "Started";
        case ProcessEventType::StartupFailed:        return "StartupFailed";
        case ProcessEventType::Exited:               return "Exited";
        case ProcessEventType::Signaled:             return "Signaled";
        case ProcessEventType::TerminationRequested: return "TerminationRequested";
        case ProcessEventType::Terminated:           return "Terminated";
        case ProcessEventType::Failed:               return "Failed";
    }
    return "Unknown";
}

struct ProcessEvent {
    ProcessEventType type{ProcessEventType::Started};
    ProcessId pid{0};
    std::string process_name;
    ProcessExitInfo exit_info{};
    SystemTimePoint timestamp{SystemClock::now()};
    std::string message{};
};

using ProcessEventListener = std::function<void(const ProcessEvent&)>;

namespace process {
using lddm::ProcessEvent;
using lddm::ProcessEventType;
using lddm::ProcessEventListener;
}

} // namespace lddm

