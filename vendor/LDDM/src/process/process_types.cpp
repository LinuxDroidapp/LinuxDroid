#include "lddm/process/process_types.hpp"
#include <ostream>
#include <sstream>

namespace lddm {

std::string_view to_string(ProcessState state) noexcept {
    switch (state) {
        case ProcessState::CREATED:  return "CREATED";
        case ProcessState::STARTING: return "STARTING";
        case ProcessState::RUNNING:  return "RUNNING";
        case ProcessState::STOPPING: return "STOPPING";
        case ProcessState::EXITED:   return "EXITED";
        case ProcessState::FAILED:   return "FAILED";
    }
    return "UNKNOWN";
}

bool is_valid_process_transition(ProcessState from, ProcessState to) noexcept {
    if (from == to) {
        return true;
    }

    switch (from) {
        case ProcessState::CREATED:
            return to == ProcessState::STARTING || to == ProcessState::FAILED;
        case ProcessState::STARTING:
            return to == ProcessState::RUNNING || to == ProcessState::STOPPING || to == ProcessState::FAILED;
        case ProcessState::RUNNING:
            return to == ProcessState::STOPPING || to == ProcessState::EXITED || to == ProcessState::FAILED;
        case ProcessState::STOPPING:
            return to == ProcessState::EXITED || to == ProcessState::FAILED;
        case ProcessState::EXITED:
            return false; // terminal
        case ProcessState::FAILED:
            return to == ProcessState::EXITED; // allow cleanup after failure
    }
    return false;
}

std::ostream& operator<<(std::ostream& os, ProcessState state) {
    return os << to_string(state);
}

std::string_view to_string(StreamPolicy policy) noexcept {
    switch (policy) {
        case StreamPolicy::Inherit: return "Inherit";
        case StreamPolicy::Null:    return "Null";
        case StreamPolicy::Close:   return "Close";
        case StreamPolicy::File:    return "File";
        case StreamPolicy::Pipe:    return "Pipe";
    }
    return "Unknown";
}

std::ostream& operator<<(std::ostream& os, StreamPolicy policy) {
    return os << to_string(policy);
}

std::string ProcessExitInfo::format() const {
    std::ostringstream oss;
    oss << "[pid=" << pid << " ";
    if (exited_normally) {
        oss << "exited=" << exit_code;
    } else if (signaled) {
        oss << "signaled=" << term_signal;
        if (core_dumped) {
            oss << " (core dumped)";
        }
    } else {
        oss << "status=unknown";
    }
    oss << "]";
    return oss.str();
}

} // namespace lddm
