#include "lddm/ldde/ldde_spec.hpp"
#include "lddm/core/error.hpp"
#include <filesystem>

namespace lddm::ldde {

Result<void> LddeSpec::validate() const {
    if (executable.empty()) {
        return Result<void>::failure(Error(
            ErrorCategory::Desktop,
            ErrorCode::LddeConfigError,
            "LDDE executable path cannot be empty"));
    }

    if (readiness_path.empty()) {
        return Result<void>::failure(Error(
            ErrorCategory::Desktop,
            ErrorCode::LddeConfigError,
            "LDDE readiness path cannot be empty"));
    }

    if (!working_directory.empty()) {
        std::error_code ec;
        if (!std::filesystem::exists(working_directory, ec) || !std::filesystem::is_directory(working_directory, ec)) {
            return Result<void>::failure(Error(
                ErrorCategory::Desktop,
                ErrorCode::LddeConfigError,
                "LDDE working directory does not exist or is not a directory",
                "working_directory=" + working_directory));
        }
    }

    return Result<void>::success();
}

std::vector<std::string> LddeSpec::build_arguments(const LddeConfig& config) {
    std::vector<std::string> args;

    if (!config.session_target.empty()) {
        args.push_back("--session=" + config.session_target);
    }

    for (const auto& arg : config.additional_args) {
        args.push_back(arg);
    }

    return args;
}

process::ProcessSpec LddeSpec::to_process_spec() const {
    process::ProcessSpec ps;
    ps.name = "ldde";
    ps.executable = executable;
    ps.arguments = arguments;
    ps.working_directory = working_directory;
    ps.environment = environment;
    ps.stdout_policy = stdout_policy;
    ps.stdout_path = stdout_file;
    ps.stderr_policy = stderr_policy;
    ps.stderr_path = stderr_file;
    ps.enable_process_group = true;
    ps.stop_timeout = shutdown_timeout;
    return ps;
}

} // namespace lddm::ldde
