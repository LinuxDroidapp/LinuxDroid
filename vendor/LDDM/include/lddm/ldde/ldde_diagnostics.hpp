#pragma once

#include "lddm/ldde/ldde_types.hpp"
#include "lddm/process/process_types.hpp"
#include "lddm/platform/clock.hpp"
#include "lddm/core/error.hpp"

#include <string>
#include <vector>
#include <optional>
#include <mutex>
#include <chrono>

namespace lddm::ldde {

struct LddeTransitionRecord {
    LddeState from;
    LddeState to;
    SystemTimePoint timestamp;
    std::string reason;
};

class LddeDiagnostics {
public:
    LddeDiagnostics() = default;

    void record_transition(LddeState from, LddeState to, std::string reason = "");
    void record_start_time();
    void record_ready_time();
    void record_stop_time();
    void record_exit_info(const process::ProcessExitInfo& exit_info);
    void record_error(const Error& error);

    void set_executable(std::string exe);
    void set_session_target(std::string target);
    void set_readiness_path(std::string path);
    void set_log_path(std::string path);
    void set_pid(pid_t pid);

    [[nodiscard]] std::vector<LddeTransitionRecord> transitions() const;
    [[nodiscard]] std::optional<SystemTimePoint> start_time() const;
    [[nodiscard]] std::optional<SystemTimePoint> ready_time() const;
    [[nodiscard]] std::optional<SystemTimePoint> stop_time() const;
    [[nodiscard]] std::chrono::milliseconds startup_duration() const;
    [[nodiscard]] std::chrono::milliseconds active_duration() const;

    [[nodiscard]] std::optional<pid_t> pid() const;
    [[nodiscard]] std::optional<process::ProcessExitInfo> exit_info() const;
    [[nodiscard]] std::vector<Error> errors() const;
    [[nodiscard]] std::optional<Error> last_error() const;

    [[nodiscard]] std::string executable() const;
    [[nodiscard]] std::string session_target() const;
    [[nodiscard]] std::string readiness_path() const;
    [[nodiscard]] std::string log_path() const;

    [[nodiscard]] std::string format_report() const;

private:
    mutable std::mutex mutex_;
    std::vector<LddeTransitionRecord> transitions_;
    std::vector<Error> errors_;

    SystemTimePoint created_at_{SystemClock::now()};
    std::optional<SystemTimePoint> start_time_;
    std::optional<SystemTimePoint> ready_time_;
    std::optional<SystemTimePoint> stop_time_;

    std::optional<pid_t> pid_;
    std::optional<process::ProcessExitInfo> exit_info_;

    std::string executable_;
    std::string session_target_;
    std::string readiness_path_;
    std::string log_path_;
};

} // namespace lddm::ldde
