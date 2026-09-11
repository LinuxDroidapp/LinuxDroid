# LDDM — LinuxDroid Display Manager

[![C++20](https://img.shields.io/badge/C%2B%2B-20-blue.svg)](https://en.wikipedia.org/wiki/C%2B%2B20)
[![CMake](https://img.shields.io/badge/CMake-3.20%2B-brightgreen.svg)](https://cmake.org/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

**LDDM (LinuxDroid Display Manager)** is the display and graphical session manager for the LinuxDroid project. It is responsible for the graphical session lifecycle, display server orchestration, process supervision, and desktop environment integration.

---

## Architectural Role

LDDM sits between the LinuxDroid runtime and the graphical Linux desktop stack:

```text
LinuxDroid Android App
        ↓
LinuxDroid Runtime
        ↓
Guest Init
        ↓
LDDM (LinuxDroid Display Manager)
        ↓
Weston (Wayland Compositor)
        ↓
LDDE (LinuxDroid Desktop Environment)
        ↓
Linux Applications
```

LDDM provides a pure Linux-native session manager with zero Android API couplings, ensuring clean portability within LinuxDroid PRoot/container environments.

---

## Features (Phases L0 through L6)

* **Modern C++20 Architecture**: Strict RAII, deterministic lifetimes, move semantics, and strong types.
* **Production Packaging & Rootfs Integration (Phase L6)**:
  * Authoritative Debian packaging (`linuxdroid-display-manager`) for `arm64` and `amd64`.
  * Standard Linux FHS filesystem layout (`/usr/bin/lddm`, `/usr/bin/linuxdroid-display-manager` symlink, `/etc/linuxdroid/lddm.conf` conffile, `/etc/lddm/lddm.conf` fallback symlink, `/usr/share/doc/linuxdroid-display-manager/`).
  * Configuration schema migration engine (`ConfigMigrator`) supporting schema version detection (v0 -> v1) while preserving user-defined configurations and comments.
  * Idempotent maintainer scripts (`postinst`, `prerm`, `postrm`) respecting remove vs purge lifecycle semantics.
  * Explicit ownership boundary between package-installed files and runtime-owned directories (`/run/lddm`, `/run/user/<uid>`).
  * Init-system independent: zero hard systemd PID 1 requirements, enabling direct invocation by LinuxDroid Guest Init.
* **Production Session Recovery (Phase L5)**:
  * Resilient recovery manager (`RecoveryManager`) handling compositor crashes, desktop environment failures, and readiness timeouts.
  * Dedicated 5-state recovery finite state machine (`Idle` -> `Recovering` -> `Verifying` -> `Recovered` / `Failed`).
  * Strongly typed recovery policies (`NoRecovery`, `ComponentRestart`, `SessionRestart`, `FailSession`) and failure classification (`RecoveryReason`).
  * Wayland dependency ordering: Weston compositor crash enforces client LDDE teardown before Weston relaunch, preventing dangling Wayland client sockets.
  * Robust loop prevention with sliding time windows (`window_ms`), bounded attempt budgets (`max_attempts`), and exponential backoff calculations.
  * Stale runtime resource cleanup using non-blocking POSIX socket probing (`SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC`) to eliminate dead sockets without blocking.
  * Comprehensive recovery diagnostics, timestamped transition auditing, consecutive failure counts, and bounded history logs.
* **Production LDDE Session Integration (Phase L4)**:
  * Manages the LinuxDroid Desktop Environment (LDDE) as an external supervised process attached to an LDDM Session.
  * Dedicated 8-state finite state machine (`Created` -> `Preparing` -> `Starting` -> `WaitingReady` -> `Running` -> `Stopping` -> `Stopped` / `Failed`).
  * Pure Linux-native readiness detection via protocol file (`STATUS=READY\nVERSION=1\nPID=<pid>`) and UNIX domain stream socket.
  * Proactive process liveness monitoring via `/proc/<pid>/stat` to abort immediately on premature crash or zombie state.
  * Startup rollback policy: automatic Weston termination and session failure if LDDE fails to start.
  * Strict programmatic reverse-order shutdown: LDDE terminates cleanly before Weston compositor shuts down.
  * Explicit graphical readiness boundary (`session.is_graphical_session_ready()`).
  * Comprehensive desktop diagnostics, transition history audit trail, and duration metrics.
* **Production Weston Compositor Manager (Phase L3)**:
  * Manages the Weston Wayland compositor as an external supervised process attached to an LDDM Session.
  * Dedicated 8-state compositor finite state machine (`Created` -> `Preparing` -> `Starting` -> `WaitingReady` -> `Running` -> `Stopping` -> `Stopped` / `Failed`).
  * Pure POSIX `AF_UNIX` stream socket readiness detection without `libwayland-client` dependencies.
  * Automated `weston.ini` generation and environment variable configuration (`XDG_RUNTIME_DIR`, `WAYLAND_DISPLAY`, `XDG_SESSION_TYPE`).
  * Detailed compositor diagnostics, timestamped transition auditing, and startup duration metrics.
  * Asynchronous process event monitoring with crash recovery and graceful `SIGTERM` -> `SIGKILL` teardown.
* **Production Process Supervisor (Phase L2)**:
  * Pure Linux-native process supervision with deterministic `CREATED` -> `STARTING` -> `RUNNING` -> `STOPPING` -> `EXITED`/`FAILED` state machine.
  * Fork/execve isolation using an anonymous `O_CLOEXEC` pipe for deterministic child startup error detection without zombies.
  * Process group isolation (`setpgid(0, 0)`) and group signaling (`kill(-pgid, sig)`) to eliminate rogue orphan sub-processes.
  * Configurable standard stream redirection policies (`Inherit`, `Null`, `Close`, `File`, `Pipe`).
  * Two-tier graceful termination: `SIGTERM` followed by timeout escalation to `SIGKILL`.
  * Non-blocking reaping (`waitpid(WNOHANG)`) and detailed `ProcessExitInfo` audit tracking.
  * Thread-safe `ProcessRegistry` supporting queries by PID, handle, and component name.
  * Deep integration with `Session` lifecycle (auto-teardown, resource cleanup, environment inheritance).
* **Production Session Model (Phase L1)**:
  * Strongly-typed `SessionId` generation (timestamp + atomic counter) and `SessionIdentity`.
  * Finite state machine (`SessionStateMachine`) with transition validation, history recording, and observer callbacks.
  * Isolated per-session runtime directory hierarchy (`SessionPaths`) with `0700` POSIX permission enforcement.
  * Deterministic multi-layer session environment generation (`SessionEnvironment`) with redacted diagnostics.
  * Centralized `SessionManager` managing multi-session registries, active session selection, and graceful teardown.
  * Robust resource tracking (`SessionResourceTracker`) ensuring zero leaked file descriptors or temporary files.
* **Unified Error Model**: Categorized errors (`Configuration`, `Session`, `Process`, `Platform`, `Compositor`, `Desktop`, `Resource`, `Recovery`, `Internal`) with stable codes, source location tracking, and type-safe `Result<T>` propagation.
* **Centralized Structured Logging**: Thread-safe multi-sink logging (`StreamSink`, `FileSink`, `MemorySink`) with severity levels (`TRACE` to `FATAL`), subsystem filtering, and ANSI terminal colorization.
* **Linux-Native Configuration**: INI-style configuration parser with default fallback, schema validation, and typed structures.
* **Platform Abstraction Layer**: Safe RAII Linux primitives including `UniqueFd`, signal handling via self-pipe trick, high-resolution monotonic clocks, and XDG directory resolution.
* **Session Contract**: Abstract session component interfaces (`ISessionComponent`, `ICompositorInstance`, `IDesktopEnvironmentInstance`).
* **Zero External Dependency Test Harness**: Complete unit and integration test suite (52 targets) running seamlessly under `CTest`.

---

## Project Structure

```text
LDDM/
├── CMakeLists.txt              # Root build configuration
├── README.md                   # Project overview and quickstart
├── include/lddm/               # Public API headers
│   ├── version.hpp.in          # Semantic version template
│   ├── core/                   # Error, Result, Lifecycle, Types
│   ├── logging/                # Logger, Sinks, Levels, Messages
│   ├── config/                 # Config types, Parser, Validator, Manager
│   ├── platform/               # UniqueFd, Clock, Environment, Paths, Signals, Process primitives
│   ├── process/                # Process, Spec, Types, Events, Diagnostics, Registry, Supervisor
│   ├── weston/                 # WestonManager, Config, Spec, Readiness, Diagnostics, Types
│   ├── ldde/                   # LddeManager, Config, Spec, Readiness, Diagnostics, Types
│   ├── recovery/               # RecoveryManager, Config, Diagnostics, Types
│   └── session/                # Session, Contracts, States, Config, Diagnostics, Paths
├── src/                        # Implementation sources
│   ├── main.cpp                # LDDM daemon CLI entry point
│   ├── core/
│   ├── logging/
│   ├── config/
│   ├── platform/
│   ├── process/
│   ├── weston/
│   ├── ldde/
│   ├── recovery/
│   └── session/
├── tests/                      # Test suite
│   ├── CMakeLists.txt
│   ├── test_framework.hpp      # Lightweight test framework
│   ├── unit/                   # Unit tests (version, error, config, lifecycle, session, process, weston, ldde, recovery)
│   └── integration/            # Lifecycle, supervisor, weston, ldde, and recovery integration tests
├── config/                     # Configuration files
│   ├── lddm.conf.example       # Production example config
│   └── lddm.conf.defaults      # Built-in defaults reference
├── packaging/                  # Systemd service units and packaging files
│   └── lddm.service.in
└── docs/                       # Developer documentation
    ├── architecture.md
    ├── configuration.md
    ├── lifecycle.md
    ├── logging.md
    ├── session.md
    ├── process.md
    ├── weston.md
    ├── ldde.md
    ├── recovery.md
    └── development.md
```

---

## Building and Testing

### Prerequisites

* GCC 13+ or Clang 18+ (C++20 compliant)
* CMake 3.20+
* GNU Make or Ninja

### Build Instructions

```bash
# 1. Configure Release build
cmake -B build -DCMAKE_BUILD_TYPE=Release -DLDDM_BUILD_TESTS=ON -DLDDM_WARNINGS_AS_ERRORS=ON

# 2. Build binaries and libraries
cmake --build build -j$(nproc)

# 3. Run test suite (56/56 tests)
ctest --test-dir build --output-on-failure

# 4. Install locally
sudo cmake --install build

# 5. Build Debian package (.deb)
cmake --build build --target package_deb
```

---

## CLI Usage

```text
Usage: lddm [OPTIONS]

Options:
  -h, --help                  Show this help message and exit
  -v, --version               Display version information and exit
  -c, --config <file>         Specify path to configuration file
      --validate-config <file> Validate configuration file and exit
      --migrate-config <in> [out] Migrate legacy configuration file to latest schema
      --log-level <level>      Set logging level (TRACE, DEBUG, INFO, WARN, ERROR, FATAL)
      --dry-run               Initialize and validate environment then exit
```

Validate a configuration file:
```bash
./build/lddm --validate-config config/lddm.conf.example
```

Migrate a configuration file:
```bash
./build/lddm --migrate-config /etc/lddm/lddm.conf /etc/linuxdroid/lddm.conf
```

Run in dry-run mode:
```bash
./build/lddm --dry-run
```

---

## Phase Roadmap

* [x] **Phase L0: Production Foundation** (Build, logging, config, lifecycle, error model)
* [x] **Phase L1: Session Model** (Session identity, environment, state machine, paths, resources)
* [x] **Phase L2: Process Supervisor** (Linux-native process lifecycle, group isolation, stream redirection, graceful termination, reaping)
* [x] **Phase L3: Weston Manager** (Compositor spawning, socket verification, crash detection)
* [x] **Phase L4: LDDE Session Integration** (Desktop environment launcher, desktop components, readiness protocol, rollback)
* [x] **Phase L5: Production Session Recovery** (Failure classification, dependency ordering, exponential backoff, socket cleanup, stability)
* [x] **Phase L6: Production Packaging** (Debian packaging, conffile protection, schema migration, filesystem layout, rootfs integration)




