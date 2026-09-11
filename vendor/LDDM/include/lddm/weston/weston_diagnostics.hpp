#pragma once

#include "lddm/weston/weston_types.hpp"
#include "lddm/process/process_types.hpp"
#include "lddm/core/error.hpp"
#include <string>
#include <vector>
#include <chrono>
#include <optional>
#include <sys/types.h>

namespace lddm::weston {

struct WestonTransitionRecord {
    WestonState from;
    WestonState to;
    std::chrono::system_clock::time_point timestamp;
    std::string reason;
};

class WestonDiagnostics {
public:
    WestonDiagnostics() = default;

    [[nodiscard]] WestonState state() const noexcept { return state_; }
    [[nodiscard]] pid_t pid() const noexcept { return pid_; }
    [[nodiscard]] const std::string& executable() const noexcept { return executable_; }
    [[nodiscard]] const std::string& config_path() const noexcept { return config_path_; }
    [[nodiscard]] const std::string& runtime_dir() const noexcept { return runtime_dir_; }
    [[nodiscard]] const std::string& wayland_display() const noexcept { return wayland_display_; }
    [[nodiscard]] const std::string& socket_path() const noexcept { return socket_path_; }
    [[nodiscard]] const std::string& log_path() const noexcept { return log_path_; }

    [[nodiscard]] std::optional<std::chrono::system_clock::time_point> start_time() const noexcept { return start_time_; }
    [[nodiscard]] std::optional<std::chrono::system_clock::time_point> ready_time() const noexcept { return ready_time_; }
    [[nodiscard]] std::optional<std::chrono::system_clock::time_point> stop_time() const noexcept { return stop_time_; }
    [[nodiscard]] std::optional<process::ProcessExitInfo> exit_info() const noexcept { return exit_info_; }
    [[nodiscard]] std::optional<Error> last_error() const noexcept { return last_error_; }
    [[nodiscard]] const std::vector<WestonTransitionRecord>& transition_history() const noexcept { return transition_history_; }

    void record_transition(WestonState from, WestonState to, std::string reason = "");
    void set_pid(pid_t pid) noexcept { pid_ = pid; }
    void set_executable(std::string exe) { executable_ = std::move(exe); }
    void set_config_path(std::string cfg) { config_path_ = std::move(cfg); }
    void set_runtime_dir(std::string dir) { runtime_dir_ = std::move(dir); }
    void set_wayland_display(std::string disp) { wayland_display_ = std::move(disp); }
    void set_socket_path(std::string path) { socket_path_ = std::move(path); }
    void set_log_path(std::string path) { log_path_ = std::move(path); }

    void record_start_time() { start_time_ = std::chrono::system_clock::now(); }
    void record_ready_time() { ready_time_ = std::chrono::system_clock::now(); }
    void record_stop_time() { stop_time_ = std::chrono::system_clock::now(); }
    void record_exit_info(process::ProcessExitInfo info) { exit_info_ = std::move(info); }
    void record_error(Error error) { last_error_ = std::move(error); }

    [[nodiscard]] std::chrono::milliseconds startup_duration() const noexcept;
    [[nodiscard]] std::chrono::milliseconds total_runtime() const noexcept;

private:
    WestonState state_{WestonState::Created};
    pid_t pid_{-1};
    std::string executable_{};
    std::string config_path_{};
    std::string runtime_dir_{};
    std::string wayland_display_{};
    std::string socket_path_{};
    std::string log_path_{};

    std::optional<std::chrono::system_clock::time_point> start_time_{};
    std::optional<std::chrono::system_clock::time_point> ready_time_{};
    std::optional<std::chrono::system_clock::time_point> stop_time_{};
    std::optional<process::ProcessExitInfo> exit_info_{};
    std::optional<Error> last_error_{};
    std::vector<WestonTransitionRecord> transition_history_{};
};

} // namespace lddm::weston
