#include "lddm/session/session.hpp"
#include "lddm/recovery/recovery_manager.hpp"
#include "lddm/platform/environment.hpp"
#include "lddm/logging/logger.hpp"
#include <fstream>
#include <filesystem>
#include <unistd.h>

namespace lddm {

Session::Session(SessionConfig config)
    : config_(std::move(config))
    , paths_(config_.id,
             config_.base_runtime_dir.empty() ? SessionPaths::default_base_runtime_dir() : config_.base_runtime_dir,
             config_.wayland_display)
    , state_machine_(config_.id.str(), SessionState::CREATED)
    , diagnostics_(config_.id) {
    identity_.id = config_.id;
    identity_.type = static_cast<std::uint32_t>(config_.type);
    identity_.user_id = config_.uid;
    identity_.group_id = config_.gid;
    identity_.username = config_.user;
    identity_.created_at = SystemClock::now();

    // Hook state transitions into diagnostics and state file
    state_machine_.register_observer([this](const SessionTransitionEvent& ev) {
        diagnostics_.record_state_transition(ev);
        switch (ev.to) {
            case SessionState::RECOVERING:
                write_state_file("GRAPHICAL_SESSION_RECOVERING");
                break;
            case SessionState::STOPPING:
                write_state_file("STOPPING");
                break;
            case SessionState::STOPPED:
                if (!config_.clean_runtime_dir_on_stop) {
                    write_state_file("STOPPED");
                }
                break;
            case SessionState::FAILED:
                write_state_file("GRAPHICAL_SESSION_FAILED");
                break;
            default:
                break;
        }
    });

    LDDM_LOG_INFO(LogSubsystem::SESSION, "Session '{}' created for user '{}' (type: {})",
                  identity_.id.str(), identity_.username, to_string(config_.type));
}

Session::~Session() {
    (void)cleanup();
}


SessionState Session::state() const noexcept {
    return state_machine_.state();
}

bool Session::is_graphical_session_ready() const noexcept {
    std::lock_guard lock(mutex_);
    if (state_machine_.state() != SessionState::RUNNING) {
        return false;
    }
    bool comp_ok = compositor_ ? compositor_->is_running() : true;
    bool desk_ok = desktop_ ? desktop_->is_running() : true;
    return comp_ok && desk_ok;
}

SessionContext Session::context() const noexcept {
    return SessionContext{
        .identity = identity_,
        .config = config_,
        .paths = paths_,
        .environment = environment_,
        .state = state(),
        .supervisor = supervisor_
    };
}

void Session::set_environment_variable(std::string key, std::string value) {
    std::lock_guard lock(mutex_);
    environment_.set(std::move(key), std::move(value));
}

void Session::attach_component(std::shared_ptr<ISessionComponent> component) {
    std::lock_guard lock(mutex_);
    if (component) {
        LDDM_LOG_INFO(LogSubsystem::SESSION, "[Session '{}'] Attached component '{}'",
                      identity_.id.str(), component->name());
        components_.push_back(std::move(component));
    }
}

void Session::attach_compositor(std::shared_ptr<ICompositorInstance> compositor) {
    std::lock_guard lock(mutex_);
    compositor_ = compositor;
    if (compositor_) {
        LDDM_LOG_INFO(LogSubsystem::SESSION, "[Session '{}'] Attached compositor '{}'",
                      identity_.id.str(), compositor_->name());
        components_.push_back(compositor_);
    }
}

void Session::attach_desktop(std::shared_ptr<IDesktopEnvironmentInstance> desktop) {
    std::lock_guard lock(mutex_);
    desktop_ = desktop;
    if (desktop_) {
        LDDM_LOG_INFO(LogSubsystem::SESSION, "[Session '{}'] Attached desktop environment '{}'",
                      identity_.id.str(), desktop_->name());
        components_.push_back(desktop_);
    }
}

void Session::attach_recovery(std::shared_ptr<recovery::RecoveryManager> recovery) {
    std::lock_guard lock(mutex_);
    recovery_ = recovery;
    if (recovery_) {
        LDDM_LOG_INFO(LogSubsystem::SESSION, "[Session '{}'] Attached recovery manager",
                      identity_.id.str());
    }
}

Result<void> Session::transition_to(SessionState target, std::string reason) {
    return state_machine_.transition_to(target, std::move(reason));
}

void Session::write_state_file(const std::string& state_name) {
    try {
        if (state_name == "STOPPED" && config_.clean_runtime_dir_on_stop) {
            return;
        }
        std::error_code ec;
        std::vector<std::filesystem::path> candidate_paths;
        if (!config_.clean_runtime_dir_on_stop || paths_.exists()) {
            candidate_paths.push_back(paths_.state_dir() / "session_state");
            candidate_paths.push_back(paths_.runtime_dir() / "lddm.state");
        }
        if (auto xdg = Environment::get("XDG_RUNTIME_DIR"); xdg && !xdg->empty()) {
            candidate_paths.push_back(std::filesystem::path(*xdg) / "session_state");
            candidate_paths.push_back(std::filesystem::path(*xdg) / "lddm.state");
        }
        auto now_t = SystemClock::to_time_t(SystemClock::now());
        for (const auto& p : candidate_paths) {
            std::filesystem::create_directories(p.parent_path(), ec);
            std::ofstream out(p, std::ios::trunc);
            if (out.is_open()) {
                out << "STATE=" << state_name << "\n";
                out << "SESSION_ID=" << identity_.id.str() << "\n";
                out << "PID=" << ::getpid() << "\n";
                out << "TIMESTAMP=" << now_t << "\n";
                out.flush();
            }
        }
    } catch (...) {
        // Non-blocking best-effort
    }
}

Result<void> Session::initialize() {
    std::lock_guard lock(mutex_);

    auto trans_res = state_machine_.transition_to(SessionState::INITIALIZING, "Initializing session resources");
    if (!trans_res.has_value()) {
        return trans_res;
    }

    // 1. Prepare runtime directories
    auto path_res = paths_.create_directories();
    if (!path_res.has_value()) {
        diagnostics_.record_error(path_res.error());
        (void)state_machine_.transition_to(SessionState::FAILED, "Runtime directory creation failed");
        (void)resources_.cleanup();
        return path_res;
    }

    // 2. Prepare environment variables
    environment_.populate_session_defaults(config_, paths_);

    // 2b. Initialize Process Supervisor
    supervisor_ = std::make_shared<ProcessSupervisor>();
    supervisor_->set_base_environment(environment_.to_vector());

    // Register supervisor exit listener for automatic recovery
    supervisor_->register_listener([this](const ProcessEvent& event) {
        if (event.type == ProcessEventType::Exited || event.type == ProcessEventType::Signaled) {
            LDDM_LOG_WARN(LogSubsystem::SESSION, "[Session '{}'] Supervised process '{}' (PID: {}) ended unexpectedly",
                          identity_.id.str(), event.process_name, event.pid);
            if (recovery_ && state_machine_.state() == SessionState::RUNNING) {
                write_state_file("GRAPHICAL_SESSION_RECOVERING");
                if (event.process_name.find("weston") != std::string::npos) {
                    LDDM_LOG_WARN(LogSubsystem::SESSION, "[WARN] Weston exited unexpectedly");
                    LDDM_LOG_WARN(LogSubsystem::SESSION, "[WARN] Graphical session degraded");
                    auto rec_res = recovery_->recover(recovery::RecoveryReason::WestonUnexpectedExit, "weston");
                    if (rec_res.has_value()) {
                        write_state_file("GUI_READY");
                    } else {
                        write_state_file("GRAPHICAL_SESSION_FAILED");
                    }
                } else if (event.process_name.find("ldde") != std::string::npos) {
                    LDDM_LOG_WARN(LogSubsystem::SESSION, "[WARN] LDDE exited unexpectedly");
                    LDDM_LOG_WARN(LogSubsystem::SESSION, "[WARN] Graphical session degraded");
                    auto rec_res = recovery_->recover(recovery::RecoveryReason::LddeUnexpectedExit, "ldde");
                    if (rec_res.has_value()) {
                        write_state_file("GUI_READY");
                    } else {
                        write_state_file("GRAPHICAL_SESSION_FAILED");
                    }
                }
            }
        }
    });

    // 3. Initialize attached components with session context
    auto ctx = context();
    for (const auto& comp : components_) {
        if (comp) {
            auto comp_init = comp->initialize(ctx);
            if (!comp_init.has_value()) {
                diagnostics_.record_error(comp_init.error());
                (void)state_machine_.transition_to(SessionState::FAILED, "Component initialization failed: " + comp->name());
                (void)cleanup();
                return comp_init;
            }
        }
    }

    diagnostics_.set_initialized_at(SystemClock::now());

    // 4. Transition to READY
    auto ready_res = state_machine_.transition_to(SessionState::READY, "Session initialization complete");
    if (!ready_res.has_value()) {
        diagnostics_.record_error(ready_res.error());
        (void)state_machine_.transition_to(SessionState::FAILED, "Transition to READY failed");
        (void)cleanup();
        return ready_res;
    }

    LDDM_LOG_INFO(LogSubsystem::SESSION, "[Session '{}'] Ready for activation", identity_.id.str());
    return Result<void>::success();
}

Result<void> Session::prepare() {
    auto current = state();
    if (current == SessionState::CREATED) {
        return initialize();
    }
    if (current == SessionState::READY) {
        return Result<void>::success();
    }
    return Result<void>::failure(Error(
        ErrorCategory::Session,
        ErrorCode::SessionInvalidState,
        "Cannot prepare session in state " + std::string(to_string(current)),
        "session_id=" + identity_.id.str()));
}

Result<void> Session::start() {
    std::lock_guard lock(mutex_);

    if (state_machine_.state() != SessionState::READY) {
        auto prep_res = prepare();
        if (!prep_res.has_value()) {
            return prep_res;
        }
    }

    if (supervisor_) {
        supervisor_->set_base_environment(environment_.to_vector());
    }

    auto start_trans = state_machine_.transition_to(SessionState::STARTING, "Starting session components");
    if (!start_trans.has_value()) {
        return start_trans;
    }

    // Start components in order
    std::vector<std::shared_ptr<ISessionComponent>> started;
    for (const auto& comp : components_) {
        if (comp) {
            if (comp == compositor_) {
                LDDM_LOG_INFO(LogSubsystem::SESSION, "[INFO] Starting Weston");
                write_state_file("WESTON_STARTING");
            } else if (comp == desktop_) {
                LDDM_LOG_INFO(LogSubsystem::SESSION, "[INFO] Starting LDDE");
                write_state_file("LDDE_STARTING");
            }

            auto res = comp->start();
            if (!res.has_value()) {
                diagnostics_.record_error(res.error());
                // Roll back started components in reverse
                for (auto it = started.rbegin(); it != started.rend(); ++it) {
                    (void)(*it)->stop();
                }
                (void)state_machine_.transition_to(SessionState::FAILED, "Component failed to start: " + comp->name());
                write_state_file("GRAPHICAL_SESSION_FAILED");
                return res;
            }

            if (comp == compositor_) {
                LDDM_LOG_INFO(LogSubsystem::SESSION, "[INFO] Weston ready");
                write_state_file("WESTON_READY");
            } else if (comp == desktop_) {
                LDDM_LOG_INFO(LogSubsystem::SESSION, "[INFO] LDDE ready");
                write_state_file("LDDE_READY");
            }

            started.push_back(comp);
        }
    }

    diagnostics_.set_started_at(SystemClock::now());

    auto run_trans = state_machine_.transition_to(SessionState::RUNNING, "All session components started");
    if (!run_trans.has_value()) {
        diagnostics_.record_error(run_trans.error());
        for (auto it = started.rbegin(); it != started.rend(); ++it) {
            (void)(*it)->stop();
        }
        (void)state_machine_.transition_to(SessionState::FAILED, "Failed to enter RUNNING state");
        write_state_file("GRAPHICAL_SESSION_FAILED");
        return run_trans;
    }

    write_state_file("GUI_READY");
    LDDM_LOG_INFO(LogSubsystem::SESSION, "[INFO] GUI ready");
    LDDM_LOG_INFO(LogSubsystem::SESSION, "[Session '{}'] Running", identity_.id.str());
    return Result<void>::success();
}

Result<void> Session::stop() {
    std::lock_guard lock(mutex_);

    auto current = state_machine_.state();
    if (current == SessionState::STOPPED) {
        return Result<void>::success();
    }

    if (current != SessionState::STARTING &&
        current != SessionState::RUNNING &&
        current != SessionState::READY &&
        current != SessionState::INITIALIZING &&
        current != SessionState::RECOVERING &&
        current != SessionState::FAILED) {
        return Result<void>::failure(Error(
            ErrorCategory::Session,
            ErrorCode::SessionInvalidState,
            "Cannot stop session in state " + std::string(to_string(current)),
            "session_id=" + identity_.id.str()));
    }

    if (current != SessionState::FAILED) {
        (void)state_machine_.transition_to(SessionState::STOPPING, "Stopping session components");
    }

    // Stop components in reverse order
    for (auto it = components_.rbegin(); it != components_.rend(); ++it) {
        if (*it && (*it)->is_running()) {
            (void)(*it)->stop();
        }
    }

    // Stop any remaining supervised child processes
    if (supervisor_) {
        (void)supervisor_->stop_all();
    }

    // Clean tracked resources
    (void)resources_.cleanup();

    // Clean runtime directory if configured
    if (config_.clean_runtime_dir_on_stop) {
        (void)paths_.remove_directories();
    }

    diagnostics_.set_stopped_at(SystemClock::now());

    (void)state_machine_.transition_to(SessionState::STOPPED, "Session teardown complete");
    LDDM_LOG_INFO(LogSubsystem::SESSION, "[Session '{}'] Stopped cleanly", identity_.id.str());
    return Result<void>::success();
}

Result<void> Session::fail(Error error) {
    std::lock_guard lock(mutex_);
    diagnostics_.record_error(error);

    LDDM_LOG_ERROR(LogSubsystem::SESSION, "[Session '{}'] Failing due to error: {}",
                   identity_.id.str(), error.to_string());

    (void)state_machine_.transition_to(SessionState::FAILED, error.message());

    // Stop supervised child processes
    if (supervisor_) {
        (void)supervisor_->stop_all();
    }

    // Teardown components
    for (auto it = components_.rbegin(); it != components_.rend(); ++it) {
        if (*it && (*it)->is_running()) {
            (void)(*it)->stop();
        }
    }

    (void)resources_.cleanup();
    if (config_.clean_runtime_dir_on_stop) {
        (void)paths_.remove_directories();
    }

    return Result<void>::success();
}

Result<void> Session::cleanup() noexcept {
    auto current = state_machine_.state();
    if (current == SessionState::RUNNING ||
        current == SessionState::STARTING ||
        current == SessionState::READY ||
        current == SessionState::INITIALIZING ||
        current == SessionState::RECOVERING) {
        (void)stop();
    }

    if (supervisor_) {
        (void)supervisor_->stop_all();
    }

    (void)resources_.cleanup();
    if (config_.clean_runtime_dir_on_stop) {
        (void)paths_.remove_directories();
    }

    return Result<void>::success();
}

} // namespace lddm
