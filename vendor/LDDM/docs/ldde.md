# LDDM LDDE Session Integration (Phase L4)

## Overview

The **LDDE Manager** (`lddm::ldde::LddeManager`) integrates the **LinuxDroid Desktop Environment (LDDE)** into **LDDM (LinuxDroid Display Manager)** as the graphical desktop shell component owned and supervised by an active LDDM Session.

In accordance with Phase L4 specifications:
- **Zero LDDE UI code in LDDM**: LDDE remains an independent Linux-native project located at `LinuxDroidapp/LDDE`. LDDM acts solely as the session manager and supervisor orchestrating the desktop session lifecycle.
- **Process Supervision**: LDDE is executed as an external supervised child process via the Phase L2 `ProcessSupervisor`.
- **Compositor Dependency**: LDDE runs as a Wayland client dependent on Weston (`lddm::weston::WestonManager`). It is started only after Weston signals Wayland socket readiness, and stopped before Weston terminates.
- **Readiness Protocol**: LDDM supports deterministic readiness detection via protocol file (`STATUS=READY\nVERSION=1\nPID=<pid>\n`) or UNIX domain stream socket, with active process liveness monitoring (`/proc/<pid>/stat`) to detect premature termination or crashes.
- **Startup Rollback**: Failure of LDDE during session startup triggers immediate Weston termination and sets the session state to `FAILED`, preventing orphan compositors from running without a desktop shell.
- **Deterministic Shutdown**: Orderly reverse teardown guarantees that LDDE terminates before Weston, preventing compositor disconnection crashes (`SIGPIPE` / `WL_DISPLAY_ERROR`).

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
  │         │    ├── Weston Process (PID_W)
  │         │    └── LDDE Process (PID_L)
  │         ├── WestonManager (L3, implements ICompositorInstance)
  │         │    ├── Wayland Compositor Socket: wayland-0
  │         │    └── weston.log
  │         └── LddeManager (L4, implements IDesktopEnvironmentInstance)
  │              ├── LddeConfig & Resolver (ldde-session / custom binary)
  │              ├── LddeSpec -> ProcessSpec
  │              ├── LddeReadinessDetector (file / socket protocol)
  │              └── LddeDiagnostics (Metrics & Audit Log)
        ↓
Weston Compositor (Wayland Server)
        ↓
LDDE Desktop Environment (Wayland Client)
  ├── Shell / Panel / Desktop Services
  └── Application Launcher
        ↓
Linux GUI Applications
```

---

## 2. Component Separation & Contracts

1. **`Session` (`lddm::Session`)**:
   - Owns the session context: identity, base runtime directories (`XDG_RUNTIME_DIR`), environment map, and the `ProcessSupervisor` instance.
   - Coordinates multi-component lifecycle via `attach_compositor(weston_mgr)` and `attach_desktop_environment(ldde_mgr)`.
   - Exposes `is_graphical_session_ready()`: returns `true` only when `SessionState == RUNNING`, `weston->is_running()`, and `ldde->is_running()`.

2. **`ProcessSupervisor` (`lddm::ProcessSupervisor`)**:
   - Manages child process execution, PID tracking, signal delivery (`SIGTERM`/`SIGKILL`), and process group isolation.
   - Emits asynchronous events (`Started`, `Exited`, `Signaled`, `Terminated`) to registered listeners.

3. **`WestonManager` (`lddm::weston::WestonManager`)**:
   - Manages the Wayland compositor process.
   - Verifies Wayland socket (`wayland-0`) creation and accessibility.

4. **`LddeManager` (`lddm::ldde::LddeManager`)**:
   - Implements `IDesktopEnvironmentInstance` and `ISessionComponent`.
   - Resolves executable location, configures environment variables, builds process specs, detects readiness, monitors liveness, and logs diagnostics.

---

## 3. Supervision Model

LDDE is supervised as a dedicated process:
- **Component ID**: `ldde`
- **Process Group**: Configured with its own process group (`setpgid(0, 0)`) so child applications or shell sub-services can be terminated collectively when the session exits.
- **Graceful Teardown**: Shutdown initiates with `SIGTERM` and waits up to `shutdown_timeout_ms` (default 5000 ms). If LDDE does not terminate within the window, the supervisor escalates to `SIGKILL`.
- **Crash Detection**: If LDDE terminates unexpectedly while the session is running, `LddeManager` transitions to `FAILED` and records exit details in `LddeDiagnostics`.

---

## 4. Environment Contract

LDDM injects all necessary environment variables into the LDDE child process:

| Variable | Description | Example / Typical Value |
| :--- | :--- | :--- |
| `XDG_RUNTIME_DIR` | Session-specific runtime directory | `/tmp/lddm/session-xxxx/runtime` |
| `WAYLAND_DISPLAY` | Active Wayland socket name | `wayland-0` |
| `DISPLAY` | X11 display (if XWayland active) | `:0` |
| `LDDM_SESSION_ID` | Unique LDDM session identifier | `session-a1b2c3d4` |
| `LDDM_SESSION_TYPE`| Type of session | `wayland` |
| `XDG_CURRENT_DESKTOP` | Desktop name | `LDDE` |
| `XDG_SESSION_DESKTOP` | Desktop session name | `ldde` |
| `XDG_SESSION_TYPE` | Session protocol | `wayland` |
| `LDDE_READINESS_FILE` | Path to readiness signal file | `$XDG_RUNTIME_DIR/ldde-ready` |
| `LDDE_READINESS_SOCKET` | Path to readiness socket (if socket mode) | `$XDG_RUNTIME_DIR/ldde-ready.sock` |

LDDM also inherits and permits overriding standard environment variables (`PATH`, `HOME`, `USER`, `LANG`, etc.).

---

## 5. Readiness Detection Protocol

LDDM provides two readiness detection modes via `LddeReadinessMode`:

### 5.1 Protocol File Mode (`LddeReadinessMode::ProtocolFile` - Default)
The protocol file mode is lightweight and requires no IPC libraries or daemons.

1. LDDM sets `LDDE_READINESS_FILE=<runtime_dir>/ldde-ready`.
2. LDDE initializes its display, shell, and core widgets.
3. Once initialized, LDDE atomically creates the file at `$LDDE_READINESS_FILE` with the following content:
   ```text
   STATUS=READY
   VERSION=1
   PID=<pid>
   ```
4. `LddeReadinessDetector` polls the file at configurable intervals (`poll_interval_ms`, default 20ms) until `readiness_timeout_ms` (default 10000ms).
5. At each poll step, the detector checks `/proc/<pid>/stat` to verify that the LDDE process is still alive and not a zombie. If the process terminates or crashes, readiness polling aborts immediately.

### 5.2 UNIX Domain Socket Mode (`LddeReadinessMode::Socket`)
1. LDDM sets `LDDE_READINESS_SOCKET=<runtime_dir>/ldde-ready.sock`.
2. LDDE creates a UNIX domain stream socket server at `$LDDE_READINESS_SOCKET`.
3. `LddeReadinessDetector` connects via POSIX `connect()` and validates connection and message receipt.

---

## 6. Startup and Shutdown Sequencing

### 6.1 Startup Ordering
```text
1. Session::initialize()
   └── Verifies session directories (0700) and initializes ProcessSupervisor.

2. Session::start()
   ├── Step 1: Start Compositor (WestonManager::start())
   │     ├── Prepares weston.ini and runtime environment
   │     ├── Spawns Weston via ProcessSupervisor
   │     └── Polls wayland-0 socket until READY
   │
   ├── Step 2: Start Desktop Environment (LddeManager::start())
   │     ├── Resolves LDDE executable
   │     ├── Prepares LDDE environment (WAYLAND_DISPLAY, LDDE_READINESS_FILE)
   │     ├── Spawns LDDE via ProcessSupervisor
   │     └── Polls readiness protocol until READY
   │
   └── Session state -> RUNNING
```

### 6.2 Rollback on Startup Failure
If LDDE fails to spawn or fails readiness:
1. `LddeManager::start()` returns an error result.
2. `Session::start()` catches the failure and triggers automatic rollback:
   - `weston->stop()` is called immediately.
   - Weston terminates cleanly.
   - Session transitions to `SessionState::Failed`.
3. No orphan compositor is left running without a desktop environment.

### 6.3 Shutdown Ordering
Reverse shutdown order is strictly enforced:
```text
1. Session::stop()
   ├── Step 1: Stop Desktop Environment (LddeManager::stop())
   │     ├── Sends SIGTERM to LDDE process group
   │     ├── Waits up to shutdown_timeout_ms (5000ms)
   │     └── Sends SIGKILL if still alive
   │
   ├── Step 2: Stop Compositor (WestonManager::stop())
   │     ├── Sends SIGTERM to Weston
   │     ├── Waits up to shutdown_timeout_ms (5000ms)
   │     └── Sends SIGKILL if still alive
   │
   └── Step 3: Stop Process Supervisor
         └── Cleans up any remaining child processes
```

---

## 7. Diagnostics and Telemetry

`LddeDiagnostics` maintains an audit trail and performance metrics:
- **PIDs**: Process ID and process group ID.
- **Timestamps**:
  - Spawn start time.
  - Ready time and startup duration (`ready_time - spawn_time`).
  - Stop request time.
  - Stopped time and shutdown duration (`stopped_time - stop_request_time`).
- **Exit Information**: Exit code, terminating signal, and graceful exit flag.
- **State Audit Trail**: Complete timestamped transition history through `LddeState`:
  `Created -> Preparing -> Starting -> WaitingReady -> Running -> Stopping -> Stopped`.

Diagnostics can be retrieved at any time via `ldde_manager->diagnostics()` or through `session->diagnostics()`.

---

## 8. Developing and Testing an LDDE Session Runner

A minimal reference script implementing the LDDM readiness protocol for integration testing:

```bash
#!/bin/bash
set -e

echo "[LDDE] Starting LDDE session runner..."
echo "[LDDE] WAYLAND_DISPLAY=$WAYLAND_DISPLAY"
echo "[LDDE] XDG_RUNTIME_DIR=$XDG_RUNTIME_DIR"
echo "[LDDE] LDDE_READINESS_FILE=$LDDE_READINESS_FILE"

# Trap termination signals for clean exit
trap 'echo "[LDDE] Received SIGTERM, exiting..."; exit 0' SIGTERM INT

# Signal readiness to LDDM
if [ -n "$LDDE_READINESS_FILE" ]; then
    cat <<EOF > "$LDDE_READINESS_FILE"
STATUS=READY
VERSION=1
PID=$$
EOF
    echo "[LDDE] Readiness file created."
fi

# Main event loop (replace with real desktop shell exec)
while true; do
    sleep 1
done
```
