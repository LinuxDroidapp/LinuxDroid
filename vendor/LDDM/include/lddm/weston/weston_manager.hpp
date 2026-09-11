#pragma once

#include "lddm/session/session_contract.hpp"
#include "lddm/session/session_context.hpp"
#include "lddm/process/process_supervisor.hpp"
#include "lddm/process/process.hpp"
#include "lddm/weston/weston_types.hpp"
#include "lddm/weston/weston_config.hpp"
#include "lddm/weston/weston_spec.hpp"
#include "lddm/weston/weston_diagnostics.hpp"
#include "lddm/weston/weston_readiness.hpp"
#include "lddm/core/result.hpp"

#include <memory>
#include <mutex>
#include <string>
#include <optional>

namespace lddm::weston {

class WestonManager : public ICompositorInstance,
                      public std::enable_shared_from_this<WestonManager> {
public:
    explicit WestonManager(WestonConfig config = WestonConfig::create_default());
    ~WestonManager() override;

    WestonManager(const WestonManager&) = delete;
    WestonManager& operator=(const WestonManager&) = delete;
    WestonManager(WestonManager&&) = delete;
    WestonManager& operator=(WestonManager&&) = delete;

    // ISessionComponent implementation
    [[nodiscard]] const std::string& name() const noexcept override;
    [[nodiscard]] bool is_running() const noexcept override;
    Result<void> initialize(const SessionContext& context) override;
    Result<void> start() override;
    Result<void> stop() override;

    // ICompositorInstance implementation
    [[nodiscard]] const std::string& socket_path() const noexcept override;

    // Weston Lifecycle Operations
    Result<void> prepare();
    Result<void> wait_until_ready(std::chrono::milliseconds timeout = std::chrono::milliseconds(0));

    // Getters & Status
    [[nodiscard]] WestonState state() const noexcept;
    [[nodiscard]] const WestonConfig& config() const noexcept;
    [[nodiscard]] const WestonSpec& spec() const noexcept;
    [[nodiscard]] const WestonDiagnostics& diagnostics() const noexcept;
    [[nodiscard]] const std::string& wayland_display() const noexcept;
    [[nodiscard]] std::optional<lddm::process::ProcessHandle> process_handle() const noexcept;
    [[nodiscard]] std::shared_ptr<lddm::process::Process> process() const noexcept;

    // Direct supervisor dependency injection (if not initialized via SessionContext)
    void set_supervisor(std::shared_ptr<lddm::process::ProcessSupervisor> supervisor);

    void reset();
    void cleanup_resources() noexcept;

private:
    Result<void> transition_to(WestonState target, std::string reason = "");
    void on_process_event(const lddm::process::ProcessEvent& event);

    mutable std::recursive_mutex mutex_;
    std::string name_{"weston"};
    WestonConfig config_;
    WestonSpec spec_;
    WestonDiagnostics diagnostics_;
    WestonState state_{WestonState::Created};

    std::string socket_path_{};
    std::string wayland_display_{"wayland-0"};
    std::string runtime_dir_{};
    std::string log_file_{};
    std::string generated_config_path_{};

    std::shared_ptr<lddm::process::ProcessSupervisor> supervisor_{nullptr};
    std::optional<lddm::process::ProcessHandle> process_handle_{std::nullopt};
    std::shared_ptr<lddm::process::Process> process_{nullptr};
};

} // namespace lddm::weston
