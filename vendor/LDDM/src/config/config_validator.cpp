#include "lddm/config/config_validator.hpp"

namespace lddm {

Result<void> ConfigValidator::validate(const LddmConfig& config) {
    if (config.server.socket_path.empty()) {
        return Result<void>::failure(Error(
            ErrorCategory::Configuration,
            ErrorCode::ConfigValidationFailed,
            "server.socket_path cannot be empty"));
    }

    if (config.server.runtime_dir.empty()) {
        return Result<void>::failure(Error(
            ErrorCategory::Configuration,
            ErrorCode::ConfigValidationFailed,
            "server.runtime_dir cannot be empty"));
    }

    if (config.session.default_user.empty()) {
        return Result<void>::failure(Error(
            ErrorCategory::Configuration,
            ErrorCode::ConfigValidationFailed,
            "session.default_user cannot be empty"));
    }

    if (config.session.session_type.empty()) {
        return Result<void>::failure(Error(
            ErrorCategory::Configuration,
            ErrorCode::ConfigValidationFailed,
            "session.session_type cannot be empty"));
    }

    if (config.weston.executable.empty()) {
        return Result<void>::failure(Error(
            ErrorCategory::Configuration,
            ErrorCode::ConfigValidationFailed,
            "weston.executable cannot be empty"));
    }

    if (config.weston.socket_name.empty()) {
        return Result<void>::failure(Error(
            ErrorCategory::Configuration,
            ErrorCode::ConfigValidationFailed,
            "weston.socket_name cannot be empty"));
    }

    if (config.ldde.autostart && config.ldde.executable.empty()) {
        return Result<void>::failure(Error(
            ErrorCategory::Configuration,
            ErrorCode::ConfigValidationFailed,
            "ldde.executable cannot be empty when autostart is enabled"));
    }

    if (config.process.startup_timeout_ms == 0) {
        return Result<void>::failure(Error(
            ErrorCategory::Configuration,
            ErrorCode::ConfigValidationFailed,
            "process.startup_timeout_ms must be greater than 0"));
    }

    if (config.process.stop_timeout_ms == 0) {
        return Result<void>::failure(Error(
            ErrorCategory::Configuration,
            ErrorCode::ConfigValidationFailed,
            "process.stop_timeout_ms must be greater than 0"));
    }

    return Result<void>::success();
}

} // namespace lddm

