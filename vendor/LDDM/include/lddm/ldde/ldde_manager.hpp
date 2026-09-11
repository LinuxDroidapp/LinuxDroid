#pragma once

#include "lddm/session/session_contract.hpp"
#include "lddm/session/session_context.hpp"
#include "lddm/process/process_supervisor.hpp"
#include "lddm/ldde/ldde_types.hpp"
#include "lddm/ldde/ldde_config.hpp"
#include "lddm/ldde/ldde_spec.hpp"
#include "lddm/ldde/ldde_diagnostics.hpp"

#include <memory>
#include <mutex>
#include <string>
#include <optional>

namespace lddm::ldde {

class LddeManager : public IDesktopEnvironmentInstance {
public:
    explicit LddeManager(LddeConfig config = LddeConfig::create_default());
    ~LddeManager() override;

    LddeManager(const LddeManager&) = delete;
    LddeManager& operator=(const LddeManager&) = delete;
    LddeManager(LddeManager&&) = delete;
    LddeManager& operator=(LddeManager&&) = delete;

    // ISessionComponent implementation
    [[nodiscard]] const std::string& name() const noexcept override;
    [[nodiscard]] bool is_running() const noexcept override;

    Result<void> initialize(const SessionContext& context) override;
    Result<void> prepare();
    Result<void> start() override;
    Result<void> stop() override;

    Result<void> wait_until_ready(std::chrono::milliseconds timeout = std::chrono::milliseconds(0));

    // Accessors
    [[nodiscard]] LddeState state() const noexcept;
    [[nodiscard]] const LddeDiagnostics& diagnostics() const noexcept;
    [[nodiscard]] const LddeConfig& configuration() const noexcept;
    [[nodiscard]] std::shared_ptr<process::Process> process() const noexcept;
    [[nodiscard]] std::optional<std::string> process_handle() const noexcept;
    [[nodiscard]] const std::string& readiness_path() const noexcept;

    void set_supervisor(std::shared_ptr<ProcessSupervisor> supervisor);
    void on_process_event(const process::ProcessEvent& event);
    void cleanup_resources() noexcept;
    void reset();

private:
    mutable std::recursive_mutex mutex_;
    std::string name_{"ldde"};
    LddeConfig config_;
    LddeSpec spec_;
    LddeState state_{LddeState::Created};
    LddeDiagnostics diagnostics_;

    std::string runtime_dir_{};
    std::string log_file_{};
    std::string wayland_display_{"wayland-0"};
    std::string readiness_path_{};

    std::shared_ptr<ProcessSupervisor> supervisor_{nullptr};
    std::shared_ptr<process::Process> process_{nullptr};
    std::optional<std::string> process_handle_{std::nullopt};

    Result<void> transition_to(LddeState target, std::string reason = "");
};

} // namespace lddm::ldde
