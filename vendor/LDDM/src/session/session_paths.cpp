#include "lddm/session/session_paths.hpp"
#include "lddm/platform/paths.hpp"
#include "lddm/platform/environment.hpp"
#include "lddm/logging/logger.hpp"
#include <sys/stat.h>

namespace lddm {

std::filesystem::path SessionPaths::default_base_runtime_dir() {
    if (auto xdg = Environment::get("XDG_RUNTIME_DIR"); xdg && !xdg->empty()) {
        return std::filesystem::path(*xdg) / "linuxdroid" / "sessions";
    }
    return std::filesystem::path("/run/lddm/sessions");
}

SessionPaths::SessionPaths(const SessionId& session_id,
                           const std::filesystem::path& base_runtime_dir,
                           std::string wayland_socket_name)
    : wayland_socket_name_(std::move(wayland_socket_name)) {
    session_root_ = base_runtime_dir / session_id.str();
    runtime_dir_ = session_root_ / "run";
    wayland_socket_path_ = runtime_dir_ / wayland_socket_name_;
    ipc_socket_path_ = runtime_dir_ / "session.sock";
    state_dir_ = session_root_ / "state";
    log_dir_ = session_root_ / "logs";
    tmp_dir_ = session_root_ / "tmp";
}

bool SessionPaths::exists() const noexcept {
    std::error_code ec;
    return std::filesystem::exists(session_root_, ec);
}

Result<void> SessionPaths::create_directories() {
    std::error_code ec;

    const std::filesystem::path dirs[] = {
        session_root_,
        runtime_dir_,
        state_dir_,
        log_dir_,
        tmp_dir_
    };

    for (const auto& dir : dirs) {
        if (!std::filesystem::create_directories(dir, ec) && ec) {
            std::string err_msg = "Failed to create session directory '" + dir.string() + "': " + ec.message();
            LDDM_LOG_ERROR(LogSubsystem::SESSION, "{}", err_msg);
            return Result<void>::failure(Error(
                ErrorCategory::Session,
                ErrorCode::SessionRuntimeDirectoryFailed,
                std::move(err_msg),
                "path=" + dir.string()));
        }

        // Enforce 0700 permissions on session runtime directories (standard XDG_RUNTIME_DIR requirement)
        std::filesystem::permissions(dir,
                                     std::filesystem::perms::owner_all,
                                     std::filesystem::perm_options::replace,
                                     ec);
        if (ec) {
            std::string err_msg = "Failed to set 0700 permissions on directory '" + dir.string() + "': " + ec.message();
            LDDM_LOG_WARN(LogSubsystem::SESSION, "{}", err_msg);
            // Permissions warning does not abort if filesystem does not support POSIX perms (e.g. FAT/PRoot)
            ec.clear();
        }
    }

    LDDM_LOG_DEBUG(LogSubsystem::SESSION, "Created session runtime directories under: {}", session_root_.string());
    return Result<void>::success();
}

Result<void> SessionPaths::remove_directories() {
    std::error_code ec;
    if (std::filesystem::exists(session_root_, ec)) {
        std::filesystem::remove_all(session_root_, ec);
        if (ec) {
            std::string err_msg = "Failed to remove session directory '" + session_root_.string() + "': " + ec.message();
            LDDM_LOG_ERROR(LogSubsystem::SESSION, "{}", err_msg);
            return Result<void>::failure(Error(
                ErrorCategory::Session,
                ErrorCode::SessionCleanupFailed,
                std::move(err_msg),
                "path=" + session_root_.string()));
        }
        LDDM_LOG_DEBUG(LogSubsystem::SESSION, "Removed session runtime directories: {}", session_root_.string());
    }
    return Result<void>::success();
}

} // namespace lddm

