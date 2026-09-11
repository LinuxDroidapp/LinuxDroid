# LDDM Session Recovery Architecture

## Overview

The **LDDM Session Recovery Subsystem** provides deterministic, policy-driven fault recovery for graphical desktop sessions in LinuxDroid. It supervises the full graphical lifecycle spanning the Weston Wayland compositor, the LinuxDroid Desktop Environment (LDDE), and underlying managed processes.

When components encounter unexpected exits, readiness timeouts, or initialization failures, the `RecoveryManager` intervenes using structured policies, dependency-ordered restarts, exponential backoff, and loop-prevention heuristics to restore session stability without requiring a full container restart.

---

## Architectural Position

```text
LinuxDroid Runtime
        ↓
Guest Init
        ↓
LDDM Display Manager
        ↓
Session
        │
        ├── Process Supervisor
        │
        ├── Weston Manager (Compositor)
        │
        ├── LDDE Manager (Desktop Environment)
        │
        └── Recovery Manager (Fault Recovery Coordinator)
```

The `RecoveryManager` interacts with the `Session`, `WestonManager`, and `LddeManager`:
* Monitors process exits and failures via supervisor event listeners.
* Evaluates configured recovery policies.
* Manages the 5-state recovery lifecycle (`RecoveryState`).
* Enforces Wayland dependency ordering: Weston crash triggers LDDE quiescence, Weston restart and readiness verification, followed by LDDE relaunch.
* Cleans up stale runtime artifacts (Wayland sockets, lockfiles, readiness files).
* Tracks recovery attempts and enforces backoff and exhaustion limits.

---

## Recovery State Machine

The recovery engine operates on a dedicated 5-state state machine:

```text
  ┌─────────────────────────────────────────────────────────────┐
  │                                                             │
  ▼                                                             │
[Idle] ──(failure detected)──> [Recovering] ──> [Verifying] ────┘ (success)
  ▲                                │                 │
  │                                ▼                 ▼
  │                           [Failed] <─────────────┘ (precondition/restart fail)
  │                              │
  └───────(reset / restart)──────┘
```

1. **`Idle`**: Normal operation. No active recovery operations.
2. **`Recovering`**: A failure has been classified, policy resolved, backoff applied, and component teardown/relaunch initiated.
3. **`Verifying`**: The recovered component has been spawned; waiting for health and readiness verification (e.g. Wayland socket creation or readiness file).
4. **`Recovered`**: Transient state confirming successful component readiness and restoration of the session to `RUNNING`.
5. **`Failed`**: Terminal state when recovery cannot proceed (policy `FailSession`, precondition failure, or retry budget exhausted). Transitions the parent session to `SessionState::FAILED`.

---

## Recovery Policies

LDDM supports 4 distinct recovery policies:

| Policy | Description | Use Cases |
| :--- | :--- | :--- |
| `NoRecovery` | Do not attempt recovery; preserve current state for external inspection. | Manual debugging, strict test harnesses. |
| `ComponentRestart` | Restart only the affected component while preserving parent session. | LDDE desktop crashes while Weston is healthy. |
| `SessionRestart` | Quiesce all components and re-execute full session initialization. | Compositor crashes, runtime environment corruption. |
| `FailSession` | Abort immediately and transition session to `FAILED`. | Fatal unrecoverable errors, startup exhaustion. |

### Policy Resolution Matrix

* **Compositor (Weston) Failures**:
  - `WestonUnexpectedExit`, `WestonStartFailure`, `WestonReadinessTimeout`:
  - Resolved policy: `SessionRestart` (or `ComponentRestart` with dependency ordering).
* **Desktop Environment (LDDE) Failures**:
  - `LddeUnexpectedExit`, `LddeReadinessTimeout`:
  - Resolved policy: `ComponentRestart` (if Weston is healthy), escalating to `SessionRestart` if Weston socket is invalid.
* **Session Runtime Failures**:
  - `SessionStartFailure`, `SessionRuntimeFailure`:
  - Resolved policy: `SessionRestart`.
* **Resource Failures**:
  - `RuntimeResourceFailure`:
  - Clean stale resources and attempt `SessionRestart`.

---

## Dependency-Ordered Recovery

In Wayland architectures, desktop clients cannot survive compositor crashes because the underlying Wayland display socket connection is broken.

### Weston Recovery Workflow:
1. **Quiesce Client**: Stop LDDE cleanly (SIGTERM -> SIGKILL) to prevent orphaned clients or broken pipe cascades.
2. **Quiesce Weston**: Terminate failed Weston process.
3. **Clean Runtime Resources**: Probe Wayland socket using non-blocking connect; remove stale socket (`wayland-0`) and lock files (`wayland-0.lock`).
4. **Reset State**: Reset Weston manager internal state and diagnostics.
5. **Restart Weston**: Launch Weston through the Process Supervisor and wait for non-blocking socket verification.
6. **Restart LDDE**: Reset LDDE manager, prepare environment with new compositor socket, launch LDDE process, and wait for readiness file protocol.
7. **Transition**: Restore session state to `SessionState::RUNNING`.

---

## Loop Prevention & Exponential Backoff

To prevent infinite recovery loops consuming system resources:

1. **Sliding Time Window (`window_ms`)**:
   Tracks retry attempts within a moving time window (default 60 seconds).
2. **Attempt Budget (`max_attempts`)**:
   If attempts within the sliding window exceed `max_attempts` (default 3), the manager transitions to `Failed` with `ErrorCode::RecoveryExhausted`.
3. **Exponential Backoff (`calculate_backoff_ms`)**:
   Delay formula:
   $$\text{delay} = \min(\text{initial\_backoff} \times (\text{multiplier})^{\text{attempt} - 1}, \text{max\_backoff})$$
   Default parameters:
   - Initial backoff: 500 ms
   - Multiplier: 2.0
   - Maximum backoff: 10,000 ms

---

## Non-Blocking Socket Probing

Stale display sockets can prevent Wayland compositors from starting. `RecoveryManager::is_wayland_socket_active` checks socket liveness without blocking:
* Creates socket with `SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC`.
* Issues `::connect()` to the socket path.
* `ret == 0` or `errno == EINPROGRESS | EAGAIN`: Socket is alive and actively listening.
* `errno == ECONNREFUSED` or `ENOENT`: Socket is stale or dead; safe to unlink.

---

## Diagnostics and Telemetry

The recovery subsystem provides real-time telemetry via `RecoveryDiagnostics`:
* Total recovery attempts initiated.
* Total successful recoveries.
* Total failed recoveries.
* Total session restarts vs component restarts.
* Consecutive failure counter (cleared on recovery success).
* Sliding attempt timestamps.
* Bounded history log of recent recovery records (reasons, policies, durations, errors).
