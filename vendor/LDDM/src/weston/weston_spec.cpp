#include "lddm/weston/weston_spec.hpp"
#include "lddm/core/error.hpp"
#include <filesystem>

namespace lddm::weston {

Result<void> WestonSpec::validate() const {
    if (executable.empty()) {
        return Result<void>::failure(Error(
            ErrorCategory::Compositor,
            ErrorCode::CompositorConfigError,
            "Weston executable path cannot be empty"));
    }

    if (wayland_display.empty()) {
        return Result<void>::failure(Error(
            ErrorCategory::Compositor,
            ErrorCode::CompositorConfigError,
            "Wayland display name cannot be empty"));
    }

    if (!working_directory.empty()) {
        std::error_code ec;
        if (!std::filesystem::exists(working_directory, ec) || !std::filesystem::is_directory(working_directory, ec)) {
            return Result<void>::failure(Error(
                ErrorCategory::Compositor,
                ErrorCode::CompositorConfigError,
                "Weston working directory does not exist or is not a directory",
                "working_directory=" + working_directory));
        }
    }

    return Result<void>::success();
}

std::vector<std::string> WestonSpec::build_arguments(
    const WestonConfig& config,
    const std::string& log_file) {
    std::vector<std::string> args;

    // Backend
    if (!config.backend.empty()) {
        args.push_back("--backend=" + config.backend);
    }

    // Socket
    if (!config.socket_name.empty()) {
        args.push_back("--socket=" + config.socket_name);
    }

    // Idle time
    args.push_back("--idle-time=" + std::to_string(config.idle_time_seconds));

    // Shell
    if (!config.shell.empty()) {
        args.push_back("--shell=" + config.shell);
    }

    // Configuration file
    if (!config.config_path.empty()) {
        args.push_back("--config=" + config.config_path);
    } else {
        args.push_back("--no-config");
    }

    // Log file
    if (!log_file.empty()) {
        args.push_back("--log=" + log_file);
    }

    // Extra arguments
    for (const auto& arg : config.additional_args) {
        args.push_back(arg);
    }

    return args;
}

lddm::process::ProcessSpec WestonSpec::to_process_spec() const {
    lddm::process::ProcessSpec ps;
    ps.name = "weston";
    ps.executable = executable;
    ps.arguments = arguments;
    ps.working_directory = working_directory;
    ps.environment = environment;
    ps.stdout_policy = stdout_policy;
    ps.stdout_path = stdout_file;
    ps.stderr_policy = stderr_policy;
    ps.stderr_path = stderr_file;
    return ps;
}

} // namespace lddm::weston
