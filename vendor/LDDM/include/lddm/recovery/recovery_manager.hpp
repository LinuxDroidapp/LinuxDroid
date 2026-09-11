#pragma once

#include "lddm/core/result.hpp"
#include "lddm/process/process_events.hpp"
#include "lddm/recovery/recovery_types.hpp"
#include "lddm/recovery/recovery_config.hpp"
#include "lddm/recovery/recovery_diagnostics.hpp"

#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <chrono>

namespace lddm {
class Session;
} // namespace lddm

namespace lddm::recovery {

class RecoveryManager : public std::enable_shared_from_this<RecoveryManager> {
public:
    explicit RecoveryManager(Session* session, RecoveryConfig config = RecoveryConfig::create_default());
    ~RecoveryManager() = default;

    RecoveryManager(const RecoveryManager&) = delete;
    RecoveryManager& operator=(const RecoveryManager&) = delete;
    RecoveryManager(RecoveryManager&&) = delete;
    RecoveryManager& operator=(RecoveryManager&&) = delete;

    // Public Recovery API
    Result<void> recover(RecoveryReason reason, const std::string& component = "");
    Result<void> recover_component(const std::string& component, RecoveryReason reason);
    Result<void> restart_session(RecoveryReason reason);

    // Event listener for asynchronous process supervisor events
    void on_process_event(const process::ProcessEvent& event);

    // Status & Diagnostics
    [[nodiscard]] RecoveryState state() const noexcept;
    [[nodiscard]] const RecoveryDiagnostics& diagnostics() const noexcept;
    [[nodiscard]] const RecoveryConfig& config() const noexcept;

    void set_config(RecoveryConfig config);
    void reset();

private:
    Result<void> validate_preconditions(RecoveryReason reason, const std::string& component);
    RecoveryPolicy determine_policy(RecoveryReason reason, const std::string& component, std::uint32_t attempt);
    Result<void> execute_recovery(RecoveryReason reason, const std::string& component, RecoveryPolicy policy);

    Result<void> recover_weston_component(RecoveryReason reason);
    Result<void> recover_ldde_component(RecoveryReason reason);
    Result<void> execute_session_restart(RecoveryReason reason);

    void clean_stale_wayland_socket(const std::string& socket_path);
    void clean_stale_ldde_resources();
    bool is_wayland_socket_active(const std::string& socket_path) noexcept;
    Result<void> verify_graphical_session_readiness();

    void purge_expired_attempts(const SystemTimePoint& now);

    mutable std::recursive_mutex mutex_;
    Session* session_{nullptr};
    RecoveryConfig config_;
    RecoveryDiagnostics diagnostics_;
    RecoveryState state_{RecoveryState::Idle};

    std::uint64_t current_recovery_id_{0};
    std::vector<SystemTimePoint> attempt_timestamps_{};
};

} // namespace lddm::recovery
