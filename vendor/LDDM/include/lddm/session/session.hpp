#pragma once

#include "lddm/session/session_id.hpp"
#include "lddm/session/session_config.hpp"
#include "lddm/session/session_paths.hpp"
#include "lddm/session/session_environment.hpp"
#include "lddm/session/session_state.hpp"
#include "lddm/session/session_resource.hpp"
#include "lddm/session/session_diagnostics.hpp"
#include "lddm/session/session_contract.hpp"
#include "lddm/session/session_context.hpp"
#include "lddm/process/process_supervisor.hpp"
#include "lddm/core/result.hpp"

#include <memory>
#include <vector>
#include <mutex>

namespace lddm {

namespace recovery {
class RecoveryManager;
}

class Session {
public:
    explicit Session(SessionConfig config);
    ~Session();

    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;
    Session(Session&&) = delete;
    Session& operator=(Session&&) = delete;

    [[nodiscard]] const SessionId& id() const noexcept { return identity_.id; }
    [[nodiscard]] const SessionIdentity& identity() const noexcept { return identity_; }
    [[nodiscard]] const SessionConfig& config() const noexcept { return config_; }
    [[nodiscard]] const SessionPaths& paths() const noexcept { return paths_; }
    [[nodiscard]] const SessionEnvironment& environment() const noexcept { return environment_; }
    [[nodiscard]] SessionEnvironment& environment() noexcept { return environment_; }
    [[nodiscard]] const SessionDiagnostics& diagnostics() const noexcept { return diagnostics_; }
    [[nodiscard]] SessionState state() const noexcept;

    [[nodiscard]] SessionContext context() const noexcept;

    void set_environment_variable(std::string key, std::string value);

    // Component attachment
    void attach_component(std::shared_ptr<ISessionComponent> component);
    void attach_compositor(std::shared_ptr<ICompositorInstance> compositor);
    void attach_desktop(std::shared_ptr<IDesktopEnvironmentInstance> desktop);
    void attach_recovery(std::shared_ptr<recovery::RecoveryManager> recovery);

    [[nodiscard]] ICompositorInstance* compositor() const noexcept { return compositor_.get(); }
    [[nodiscard]] IDesktopEnvironmentInstance* desktop() const noexcept { return desktop_.get(); }
    [[nodiscard]] std::shared_ptr<ProcessSupervisor> supervisor() const noexcept { return supervisor_; }
    [[nodiscard]] recovery::RecoveryManager* recovery() const noexcept { return recovery_.get(); }

    [[nodiscard]] bool is_graphical_session_ready() const noexcept;
    void write_state_file(const std::string& state_name);

    // Lifecycle Operations
    Result<void> initialize();
    Result<void> prepare();
    Result<void> start();
    Result<void> stop();
    Result<void> fail(Error error);
    Result<void> cleanup() noexcept;

    // Direct state transition
    Result<void> transition_to(SessionState target, std::string reason = "");

    // Backward compatibility with L0
    Result<void> activate() { return start(); }
    Result<void> terminate() { return stop(); }

private:
    mutable std::recursive_mutex mutex_;
    SessionIdentity identity_;
    SessionConfig config_;
    SessionPaths paths_;
    SessionEnvironment environment_;
    SessionStateMachine state_machine_;
    SessionResourceTracker resources_;
    SessionDiagnostics diagnostics_;

    std::vector<std::shared_ptr<ISessionComponent>> components_;
    std::shared_ptr<ICompositorInstance> compositor_;
    std::shared_ptr<IDesktopEnvironmentInstance> desktop_;
    std::shared_ptr<ProcessSupervisor> supervisor_;
    std::shared_ptr<recovery::RecoveryManager> recovery_;
};

} // namespace lddm
