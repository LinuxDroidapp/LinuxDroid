# LDDM Session Architecture (Phase L1)

## Overview

The Session Model is the core abstraction of **LDDM (LinuxDroid Display Manager)**, defining the graphical session identity, environment, lifecycle, filesystem isolation, and supervised component tracking.

In accordance with Phase L1 design specifications, the session model establishes the foundation for compositor (Weston) and desktop environment (LDDE) lifecycle supervision without launching them directly.

---

## 1. Architectural Position

```text
LinuxDroid Runtime
        ↓
Guest Init
        ↓
LDDM (Display & Session Manager)
 ├── SessionManager
 │    └── Session ("session-xxxx")
 │         ├── SessionIdentity (Id, User, UID, GID)
 │         ├── SessionStateMachine (State, History, Observers)
 │         ├── SessionPaths (XDG_RUNTIME_DIR, Sockets, State, Logs)
 │         ├── SessionEnvironment (Deterministic Layering)
 │         ├── SessionResourceTracker (FDs, Temp files, Cleanup)
 │         └── SessionDiagnostics (Audit log, Error tracking)
 ↓
[Weston Compositor Instance] (Supervised in Phase L2)
 ↓
[LDDE Desktop Environment Instance] (Supervised in Phase L3)
```

---

## 2. Session Identity

Every graphical session is assigned an immutable `SessionIdentity`:
- `SessionId`: Unique identifier (e.g. `session-1725500000000-0001` or explicit slug `session-0`).
- `UserId` and `GroupId`: Numerical credentials mapped to the target user (e.g., `1000` / `1000` or `0` / `0`).
- `Username`: Human-readable user name (e.g., `root`, `droiduser`).
- `SessionType`: Session type (`Wayland`, `X11`, `Headless`).

---

## 3. Lifecycle State Machine

The session adheres to an explicit finite state machine with strict transition guards:

```text
  ┌─────────┐
  │ CREATED │
  └────┬────┘
       │ initialize()
       ▼
┌──────────────┐
│ INITIALIZING ├─────────────────────────┐
└──────┬───────┘                         │
       │                                 │
       ▼                                 │
   ┌───────┐                             │
   │ READY │                             │
   └───┬───┘                             │
       │ start()                         │
       ▼                                 ▼
 ┌──────────┐                      ┌──────────┐
 │ STARTING ├─────────────────────►│  FAILED  │
 └─────┬────┘                      └────▲──▲──┘
       │                                │  │
       ▼                                │  │
  ┌─────────┐                           │  │
  │ RUNNING ├───────────────────────────┘  │
  └────┬────┘                              │
       │ stop()                            │
       ▼                                   │
 ┌──────────┐                              │
 │ STOPPING ├──────────────────────────────┘
 └─────┬────┘
       │
       ▼
  ┌─────────┐
  │ STOPPED │
  └─────────┘
```

### Valid Transitions
| From | Allowed To | Trigger |
| :--- | :--- | :--- |
| `CREATED` | `INITIALIZING`, `FAILED` | `initialize()` |
| `INITIALIZING` | `READY`, `FAILED` | Resource preparation completes |
| `READY` | `STARTING`, `STOPPING`, `FAILED` | `start()` or explicit teardown |
| `STARTING` | `RUNNING`, `FAILED`, `STOPPING` | Component startup completes |
| `RUNNING` | `STOPPING`, `FAILED` | `stop()` or component failure |
| `STOPPING` | `STOPPED`, `FAILED` | Teardown sequence completes |
| `FAILED` | `STOPPED` | Cleanup after failure |
| `STOPPED` | *(terminal)* | Final state |

All transitions are observed and recorded in `SessionDiagnostics` with timestamps and transition reasons.

---

## 4. Runtime Paths & Directory Isolation

`SessionPaths` establishes isolated per-session directories with strict POSIX permissions (`0700` / `S_IRWXU`):

- **Session Root**: `/run/lddm/<session-id>`
- **Runtime Dir (`XDG_RUNTIME_DIR`)**: `/run/lddm/<session-id>/runtime`
- **State Dir (`XDG_STATE_HOME`)**: `/run/lddm/<session-id>/state`
- **Log Dir**: `/run/lddm/<session-id>/logs`
- **Tmp Dir**: `/run/lddm/<session-id>/tmp`
- **Wayland Socket**: `/run/lddm/<session-id>/runtime/wayland-0`
- **IPC Socket**: `/run/lddm/<session-id>/lddm.sock`

Directory creation is idempotent. Cleanup removes all generated paths cleanly upon session termination.

---

## 5. Environment Layering

The session environment is constructed through deterministic layering:

1. **System & Base Defaults**:
   - `HOME`, `USER`, `LOGNAME`, `SHELL`, `PATH`
2. **Session Identification & Runtime**:
   - `LDDM_SESSION_ID=<session-id>`
   - `XDG_SESSION_TYPE=wayland`
   - `XDG_SESSION_CLASS=user`
   - `XDG_RUNTIME_DIR=/run/lddm/<session-id>/runtime`
   - `WAYLAND_DISPLAY=wayland-0`
3. **Desktop & Toolkit Compatibility**:
   - `XDG_CURRENT_DESKTOP=LDDE`
   - `XDG_SESSION_DESKTOP=LDDE`
   - `GDK_BACKEND=wayland`
   - `QT_QPA_PLATFORM=wayland`
   - `CLUTTER_BACKEND=wayland`
   - `SDL_VIDEODRIVER=wayland`
4. **Explicit User Overrides**:
   - Variables supplied via configuration `environment_overrides`.

Diagnostics provide a **redacted summary** of the environment, masking sensitive tokens like `*KEY*`, `*TOKEN*`, `*SECRET*`, `*PASSWORD*`.

---

## 6. Resource Ownership & Cleanup Guarantees

The `SessionResourceTracker` guarantees zero resource leaks upon session termination:
- **File Descriptors**: Managed via `UniqueFd`, auto-closed on scope exit or explicit cleanup.
- **Temporary Files**: Tracked and unlinked during teardown.
- **Component Teardown**: Components are stopped in reverse order of startup (LIFO).
- **Cleanup Guarantee**: `cleanup()` is `noexcept` and safe to invoke multiple times.

---

## 7. Session Manager

The `SessionManager` provides centralized session registry and lifecycle orchestration:
- Thread-safe registry mapping `SessionId` to `std::shared_ptr<Session>`.
- Active session designation.
- Controlled shutdown via `stop_session()`, `remove_session()`, and `stop_all_sessions()`.
