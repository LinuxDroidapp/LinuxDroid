# LDDM Architecture

## 1. System Role & Topology

**LDDM (LinuxDroid Display Manager)** is the display and graphical session manager within the LinuxDroid project. It bridges the low-level LinuxDroid runtime and the high-level graphical desktop environment.

```text
+---------------------------------------------------------+
|                LinuxDroid Android App                   |
|  - Android UI & Lifecycle                               |
|  - PRoot / Container Sandboxing                         |
|  - Hardware bridge & Termux-X11/Wayland Surface View    |
+---------------------------------------------------------+
                            |
                            v
+---------------------------------------------------------+
|                  LinuxDroid Runtime                     |
|  - Rootfs orchestration                                 |
|  - Guest environment mounting & initialization          |
+---------------------------------------------------------+
                            |
                            v
+---------------------------------------------------------+
|                      Guest Init                         |
|  - System init scripts / systemd / custom supervisor    |
+---------------------------------------------------------+
                            |
                            v
+---------------------------------------------------------+
|                        LDDM                             |
|            LinuxDroid Display Manager                   |
|  - Session orchestration & configuration                |
|  - Lifecycle state machine                              |
|  - Compositor & Desktop process supervision             |
+---------------------------------------------------------+
                            |
                            v
+---------------------------------------------------------+
|                       Weston                            |
|                 Wayland Compositor                      |
|  - Headless / Wayland backend rendering                 |
|  - Wayland protocol endpoints (/run/lddm/wayland-0)     |
+---------------------------------------------------------+
                            |
                            v
+---------------------------------------------------------+
|                        LDDE                             |
|          LinuxDroid Desktop Environment                 |
|  - Desktop panels, session services, window manager     |
+---------------------------------------------------------+
                            |
                            v
+---------------------------------------------------------+
|                  Linux Applications                     |
|  - Terminal, Browsers, Productivity tools               |
+---------------------------------------------------------+
```

---

## 2. Component Boundaries & Responsibilities

| Component | Owned Responsibilities | Explicit Non-Responsibilities |
| :--- | :--- | :--- |
| **Android App** | Android UI, PRoot bindings, storage access permissions, Android surface rendering. | Linux graphical session management, Wayland socket lifecycles. |
| **LinuxDroid Runtime / Guest Init** | Container filesystem setup, user namespace mapping, init invocation. | Display configuration, compositor argument tuning, session state recovery. |
| **LDDM** | **Graphical session lifecycle**, config parsing, process supervision, Wayland environment setup, graceful recovery. | Android JNI calls, rendering pixels directly, window management. |
| **Weston** | Surface composition, Wayland client protocol handling, input event delivery. | Session authentication, desktop panel startup, desktop lifecycle supervision. |
| **LDDE** | User desktop experience, system tray, app launcher, notification daemon. | Low-level compositor process spawning or display server initialization. |

---

## 3. Subsystem Breakdown

1. **`lddm::core`**:
   - `LifecycleStateMachine`: Deterministic transition state machine (`CREATED` -> `STOPPED`/`FAILED`).
   - `Error` & `Result<T>`: Strict, unified error taxonomy without raw errno leaks.
   - `types`: Strongly typed IDs (`SessionId`, `UserId`, `ProcessId`).

2. **`lddm::logging`**:
   - Centralized, thread-safe logger with structured log records (`LogMessage`).
   - Sinks: `StreamSink` (ANSI colored terminal), `FileSink`, `MemorySink` (for unit testing).
   - Subsystems: `LDDM`, `SESSION`, `PROCESS`, `CONFIG`, `PLATFORM`, `WESTON`, `LDDE`.

3. **`lddm::config`**:
   - Centralized INI configuration parser supporting sections, types, validation, and defaults.
   - System path: `/etc/linuxdroid/lddm.conf` (with `/etc/lddm/lddm.conf` fallback symlink), user path: `~/.config/lddm/lddm.conf`.
   - `ConfigMigrator`: Schema-versioned configuration upgrade engine (v0 -> v1) with in-place, out-of-place, and atomic update capabilities.

4. **`lddm::platform`**:
   - RAII wrappers: `UniqueFd`.
   - OS abstractions: high-resolution monotonic clock, environment management, XDG directory resolution, signal handling (`sigaction` + self-pipe).

5. **`lddm::session`** (Phase L1):
   - `SessionIdentity`: Unique `SessionId` (monotonic sequence + timestamp), user credentials, session type.
   - `SessionStateMachine`: State machine with transition validation, history recording, and observer callbacks.
   - `SessionPaths`: Per-session directory isolation (`/run/lddm/<session-id>`) with `0700` permission enforcement.
   - `SessionEnvironment`: Deterministic multi-layer environment resolution (base -> session -> desktop -> overrides) with redacted diagnostic summaries.
   - `SessionResourceTracker`: RAII tracking of open file descriptors and temporary files ensuring zero leaks on stop or failure.
   - `SessionDiagnostics`: Full transition history and failure recording for inspectability.
   - `SessionManager`: Multi-session registry, active session designation, and controlled teardown.
   - Contracts: `ISessionComponent`, `ICompositorInstance`, `IDesktopEnvironmentInstance`.

6. **`lddm::process`** (Phase L2):
   - `Process`: Encapsulates a single supervised OS process lifecycle with state transitions (`CREATED` -> `STARTING` -> `RUNNING` -> `STOPPING` -> `EXITED`/`FAILED`).
   - `ProcessSpec`: Executable path, argument vector, environment map, working directory, and stream policies.
   - `StreamPolicy`: Configurable stream routing (`Inherit`, `Null`, `Close`, `File`, `Pipe`).
   - `ProcessRegistry`: Thread-safe registry indexable by PID, handle, and component name.
   - `ProcessSupervisor`: High-level process lifecycle orchestrator with non-blocking reaping (`waitpid(WNOHANG)`), process group isolation (`setpgid`), graceful termination (`SIGTERM`), and timeout escalation to `SIGKILL`.
   - `ProcessDiagnostics`: Process runtime metrics, exit info recording, and lifecycle history audit logs.
   - `ProcessEventDispatcher`: Observer event stream (`Started`, `Exited`, `Failed`, `Signaled`).
   - Integration with `Session`: Owned directly by `Session`, initialized in `session.initialize()`, environment synchronized in `session.start()`, and stopped gracefully in `session.stop()`.

7. **`lddm::weston`** (Phase L3):
   - `WestonManager`: Production compositor manager implementing `ICompositorInstance` and `ISessionComponent`.
   - `WestonConfig`: Strongly typed compositor configuration with validation and defaults.
   - `WestonConfigWriter`: INI configuration generator producing minimal, secure `weston.ini` files.
   - `WestonSpec`: Compositor specification generating runtime command-line arguments and mapping to `ProcessSpec`.
   - `WestonReadinessDetector`: Non-blocking Wayland socket readiness prober using `AF_UNIX` stream socket connections without external library dependencies.
   - `WestonDiagnostics`: Dedicated operational metrics, transition audit logging, and error tracking.
   - `WestonExecutableResolver`: Resolves and validates the Weston binary location.
   - Integration with `Session`: Attached via `session.attach_compositor()`, supervised through `session.supervisor()`, and stopped cleanly in reverse dependency order.

8. **`lddm::ldde`** (Phase L4):
   - `LddeManager`: Production desktop environment manager implementing `IDesktopEnvironmentInstance` and `ISessionComponent`.
   - `LddeConfig`: Strongly typed desktop configuration (executable path, startup args, environment overrides, readiness mode, timeouts).
   - `LddeSpec`: Desktop environment process specification generating command arguments and mapping to `ProcessSpec`.
   - `LddeReadinessDetector`: Zero-dependency readiness prober supporting protocol file (`STATUS=READY\nVERSION=1\nPID=<pid>`) and UNIX domain stream sockets, with active `/proc/<pid>/stat` process liveness and zombie detection.
   - `LddeDiagnostics`: Operational telemetry, startup and shutdown timing, transition audit logs, and crash tracking.
   - `LddeExecutableResolver`: Automatic discovery and validation of `ldde-session` across standard and configured paths.
   - Integration with `Session`: Attached via `session.attach_desktop()`, verified via `session.is_graphical_session_ready()`, supervised through `session.supervisor()`, startup rollback on failure, and clean reverse-order teardown.

9. **`lddm::recovery`** (Phase L5):
   - `RecoveryManager`: Production fault recovery orchestrator managing policies, exponential backoff, retry windows, and failure loop prevention.
   - `RecoveryState`: Dedicated 5-state lifecycle (`Idle`, `Recovering`, `Verifying`, `Recovered`, `Failed`).
   - `RecoveryReason` & `RecoveryPolicy`: Strongly typed failure classification and policy mapping (`NoRecovery`, `ComponentRestart`, `SessionRestart`, `FailSession`).
   - `RecoveryConfig`: Bounded retry parameters, backoff timing calculator, and precondition enforcement.
   - `RecoveryDiagnostics`: Real-time telemetry, consecutive failure metrics, and bounded audit logs.
   - Dependency Ordering: Compositor crash triggers LDDE client teardown prior to Weston re-launch and re-verification, followed by clean LDDE re-launch.
   - Stale Resource Cleanup: Non-blocking Wayland display socket probing (`SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC`) to eliminate dead sockets without blocking.
   - Integration with `Session`: Attached via `session.attach_recovery()`, hooks into `ProcessSupervisor` exit events, transitions session to `RECOVERING`, and restores to `RUNNING` or `FAILED`.

10. **`packaging`** (Phase L6):
    - Authoritative Debian package: `linuxdroid-display-manager` for `arm64` and `amd64`.
    - Filesystem layout: `/usr/bin/lddm`, `/usr/bin/linuxdroid-display-manager` symlink, `/etc/linuxdroid/lddm.conf` conffile, `/etc/lddm/lddm.conf` fallback symlink.
    - Migration & upgrade safety: Automatic schema migration preserving user-edited configuration parameters.
    - Idempotent maintainer scripts: `postinst`, `prerm`, and `postrm` handling install, upgrade, remove, and purge lifecycles.
    - Init system independence: Designed for direct invocation by LinuxDroid Guest Init without requiring systemd as PID 1.
    - Clear ownership boundary: Distinguishes package-installed static files from dynamic runtime assets (`/run/lddm`, `/run/user/<uid>`).

---

## 4. Phase Completion Status

- [x] **Phase L0 — Production Foundation**: Build system, versioning, error taxonomy, logging, lifecycle primitives.
- [x] **Phase L1 — Production Session Model**: Identity, environment, paths, state machine, resource tracker, multi-session manager.
- [x] **Phase L2 — Production Process Supervisor**: Supervised processes, process groups, streams, graceful termination, reaping.
- [x] **Phase L3 — Production Weston Manager**: Compositor process orchestration, Wayland socket readiness detection, crash handling.
- [x] **Phase L4 — Production LDDE Session Integration**: Desktop environment orchestration, file/socket readiness, startup rollback.
- [x] **Phase L5 — Production Session Recovery**: Resilient fault recovery, exponential backoff, dependency ordering, stale socket cleanup.
- [x] **Phase L6 — Production Packaging**: Debian package generation, conffile protection, schema migration, filesystem layout, rootfs integration.




