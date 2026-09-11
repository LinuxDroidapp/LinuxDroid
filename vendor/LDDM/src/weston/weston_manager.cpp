#include "lddm/weston/weston_manager.hpp"
#include "lddm/weston/weston_readiness.hpp"
#include "lddm/logging/logger.hpp"
#include "lddm/platform/clock.hpp"

#include <filesystem>
#include <thread>
#include <vector>
#include <sys/stat.h>

namespace lddm::weston {

namespace process = lddm::process;

WestonManager::WestonManager(WestonConfig config)
    : config_(std::move(config)),
      state_(WestonState::Created) {
    diagnostics_.record_transition(WestonState::Created, WestonState::Created, "WestonManager created");
}

WestonManager::~WestonManager() {
    cleanup_resources();
}

const std::string& WestonManager::name() const noexcept {
    return name_;
}

bool WestonManager::is_running() const noexcept {
    std::lock_guard lock(mutex_);
    return state_ == WestonState::Running;
}

const std::string& WestonManager::socket_path() const noexcept {
    std::lock_guard lock(mutex_);
    return socket_path_;
}

WestonState WestonManager::state() const noexcept {
    std::lock_guard lock(mutex_);
    return state_;
}

const WestonConfig& WestonManager::config() const noexcept {
    std::lock_guard lock(mutex_);
    return config_;
}

const WestonSpec& WestonManager::spec() const noexcept {
    std::lock_guard lock(mutex_);
    return spec_;
}

const WestonDiagnostics& WestonManager::diagnostics() const noexcept {
    std::lock_guard lock(mutex_);
    return diagnostics_;
}

const std::string& WestonManager::wayland_display() const noexcept {
    std::lock_guard lock(mutex_);
    return wayland_display_;
}

std::optional<process::ProcessHandle> WestonManager::process_handle() const noexcept {
    std::lock_guard lock(mutex_);
    return process_handle_;
}

std::shared_ptr<process::Process> WestonManager::process() const noexcept {
    std::lock_guard lock(mutex_);
    return process_;
}

void WestonManager::set_supervisor(std::shared_ptr<process::ProcessSupervisor> supervisor) {
    std::lock_guard lock(mutex_);
    supervisor_ = std::move(supervisor);
    if (supervisor_) {
        supervisor_->register_listener(
            [this](const process::ProcessEvent& event) {
                on_process_event(event);
            });
    }
}

Result<void> WestonManager::transition_to(WestonState target, std::string reason) {
    if (!is_valid_weston_transition(state_, target)) {
        std::string err_msg = "Invalid Weston state transition from " +
                              std::string(to_string(state_)) + " to " +
                              std::string(to_string(target));
        LDDM_LOG_WARN(LogSubsystem::WESTON, "{}", err_msg);
        return Result<void>::failure(Error(
            ErrorCategory::Compositor,
            ErrorCode::CompositorInvalidState,
            err_msg));
    }

    auto old_state = state_;
    state_ = target;
    diagnostics_.record_transition(old_state, target, reason);

    LDDM_LOG_INFO(LogSubsystem::WESTON, "Weston transition: {} -> {} ({})",
                  to_string(old_state), to_string(target),
                  reason.empty() ? "none" : reason);

    return Result<void>::success();
}

Result<void> WestonManager::initialize(const SessionContext& context) {
    std::lock_guard lock(mutex_);

    runtime_dir_ = context.paths.runtime_dir().string();
    log_file_ = (context.paths.log_dir() / "weston.log").string();
    wayland_display_ = !config_.socket_name.empty() ? config_.socket_name : "wayland-0";
    socket_path_ = (std::filesystem::path(runtime_dir_) / wayland_display_).string();

    diagnostics_.set_runtime_dir(runtime_dir_);
    diagnostics_.set_socket_path(socket_path_);
    diagnostics_.set_wayland_display(wayland_display_);
    diagnostics_.set_log_path(log_file_);

    if (context.supervisor) {
        supervisor_ = context.supervisor;
        supervisor_->register_listener(
            [this](const process::ProcessEvent& event) {
                on_process_event(event);
            });
    }

    // In the finalized architecture, the compositor is the native embedded libweston-17
    // managed by LinuxDroid's GuiHost. We do not require or spawn /usr/bin/weston.
    config_.executable = "native-embedded-libweston";
    diagnostics_.set_executable(config_.executable);

    LDDM_LOG_INFO(LogSubsystem::WESTON, "Weston initialized for native compositor: exe={}, socket={}, runtime_dir={}",
                  config_.executable, socket_path_, runtime_dir_);

    return Result<void>::success();
}

Result<void> WestonManager::prepare() {
    std::lock_guard lock(mutex_);

    if (state_ == WestonState::Created) {
        auto tr = transition_to(WestonState::Preparing, "Preparing Weston runtime and configuration");
        if (!tr.has_value()) {
            return tr;
        }
    } else if (state_ != WestonState::Preparing) {
        return Result<void>::failure(Error(
            ErrorCategory::Compositor,
            ErrorCode::CompositorInvalidState,
            "Cannot prepare Weston in state " + std::string(to_string(state_))));
    }

    // 1. Ensure runtime directory exists with 0700
    if (!runtime_dir_.empty()) {
        std::error_code ec;
        std::filesystem::create_directories(runtime_dir_, ec);
        if (ec) {
            auto err = Error(ErrorCategory::Platform, ErrorCode::PlatformPathResolution,
                             "Failed to create runtime directory: " + ec.message(),
                             "runtime_dir=" + runtime_dir_);
            diagnostics_.record_error(err);
            (void)transition_to(WestonState::Failed, err.message());
            return Result<void>::failure(err);
        }
        chmod(runtime_dir_.c_str(), 0700);
    }

    // Note: In the finalized architecture, the Wayland socket is owned by the
    // native embedded libweston-17 compositor in GuiHost. Do not remove or unlink it.

    if (!log_file_.empty()) {
        std::filesystem::path lp(log_file_);
        if (lp.has_parent_path()) {
            std::error_code ec;
            std::filesystem::create_directories(lp.parent_path(), ec);
        }
    }

    // 2. Write auto-generated config if requested or no path provided
    if (config_.auto_generate_config || config_.config_path.empty()) {
        std::string cfg_path = runtime_dir_ + "/weston.ini";
        auto write_res = WestonConfigWriter::write_config_file(cfg_path, config_);
        if (write_res.has_value()) {
            config_.config_path = cfg_path;
            generated_config_path_ = cfg_path;
            diagnostics_.set_config_path(cfg_path);
        } else {
            diagnostics_.record_error(write_res.error());
            (void)transition_to(WestonState::Failed, "Config generation failed: " + write_res.error().message());
            return write_res;
        }
    } else {
        diagnostics_.set_config_path(config_.config_path);
    }

    // 3. Build WestonSpec
    spec_.executable = config_.executable;
    spec_.arguments = WestonSpec::build_arguments(config_, log_file_);
    spec_.working_directory = runtime_dir_;
    spec_.config_file = config_.config_path;
    spec_.wayland_display = wayland_display_;
    spec_.log_file = log_file_;
    spec_.startup_timeout = std::chrono::milliseconds(config_.startup_timeout_ms);
    spec_.shutdown_timeout = std::chrono::milliseconds(config_.stop_timeout_ms);
    spec_.stdout_policy = process::StreamPolicy::File;
    spec_.stdout_file = log_file_;
    spec_.stderr_policy = process::StreamPolicy::File;
    spec_.stderr_file = log_file_;

    // Environment variables
    spec_.environment["XDG_RUNTIME_DIR"] = runtime_dir_;
    spec_.environment["WAYLAND_DISPLAY"] = wayland_display_;
    spec_.environment["XDG_SESSION_TYPE"] = "wayland";

    auto val_res = spec_.validate();
    if (!val_res.has_value()) {
        diagnostics_.record_error(val_res.error());
        (void)transition_to(WestonState::Failed, "Spec validation failed: " + val_res.error().message());
        return val_res;
    }

    LDDM_LOG_INFO(LogSubsystem::WESTON, "Weston prepared successfully");
    return Result<void>::success();
}

Result<void> WestonManager::start() {
    std::unique_lock lock(mutex_);

    if (state_ == WestonState::Created) {
        lock.unlock();
        auto prep_res = prepare();
        if (!prep_res.has_value()) {
            return prep_res;
        }
        lock.lock();
    }

    if (state_ != WestonState::Preparing) {
        return Result<void>::failure(Error(
            ErrorCategory::Compositor,
            ErrorCode::CompositorInvalidState,
            "Cannot start Weston in state " + std::string(to_string(state_))));
    }

    auto tr = transition_to(WestonState::Starting, "Verifying native Wayland compositor readiness");
    if (!tr.has_value()) {
        return tr;
    }

    diagnostics_.record_start_time();

    LDDM_LOG_INFO(LogSubsystem::WESTON, "[INFO] Waiting for native Wayland compositor readiness");
    (void)transition_to(WestonState::WaitingReady, "Waiting for native Wayland socket readiness");

    // Unlock mutex while waiting for socket readiness to prevent deadlock
    auto socket_path_copy = socket_path_;
    auto timeout_copy = spec_.startup_timeout.count() > 0 ? spec_.startup_timeout : std::chrono::milliseconds(10000);
    auto wayland_disp_copy = wayland_display_;
    lock.unlock();

    // Check socket readiness with pid = -1 (native compositor runs in Android host process, not child process)
    // Probe candidates: designated session socket, /tmp socket, /run/user socket, and XDG_RUNTIME_DIR
    std::vector<std::string> candidates = {
        socket_path_copy,
        "/tmp/" + wayland_disp_copy,
        "/run/user/1000/" + wayland_disp_copy,
        "/run/user/0/" + wayland_disp_copy
    };
    if (const char* xdg = getenv("XDG_RUNTIME_DIR")) {
        candidates.push_back((std::filesystem::path(xdg) / wayland_disp_copy).string());
    }

    std::string active_socket;
    auto deadline = SystemClock::now() + timeout_copy;
    bool found = false;

    while (SystemClock::now() < deadline) {
        for (const auto& cand : candidates) {
            auto status = WestonReadinessDetector::check_socket(cand, -1);
            if (status == WaylandSocketStatus::WaylandConnectionUsable) {
                active_socket = cand;
                found = true;
                break;
            }
        }
        if (found) {
            break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }

    if (!found) {
        auto ready_res = WestonReadinessDetector::wait_for_readiness(
            socket_path_copy, -1, std::chrono::milliseconds(100));
        lock.lock();
        diagnostics_.record_error(ready_res.error());
        (void)transition_to(WestonState::Failed, "Native compositor readiness check failed: " + ready_res.error().message());
        cleanup_resources();
        return ready_res;
    }

    // If active socket is not socket_path_copy, establish a symlink so that
    // LDDE and other session children can access it via socket_path_copy (in session runtime dir)
    if (active_socket != socket_path_copy) {
        std::error_code ec;
        std::filesystem::create_directories(std::filesystem::path(socket_path_copy).parent_path(), ec);
        std::filesystem::remove(socket_path_copy, ec);
        std::filesystem::create_symlink(active_socket, socket_path_copy, ec);
        if (!ec) {
            LDDM_LOG_INFO(LogSubsystem::WESTON, "Bridged session socket {} -> {}", socket_path_copy, active_socket);
        }
    }

    lock.lock();
    (void)transition_to(WestonState::Running, "Native Wayland socket verified ready");
    diagnostics_.record_ready_time();

    LDDM_LOG_INFO(LogSubsystem::WESTON, "[INFO] Weston ready");
    LDDM_LOG_INFO(LogSubsystem::WESTON, "Native compositor ready (Socket: {}, Active: {})", socket_path_, active_socket);

    return Result<void>::success();
}

Result<void> WestonManager::wait_until_ready(std::chrono::milliseconds timeout) {
    std::string socket_path_copy;

    {
        std::lock_guard lock(mutex_);
        if (state_ == WestonState::Running) {
            return Result<void>::success();
        }
        if (state_ != WestonState::WaitingReady) {
            return Result<void>::failure(Error(
                ErrorCategory::Compositor,
                ErrorCode::CompositorInvalidState,
                "Cannot wait for readiness in state " + std::string(to_string(state_))));
        }

        socket_path_copy = socket_path_;
        if (timeout == std::chrono::milliseconds(0)) {
            timeout = spec_.startup_timeout;
        }
    }

    return WestonReadinessDetector::wait_for_readiness(socket_path_copy, -1, timeout);
}

Result<void> WestonManager::stop() {
    std::lock_guard lock(mutex_);

    if (state_ == WestonState::Stopped) {
        return Result<void>::success();
    }

    if (state_ != WestonState::Running &&
        state_ != WestonState::WaitingReady &&
        state_ != WestonState::Starting &&
        state_ != WestonState::Preparing &&
        state_ != WestonState::Created &&
        state_ != WestonState::Failed) {
        return Result<void>::failure(Error(
            ErrorCategory::Compositor,
            ErrorCode::CompositorInvalidState,
            "Cannot stop Weston in state " + std::string(to_string(state_))));
    }

    (void)transition_to(WestonState::Stopping, "Weston shutdown requested");

    cleanup_resources();

    diagnostics_.record_stop_time();
    (void)transition_to(WestonState::Stopped, "Weston stopped cleanly");

    LDDM_LOG_INFO(LogSubsystem::WESTON, "Weston stopped cleanly");
    return Result<void>::success();
}

void WestonManager::on_process_event(const process::ProcessEvent& event) {
    std::lock_guard lock(mutex_);

    if (process_ && event.pid == process_->pid()) {
        if (event.type == process::ProcessEventType::Exited ||
            event.type == process::ProcessEventType::Signaled) {
            diagnostics_.record_exit_info(event.exit_info);

            if (state_ == WestonState::Running ||
                state_ == WestonState::WaitingReady ||
                state_ == WestonState::Starting) {
                std::string reason = "Weston process exited unexpectedly (" + event.exit_info.format() + ")";
                LDDM_LOG_ERROR(LogSubsystem::WESTON, "{}", reason);

                diagnostics_.record_error(Error(
                    ErrorCategory::Compositor,
                    ErrorCode::CompositorCrash,
                    reason));

                (void)transition_to(WestonState::Failed, reason);
            }
        }
    }
}

void WestonManager::cleanup_resources() noexcept {
    // Note: Do not remove socket_path_ because it belongs to the host native compositor.
    // Remove auto-generated config
    if (!generated_config_path_.empty()) {
        std::error_code ec;
        std::filesystem::remove(generated_config_path_, ec);
        generated_config_path_.clear();
    }
}

void WestonManager::reset() {
    std::lock_guard lock(mutex_);
    cleanup_resources();
    process_ = nullptr;
    process_handle_ = std::nullopt;
    state_ = WestonState::Created;
}

} // namespace lddm::weston
