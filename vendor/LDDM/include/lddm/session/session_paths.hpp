#pragma once

#include "lddm/core/result.hpp"
#include "lddm/session/session_id.hpp"
#include <filesystem>
#include <string>

namespace lddm {

class SessionPaths {
public:
    SessionPaths() = default;
    SessionPaths(const SessionId& session_id,
                 const std::filesystem::path& base_runtime_dir,
                 std::string wayland_socket_name = "wayland-0");

    [[nodiscard]] const std::filesystem::path& session_root() const noexcept { return session_root_; }
    [[nodiscard]] const std::filesystem::path& runtime_dir() const noexcept { return runtime_dir_; }
    [[nodiscard]] const std::filesystem::path& wayland_socket_path() const noexcept { return wayland_socket_path_; }
    [[nodiscard]] const std::filesystem::path& ipc_socket_path() const noexcept { return ipc_socket_path_; }
    [[nodiscard]] const std::filesystem::path& state_dir() const noexcept { return state_dir_; }
    [[nodiscard]] const std::filesystem::path& log_dir() const noexcept { return log_dir_; }
    [[nodiscard]] const std::filesystem::path& tmp_dir() const noexcept { return tmp_dir_; }
    [[nodiscard]] const std::string& wayland_socket_name() const noexcept { return wayland_socket_name_; }

    [[nodiscard]] bool exists() const noexcept;
    Result<void> create_directories();
    Result<void> remove_directories();

    [[nodiscard]] static std::filesystem::path default_base_runtime_dir();

private:
    std::filesystem::path session_root_;
    std::filesystem::path runtime_dir_;
    std::filesystem::path wayland_socket_path_;
    std::filesystem::path ipc_socket_path_;
    std::filesystem::path state_dir_;
    std::filesystem::path log_dir_;
    std::filesystem::path tmp_dir_;
    std::string wayland_socket_name_{"wayland-0"};
};

} // namespace lddm

