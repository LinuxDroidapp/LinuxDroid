#include "lddm/process/process_spec.hpp"
#include "lddm/core/error.hpp"

namespace lddm {

Result<void> ProcessSpec::validate() const noexcept {
    if (executable.empty()) {
        return Result<void>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessSpawnFailed,
            "Process executable path cannot be empty",
            "name=" + name));
    }

    if (!working_directory.empty()) {
        std::error_code ec;
        if (!std::filesystem::exists(working_directory, ec) || !std::filesystem::is_directory(working_directory, ec)) {
            return Result<void>::failure(Error(
                ErrorCategory::Process,
                ErrorCode::ProcessSpawnFailed,
                "Working directory does not exist or is not a directory: " + working_directory.string(),
                "name=" + name));
        }
    }

    if (stdout_policy == StreamPolicy::File && stdout_path.empty()) {
        return Result<void>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessSpawnFailed,
            "stdout_path must be specified when stdout_policy is StreamPolicy::File",
            "name=" + name));
    }

    if (stderr_policy == StreamPolicy::File && stderr_path.empty()) {
        return Result<void>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessSpawnFailed,
            "stderr_path must be specified when stderr_policy is StreamPolicy::File",
            "name=" + name));
    }

    return Result<void>::success();
}

} // namespace lddm

