#include "lddm/version.hpp"
#include "lddm/core/lifecycle.hpp"
#include "lddm/core/error.hpp"
#include "lddm/config/config.hpp"
#include "lddm/config/config_migrator.hpp"
#include "lddm/logging/logger.hpp"
#include "lddm/platform/signal_handler.hpp"
#include "lddm/session/session.hpp"
#include "lddm/session/session_manager.hpp"
#include "lddm/weston/weston_manager.hpp"
#include "lddm/weston/weston_config.hpp"
#include "lddm/ldde/ldde_manager.hpp"
#include "lddm/ldde/ldde_config.hpp"
#include "lddm/recovery/recovery_manager.hpp"
#include "lddm/recovery/recovery_config.hpp"

#include <iostream>
#include <string>
#include <string_view>
#include <vector>
#include <chrono>
#include <thread>

namespace {

void print_version() {
    std::cout << "lddm (LinuxDroid Display Manager) " << lddm::Version::string() << "\n"
              << "Commit: " << lddm::Version::git_commit() << "\n"
              << "Copyright (C) 2026 LinuxDroid Project\n";
}

void print_help(const char* program_name) {
    std::cout << "Usage: " << program_name << " [OPTIONS]\n\n"
              << "Options:\n"
              << "  -h, --help                  Show this help message and exit\n"
              << "  -v, --version               Display version information and exit\n"
              << "  -c, --config <file>         Specify path to configuration file\n"
              << "      --validate-config <file> Validate configuration file and exit\n"
              << "      --migrate-config <in> [out] Migrate configuration file to latest schema and exit\n"
              << "      --log-level <level>      Set logging level (TRACE, DEBUG, INFO, WARN, ERROR, FATAL)\n"
              << "      --dry-run               Initialize and validate environment then exit\n";
}

} // namespace

int main(int argc, char* argv[]) {
    std::string config_path;
    std::string validate_config_path;
    std::string migrate_in_path;
    std::string migrate_out_path;
    std::string log_level_str;
    bool dry_run = false;

    const std::vector<std::string_view> args(argv + 1, argv + argc);

    for (std::size_t i = 0; i < args.size(); ++i) {
        if (args[i] == "-v" || args[i] == "--version") {
            print_version();
            return 0;
        }
        if (args[i] == "-h" || args[i] == "--help") {
            print_help(argv[0]);
            return 0;
        }
        if (args[i] == "-c" || args[i] == "--config") {
            if (i + 1 < args.size()) {
                config_path = std::string(args[++i]);
            } else {
                std::cerr << "Error: --config requires a file path\n";
                return 1;
            }
        } else if (args[i] == "--validate-config") {
            if (i + 1 < args.size()) {
                validate_config_path = std::string(args[++i]);
            } else {
                std::cerr << "Error: --validate-config requires a file path\n";
                return 1;
            }
        } else if (args[i] == "--migrate-config") {
            if (i + 1 < args.size()) {
                migrate_in_path = std::string(args[++i]);
                if (i + 1 < args.size() && !args[i + 1].starts_with("-")) {
                    migrate_out_path = std::string(args[++i]);
                }
            } else {
                std::cerr << "Error: --migrate-config requires an input file path\n";
                return 1;
            }
        } else if (args[i] == "--log-level") {
            if (i + 1 < args.size()) {
                log_level_str = std::string(args[++i]);
            } else {
                std::cerr << "Error: --log-level requires a level argument\n";
                return 1;
            }
        } else if (args[i] == "--dry-run") {
            dry_run = true;
        } else {
            std::cerr << "Unknown argument: " << args[i] << "\n";
            print_help(argv[0]);
            return 1;
        }
    }

    // Config validation mode
    if (!validate_config_path.empty()) {
        lddm::ConfigParser parser;
        auto parse_res = parser.parse_file(validate_config_path);
        if (!parse_res.has_value()) {
            std::cerr << "Configuration parse error: " << parse_res.error() << "\n";
            return 1;
        }
        auto val_res = lddm::ConfigValidator::validate(parse_res.value());
        if (!val_res.has_value()) {
            std::cerr << "Configuration validation error: " << val_res.error() << "\n";
            return 1;
        }
        std::cout << "Configuration file " << validate_config_path << " is valid.\n";
        return 0;
    }

    // Config migration mode
    if (!migrate_in_path.empty()) {
        auto mig_res = lddm::ConfigMigrator::migrate_file(migrate_in_path, migrate_out_path);
        if (!mig_res.has_value()) {
            std::cerr << "Configuration migration error: " << mig_res.error().message() << "\n";
            return 1;
        }
        std::cout << "Configuration migrated successfully.\n";
        return 0;
    }

    // Initialize lifecycle state machine
    lddm::LifecycleStateMachine lifecycle;

    // Transition to INITIALIZING
    if (auto res = lifecycle.transition_to(lddm::LifecycleState::INITIALIZING, "Starting LDDM subsystem"); !res) {
        std::cerr << "Fatal: Failed to enter INITIALIZING state: " << res.error() << "\n";
        return 1;
    }

    // Initialize signals
    if (auto sig_res = lddm::SignalHandler::initialize(); !sig_res) {
        LDDM_LOG_FATAL(lddm::LogSubsystem::PLATFORM, "Failed to initialize signals: {}", sig_res.error().to_string());
        (void)lifecycle.transition_to(lddm::LifecycleState::FAILED, "Signal initialization failure");
        return 1;
    }

    // Load configuration
    lddm::ConfigManager config_mgr;
    std::optional<std::string> cfg_override = config_path.empty() ? std::nullopt : std::make_optional(config_path);
    if (auto cfg_res = config_mgr.load_standard(cfg_override); !cfg_res) {
        LDDM_LOG_FATAL(lddm::LogSubsystem::CONFIG, "Failed to load configuration: {}", cfg_res.error().to_string());
        (void)lifecycle.transition_to(lddm::LifecycleState::FAILED, "Config load failure");
        return 1;
    }

    const auto& config = config_mgr.config();

    // Configure logger
    if (!log_level_str.empty()) {
        if (auto lvl = lddm::parse_log_level(log_level_str)) {
            lddm::Logger::instance().set_level(*lvl);
        }
    } else {
        lddm::Logger::instance().set_level(config.logging.level);
    }

    if (!config.logging.file_path.empty()) {
        lddm::Logger::instance().add_sink(std::make_shared<lddm::FileSink>(config.logging.file_path));
    }

    LDDM_LOG_INFO(lddm::LogSubsystem::LDDM, "Starting LDDM version {}", lddm::Version::string());

    // Transition to READY
    if (auto res = lifecycle.transition_to(lddm::LifecycleState::READY, "All subsystems initialized"); !res) {
        LDDM_LOG_FATAL(lddm::LogSubsystem::LDDM, "Failed to enter READY state: {}", res.error().to_string());
        return 1;
    }

    if (dry_run) {
        LDDM_LOG_INFO(lddm::LogSubsystem::LDDM, "Dry-run mode: stopping after READY state reached");
        (void)lifecycle.transition_to(lddm::LifecycleState::STOPPING, "Dry-run shutdown");
        (void)lifecycle.transition_to(lddm::LifecycleState::STOPPED, "Dry-run complete");
        lddm::SignalHandler::restore_defaults();
        return 0;
    }

    // Transition to STARTING
    if (auto res = lifecycle.transition_to(lddm::LifecycleState::STARTING, "Spawning primary session"); !res) {
        LDDM_LOG_FATAL(lddm::LogSubsystem::LDDM, "Failed to enter STARTING state: {}", res.error().to_string());
        return 1;
    }

    // Create primary session via SessionManager
    lddm::SessionManager session_manager;
    auto session_cfg = lddm::SessionConfig::from_lddm_config(config, lddm::SessionId("session-default"));

    auto create_res = session_manager.create_session(std::move(session_cfg));
    if (!create_res.has_value()) {
        LDDM_LOG_FATAL(lddm::LogSubsystem::SESSION, "Failed to create session: {}", create_res.error().to_string());
        (void)lifecycle.transition_to(lddm::LifecycleState::FAILED, "Session creation failed");
        return 1;
    }

    auto active_session = create_res.value();

    // 1. Attach Weston compositor manager
    lddm::weston::WestonConfig wcfg;
    wcfg.executable = config.weston.executable;
    wcfg.config_path = config.weston.config_path;
    wcfg.socket_name = config.weston.socket_name.empty() ? config.session.wayland_display : config.weston.socket_name;
    wcfg.backend = config.weston.backend;
    wcfg.additional_args = config.weston.additional_args;
    wcfg.startup_timeout_ms = config.process.startup_timeout_ms;
    wcfg.stop_timeout_ms = config.process.stop_timeout_ms;
    auto weston_mgr = std::make_shared<lddm::weston::WestonManager>(wcfg);
    active_session->attach_compositor(weston_mgr);

    // 2. Attach LDDE desktop environment manager
    lddm::ldde::LddeConfig lcfg;
    lcfg.executable = config.ldde.executable;
    lcfg.session_target = config.ldde.session_target;
    lcfg.autostart = config.ldde.autostart;
    lcfg.startup_timeout_ms = config.process.startup_timeout_ms;
    lcfg.stop_timeout_ms = config.process.stop_timeout_ms;
    lcfg.readiness_timeout_ms = config.process.startup_timeout_ms;
    auto ldde_mgr = std::make_shared<lddm::ldde::LddeManager>(lcfg);
    active_session->attach_desktop(ldde_mgr);

    // 3. Attach Recovery manager
    lddm::recovery::RecoveryConfig rcfg;
    rcfg.max_attempts = config.process.max_restart_count;
    rcfg.window_ms = config.process.restart_window_seconds * 1000;
    auto rec_mgr = std::make_shared<lddm::recovery::RecoveryManager>(active_session.get(), rcfg);
    active_session->attach_recovery(rec_mgr);

    if (auto init_res = active_session->initialize(); !init_res) {
        LDDM_LOG_ERROR(lddm::LogSubsystem::SESSION, "Failed to initialize session: {}", init_res.error().to_string());
        (void)lifecycle.transition_to(lddm::LifecycleState::FAILED, "Session initialization failed");
        return 1;
    }

    LDDM_LOG_INFO(lddm::LogSubsystem::LDDM, "[INFO] Starting LDDM");
    if (auto start_res = active_session->start(); !start_res) {
        LDDM_LOG_ERROR(lddm::LogSubsystem::SESSION, "Failed to start session: {}", start_res.error().to_string());
        (void)lifecycle.transition_to(lddm::LifecycleState::FAILED, "Session startup failed");
        return 1;
    }

    // Transition to RUNNING
    if (auto res = lifecycle.transition_to(lddm::LifecycleState::RUNNING, "LDDM active and running"); !res) {
        LDDM_LOG_FATAL(lddm::LogSubsystem::LDDM, "Failed to enter RUNNING state: {}", res.error().to_string());
        return 1;
    }

    LDDM_LOG_INFO(lddm::LogSubsystem::LDDM, "[INFO] LDDM started");
    LDDM_LOG_INFO(lddm::LogSubsystem::LDDM, "LDDM is running. Waiting for signals...");

    // Event loop: wait for termination signal
    while (!lddm::SignalHandler::is_termination_requested()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        if (active_session && active_session->supervisor()) {
            active_session->supervisor()->reap_exited_processes();
        }
    }

    int sig = lddm::SignalHandler::last_signal_received();
    LDDM_LOG_INFO(lddm::LogSubsystem::LDDM, "Termination requested via signal {}", sig);

    // Transition to STOPPING
    (void)lifecycle.transition_to(lddm::LifecycleState::STOPPING, "Graceful termination initiated");

    // Stop all sessions
    (void)session_manager.stop_all_sessions();

    // Transition to STOPPED
    (void)lifecycle.transition_to(lddm::LifecycleState::STOPPED, "Shutdown complete");

    lddm::SignalHandler::restore_defaults();
    LDDM_LOG_INFO(lddm::LogSubsystem::LDDM, "LDDM stopped cleanly");
    lddm::Logger::instance().flush();

    return 0;
}

