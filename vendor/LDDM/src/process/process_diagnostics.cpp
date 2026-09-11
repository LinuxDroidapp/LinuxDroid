#include "lddm/process/process_diagnostics.hpp"
#include <sstream>

namespace lddm {

ProcessDiagnostics::ProcessDiagnostics(ProcessSpec spec, ProcessId pid)
    : spec_(std::move(spec))
    , pid_(pid) {}

std::chrono::milliseconds ProcessDiagnostics::runtime_duration() const {
    if (state_ == ProcessState::CREATED || state_ == ProcessState::STARTING) {
        return std::chrono::milliseconds{0};
    }
    auto end_time = exit_time_.value_or(SystemClock::now());
    return std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time_);
}

std::string ProcessDiagnostics::format_report() const {
    std::ostringstream oss;
    oss << "=== Process Diagnostics [" << spec_.name << "] ===\n"
        << "  PID: " << pid_ << "\n"
        << "  Executable: " << spec_.executable << "\n"
        << "  State: " << to_string(state_) << "\n"
        << "  Process Group: " << (spec_.enable_process_group ? "enabled" : "disabled") << "\n"
        << "  Duration: " << runtime_duration().count() << "ms\n";

    if (!spec_.arguments.empty()) {
        oss << "  Arguments: ";
        for (const auto& arg : spec_.arguments) {
            oss << arg << " ";
        }
        oss << "\n";
    }

    if (!spec_.working_directory.empty()) {
        oss << "  Working Directory: " << spec_.working_directory.string() << "\n";
    }

    if (exit_info_) {
        oss << "  Exit Info: " << exit_info_->format() << "\n";
    }

    return oss.str();
}

} // namespace lddm

