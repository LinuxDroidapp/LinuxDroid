# LDDM Weston Compositor Manager (Phase L3)

## Overview

The **Weston Manager** (`lddm::weston::WestonManager`) integrates the [Weston Wayland Compositor](https://wayland.freedesktop.org/) into **LDDM (LinuxDroid Display Manager)** as the graphical compositor owned and supervised by an active LDDM Session.

In accordance with Phase L3 specifications:
- Weston is treated as a managed external child process supervised through the **Phase L2 Process Supervisor** (`ProcessSupervisor`), never embedded as an in-process library.
- Compositor lifecycle is tracked via a dedicated 8-state finite state machine (`WestonState`).
- Wayland socket readiness is verified deterministically using pure POSIX `AF_UNIX` stream socket connection probes without linking against `libwayland-client` or any external SDKs.
- Environment variables, configuration files (`weston.ini`), and runtime directories (`XDG_RUNTIME_DIR`) are prepared with strict permissions (`0700`) and deterministic isolation.
- Shutdown is graceful and deterministic, escalating from `SIGTERM` to `SIGKILL` if necessary, followed by resource cleanup.

---

## 1. Architectural Position

```text
LinuxDroid Android App
        ↓
LinuxDroid Runtime
        ↓
Guest Init
        ↓
LDDM (Display & Session Manager)
  ├── SessionManager
  │    └── Session ("session-xxxx")
  │         ├── SessionIdentity & Paths
  │         ├── ProcessSupervisor (L2)
  │         │    └── Process ("weston", PID)
  │         └── WestonManager (L3, implements ICompositorInstance)
  │              ├── WestonConfig & WestonConfigWriter (weston.ini)
  │              ├── WestonSpec -> ProcessSpec
  │              ├── WestonReadinessDetector (AF_UNIX connect)
  │              └── WestonDiagnostics (Metrics & Audit Log)
        ↓
Weston Compositor (Wayland Server)
  ├── wayland-0 (UNIX Domain Socket)
  └── weston.log
        ↓
LDDE Desktop Environment (Phase L4)
        ↓
Linux GUI Applications
```

---

## 2. Component Separation & Contracts

1. **`Session` (`lddm::Session`)**:
   - Owns the session context: identity, base runtime directories (`XDG_RUNTIME_DIR`), environment map, and the `ProcessSupervisor` instance.
   - Attaches `WestonManager` via `session.attach_compositor(weston_mgr)`.
   - Propagates session initialization (`session.initialize()`) and teardown (`session.stop()`).

2. **`ProcessSupervisor` (`lddm::ProcessSupervisor`)**:
   - Manages child process spawning (`fork` + `execve`), PID tracking, signal propagation (`SIGTERM`/`SIGKILL`), and process group isolation.
   - Emits asynchronous events (`Started`, `Exited`, `Signaled`, `Terminated`) to registered listeners.

3. **`WestonManager` (`lddm::weston::WestonManager`)**:
   - Implements `ICompositorInstance` (and `ISessionComponent`).
   - Translates `WestonConfig` into `WestonSpec` and underlying `ProcessSpec`.
   - Manages configuration generation (`weston.ini`), Wayland socket path resolution, and readiness verification.
   - Subscribes to `ProcessSupervisor` process events to track unexpected crashes or exits.
   - Enforces the compositor state machine.

---

## 3. Weston Lifecycle State Machine

The compositor lifecycle is governed by an explicit 8-state machine:

```text
       ┌─────────┐
       │ CREATED │
       └────┬────┘
            │ prepare()
            ▼
      ┌───────────┐
      │ PREPARING │
      └─────┬─────┘
            │ start() [spawn process]
            ▼
      ┌──────────┐
      │ STARTING │
      └─────┬────┘
            │ [process running, wait for socket]
            ▼
    ┌───────────────┐
    │ WAITING_READY ├──────────────┐
    └───────┬───────┘              │
            │ connect() OK         │ readiness timeout / crash
            ▼                      ▼
       ┌─────────┐            ┌────────┐
       │ RUNNING │            │ FAILED │
       └────┬────┘            └───┬────┘
            │ stop()              │ stop()
            ▼                     │
      ┌──────────┐                │
      │ STOPPING ◄────────────────┘
      └─────┬────┘
            │ process reaped & cleanup
            ▼
       ┌─────────┐
       │ STOPPED │ (Terminal)
       └─────────┘
```

### Valid State Transitions
- `Created` -> `Preparing`, `Stopping`, `Failed`
- `Preparing` -> `Starting`, `Stopping`, `Failed`
- `Starting` -> `WaitingReady`, `Stopping`, `Failed`
- `WaitingReady` -> `Running`, `Stopping`, `Failed`
- `Running` -> `Stopping`, `Failed`
- `Stopping` -> `Stopped`, `Failed`
- `Failed` -> `Stopped`, `Stopping`
- `Stopped` -> *Terminal state* (idempotent repeated stop succeeds)

---

## 4. Configuration & Environment Preparation

### Directory Structure & Permissions
Weston requires `XDG_RUNTIME_DIR` with strict `0700` POSIX permissions:
```text
/tmp/lddm/session-xxxx/
├── run/                  <-- XDG_RUNTIME_DIR (0700)
│   ├── wayland-0         <-- Wayland UNIX domain socket
│   ├── wayland-0.lock    <-- Wayland socket lock file
│   └── weston.ini        <-- Auto-generated or custom configuration
└── logs/
    └── weston.log        <-- Compositor standard out & log
```

### Environment Variables
Prior to launching Weston, `WestonManager` populates:
- `XDG_RUNTIME_DIR`: Path to the dedicated session runtime directory.
- `WAYLAND_DISPLAY`: Name of the socket (default `wayland-0`).
- `XDG_SESSION_TYPE`: `"wayland"`.

### Configuration Generation (`weston.ini`)
When `auto_generate_config` is set or no custom `config_path` is specified, `WestonConfigWriter` generates a minimal, robust `weston.ini`:
```ini
# LDDM Auto-Generated Weston Configuration

[core]
idle-time=0
require-input=false

[shell]
locking=false
```

---

## 5. Wayland Socket Readiness Detection

LDDM avoids race conditions and premature application startup by actively probing the Wayland display socket.

### Probe Algorithm (`WestonReadinessDetector`)
1. **Process Liveness**: Inspects process status via `/proc/<pid>/stat` and `kill(pid, 0)`. If the process has exited or transitioned to a zombie (`'Z'`), readiness fails immediately with `CompositorCrash`.
2. **Filesystem Check**: Verifies that the socket file (`XDG_RUNTIME_DIR/wayland-0`) exists and `S_ISSOCK` is true.
3. **Connection Usability**: Creates an `AF_UNIX` stream socket (`SOCK_STREAM | SOCK_CLOEXEC`) and performs a non-blocking `connect()` to the socket path.
   - `connect() == 0`: Socket is bound, listening, and accepting client connections. Status: `WaylandConnectionUsable` (Weston is READY).
   - `ECONNREFUSED` or `EAGAIN`: Socket file exists but compositor has not called `listen()`. Status: `SocketCreatedUnusable` (continue polling).
   - `ENOENT`: Socket file does not yet exist. Status: `SocketNotCreated` (continue polling).
4. **Timeout Escalation**: If readiness is not attained within `startup_timeout_ms` (default 5000ms), the detector returns `CompositorTimeout`, triggering automatic process shutdown and transition to `Failed`.

---

## 6. Failure Recovery & Teardown Protocol

### Premature Exit / Crash Detection
`WestonManager` registers an asynchronous listener with `ProcessSupervisor`. When `ProcessEventType::Exited` or `Signaled` is received:
- Exit code and signal details are captured into `WestonDiagnostics`.
- If the compositor was in `Starting`, `WaitingReady`, or `Running` state, it is transitioned to `Failed` with a detailed error message.
- Associated session resources are cleaned up.

### Graceful Teardown (`stop()`)
1. Transitions to `Stopping`.
2. Invokes `supervisor->stop_process(process_handle, shutdown_timeout)`.
   - Sends `SIGTERM`.
   - Polls for process exit up to `stop_timeout_ms` (default 3000ms).
   - If the process fails to exit before the timeout, escalates to `SIGKILL`.
3. Unlinks socket and lock files (`wayland-0`, `wayland-0.lock`).
4. Cleans up auto-generated configuration files (`weston.ini`).
5. Records completion timestamp in diagnostics and transitions to `Stopped`.

---

## 7. Diagnostics & Metrics

`WestonDiagnostics` maintains an in-memory audit log and operational metrics:
- **Timestamps**: Creation, start, ready, and stop times.
- **Durations**: Startup duration (ready_time - start_time), active runtime duration.
- **Process Info**: Process ID, exit code, termination signal.
- **Transition History**: Timestamped sequence of state changes with human-readable rationale strings.
- **Errors**: Complete error chain with categories and descriptions.

---

## 8. Configuration Reference (`WestonConfig`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `executable` | `std::string` | `"/usr/bin/weston"` | Path or command name of the Weston executable. |
| `backend` | `std::string` | `"headless"` | Weston backend (`headless`, `drm`, `wayland`, `x11`). |
| `socket_name` | `std::string` | `"wayland-0"` | Name of the Wayland socket. |
| `config_path` | `std::string` | `""` | Path to custom `weston.ini` (empty = auto-generate). |
| `shell` | `std::string` | `""` | Shell plugin (`desktop-shell.so`, `kiosk-shell.so`). |
| `idle_time_seconds` | `std::uint32_t` | `0` | Screen idle timeout (0 disables idle blanking). |
| `require_input` | `bool` | `false` | Whether input devices are required to launch. |
| `startup_timeout_ms`| `std::uint32_t` | `5000` | Maximum ms to wait for socket readiness. |
| `stop_timeout_ms` | `std::uint32_t` | `3000` | Maximum ms to wait for graceful SIGTERM exit. |
| `auto_generate_config` | `bool` | `true` | Generate minimal `weston.ini` if none provided. |
| `additional_args` | `std::vector<std::string>` | `{}` | Extra command-line arguments passed to Weston. |
