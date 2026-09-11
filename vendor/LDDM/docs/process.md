# LDDM Process Supervisor Architecture (Phase L2)

## Overview

The Process Supervisor is the Linux-native process management and supervision subsystem of **LDDM (LinuxDroid Display Manager)**. It provides deterministic lifecycle control, stream redirection, process group isolation, exit monitoring, and non-blocking reaping for all child processes spawned by an LDDM session.

In accordance with Phase L2 design specifications, this subsystem establishes the foundation required for Phase L3 (where LDDM supervises Weston) and Phase L4 (where LDDM supervises LDDE and session services).

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
 │         ├── SessionIdentity
 │         ├── SessionStateMachine
 │         ├── SessionPaths (XDG_RUNTIME_DIR, Sockets, State, Logs)
 │         ├── SessionEnvironment
 │         ├── SessionResourceTracker
 │         ├── SessionDiagnostics
 │         └── ProcessSupervisor (Phase L2)
 │              ├── ProcessRegistry (PIDs, handles, names)
 │              ├── ProcessEventDispatcher (Started, Exited, Failed, Signaled)
 │              └── Supervised Processes
 │                   ├── Process (Weston Compositor - Phase L3)
 │                   └── Process (LDDE / Desktop - Phase L4)
```

The `ProcessSupervisor` is instantiated and owned by the `Session`. When a session initializes, the supervisor is prepared; when the session stops or fails, all child processes registered in the supervisor are gracefully stopped (`SIGTERM`) and escalated to `SIGKILL` if they fail to terminate within their grace period.

---

## 2. Process Lifecycle State Machine

Each supervised process follows an explicit, guarded finite state machine:

```text
       ┌─────────┐
       │ CREATED │
       └────┬────┘
            │ spawn()
            ▼
      ┌──────────┐
      │ STARTING ├─────────────────────────┐
      └─────┬────┘                         │
            │ execve() succeeds            │ execve() fails
            ▼                              ▼
       ┌─────────┐                    ┌────────┐
       │ RUNNING ├────────┐           │ FAILED │
       └────┬────┘        │           └────────┘
            │             │
   stop()   │             │ process exits voluntarily
   (SIGTERM)│             │ or killed externally
            ▼             ▼
      ┌──────────┐   ┌────────┐
      │ STOPPING ├──►│ EXITED │
      └──────────┘   └────────┘
```

### State Definitions

| State | Description |
| :--- | :--- |
| `CREATED` | Process specification is defined and configured; no OS process exists yet. |
| `STARTING` | `fork()` has been called; child is configuring file descriptors, signals, and process group prior to `execve()`. |
| `RUNNING` | `execve()` succeeded; child is executing normally in its own process group. |
| `STOPPING` | Termination signal (`SIGTERM`) has been delivered; waiting for exit or timeout escalation. |
| `EXITED` | Child process terminated; exit status (`exit_code` or `term_signal`) reaped and recorded. |
| `FAILED` | Process failed to launch (`execve` failed, binary not found, permission denied, fork error). |

---

## 3. Fork / Exec Architecture & Error Pipe

To guarantee zero race conditions and deterministic failure detection when spawning processes, LDDM uses an anonymous unidirectional UNIX pipe with the `O_CLOEXEC` flag:

```text
Parent Process (LDDM)                   Child Process (Forked)
 ├── pipe2(err_pipe, O_CLOEXEC)                │
 ├── fork() ─────────────────────────────────► │
 │                                             ├── setpgid(0, 0)
 │                                             ├── reset signal masks
 │                                             ├── configure streams (dup2)
 │                                             ├── set environment & cwd
 │                                             ├── execve(path, argv, envp)
 │                                             │   │
 │                                             │   ├── (On failure):
 │                                             │   ├── write(err_pipe[1], &errno, 4)
 │                                             │   └── _exit(127)
 ├── close(err_pipe[1])                        │
 ├── read(err_pipe[0], &err, 4)                │
 │    ├── EOF (0 bytes read):                  │
 │    │    execve succeeded!                   │
 │    │    Pipe closed automatically via       │
 │    │    O_CLOEXEC. Transition to RUNNING.   │
 │    └── 4 bytes read:                        ▼
 │         execve failed with errno.           [Replaced by binary]
 │         Reap child and transition to FAILED.
```

### Key Safety Invariants
1. **No Zombies on Spawn Failure**: If `execve()` fails (e.g. `ENOENT` or `EACCES`), the child writes the POSIX `errno` to the error pipe and calls `_exit(127)`. The parent immediately calls `waitpid(pid, ...)` to reap the failed child and reports the exact failure code.
2. **Deterministic Startup**: Parent `spawn()` blocks only for the duration of the pipe read, guaranteeing that when `spawn()` returns, the process is either actively executing the target binary or has been completely reaped and marked `FAILED`.

---

## 4. Stream Redirection Model

Each child process can have its standard streams (`stdin`, `stdout`, `stderr`) independently configured via `StreamPolicy`:

| Policy | Behavior | Implementation |
| :--- | :--- | :--- |
| `Inherit` | Inherits parent's file descriptor. | No-op (FD remains open). |
| `Null` | Redirects to `/dev/null`. | Opens `/dev/null` with `O_RDONLY` (stdin) or `O_WRONLY` (stdout/stderr) and `dup2`s to stream FD. |
| `Close` | Closes the stream descriptor. | Explicit `close(fd)`. |
| `File` | Redirects to a specified file path. | Opens file with `O_CREAT | O_WRONLY | (truncate ? O_TRUNC : O_APPEND)` with permissions `0644`. |
| `Pipe` | Creates an inter-process pipe. | `pipe2()` connected to parent for structured streaming. |

---

## 5. Process Group Isolation & Signal Handling

To prevent rogue child processes or background jobs from escaping termination, every supervised child calls:

```c
setpgid(0, 0);
```

immediately after `fork()`. This sets the process group ID (`PGID`) equal to the child's `PID`.

### Signal Delivery
When sending signals:
- Process groups are signaled via `kill(-pgid, signal)`. This ensures that all sub-children, helper processes, or child forks belonging to that component receive the signal.
- In the child process prior to `execve()`, all signal handlers are reset to default (`SIG_DFL`) and the signal mask is cleared via `pthread_sigmask(SIG_SETMASK, &empty_mask, nullptr)`.

---

## 6. Graceful Termination vs SIGKILL Escalation

Process termination follows a two-tier graceful degradation protocol:

```text
supervisor.stop_process(handle, timeout = 3000ms)
        │
        ├── 1. Send SIGTERM to process group (-pgid)
        │      Transition state: RUNNING -> STOPPING
        │
        ├── 2. Wait up to timeout_ms with poll / waitpid(WNOHANG)
        │      │
        │      ├── Child exits within timeout:
        │      │   Record exit status.
        │      │   Transition state: STOPPING -> EXITED.
        │      │
        │      └── Timeout expires (process still alive):
        │          Send SIGKILL to process group (-pgid).
        │          Blocking waitpid() to guarantee reaping.
        │          Record exit status (killed by SIGKILL).
        │          Transition state: STOPPING -> EXITED.
```

This guarantees that:
1. Well-behaved processes have an opportunity to perform clean exit procedures, flush buffers, and release resources.
2. Unresponsive or hung processes are forcefully killed after the grace period expires.
3. Every terminated process is unconditionally reaped; no defunct/zombie processes are left in the process table.

---

## 7. Zombie Avoidance & Non-Blocking Reaping

The `ProcessSupervisor` provides `reap_exited_processes()`, which runs non-blocking `waitpid(-1, &status, WNOHANG)`:

```cpp
auto reaped = supervisor.reap_exited_processes();
for (const auto& exit_info : reaped) {
    LOG_INFO("Child PID {} exited with code {}", exit_info.pid, exit_info.exit_code);
}
```

When an exited process is reaped:
- Its state machine transitions to `EXITED`.
- Its `ProcessExitInfo` (containing exit code, terminating signal, and exit timestamp) is populated.
- A `ProcessEventType::Exited` event is dispatched to all registered `ProcessEventListener` callbacks.
- The process remains registered in `ProcessRegistry` so callers can query its historical exit details until explicitly unregistered.

---

## 8. Session Integration

The `Session` class tightly integrates the `ProcessSupervisor`:

1. **Initialization (`session.initialize()`)**:
   - Initializes the supervisor instance.
   - Registers a session-level process event listener to audit process lifecycle changes into `SessionDiagnostics`.
2. **Environment Propagation (`session.start()`)**:
   - The supervisor's base environment is automatically populated with the layered `SessionEnvironment` (`XDG_RUNTIME_DIR`, `WAYLAND_DISPLAY`, `PATH`, etc.).
3. **Graceful Teardown (`session.stop()`, `fail()`, `cleanup()`)**:
   - Calls `supervisor.stop_all(grace_period)`.
   - Reaps all outstanding child processes.
   - Cleans up all tracked process resources.

---

## 9. Usage Examples

### Example 1: Launching a Simple Supervised Process

```cpp
#include <lddm/process/process_supervisor.hpp>
#include <lddm/process/process_spec.hpp>

lddm::process::ProcessSupervisor supervisor;

lddm::process::ProcessSpec spec;
spec.name = "test-worker";
spec.executable = "/usr/bin/echo";
spec.arguments = {"echo", "Hello from LDDM Process Supervisor"};
spec.stdout_policy = lddm::process::StreamPolicy::File;
spec.stdout_file = "/tmp/worker_output.log";

auto result = supervisor.create_and_start_process(spec);
if (result.is_ok()) {
    auto handle = result.value();
    auto proc = supervisor.get_process(handle);
    proc->wait(); // Wait for exit
}
```

### Example 2: Graceful Termination with Timeout Escalation

```cpp
lddm::process::ProcessSpec spec;
spec.name = "long-running-service";
spec.executable = "/usr/bin/sleep";
spec.arguments = {"sleep", "60"};

auto result = supervisor.create_and_start_process(spec);
auto handle = result.value();

// Request graceful shutdown with 1000ms timeout before SIGKILL
supervisor.stop_process(handle, std::chrono::milliseconds(1000));
```

### Example 3: Listening to Process Lifecycle Events

```cpp
supervisor.register_event_listener([](const lddm::process::ProcessEvent& event) {
    if (event.type == lddm::process::ProcessEventType::Exited) {
        std::cout << "Process " << event.name << " (PID " << event.pid 
                  << ") exited with code " << event.exit_info.exit_code << std::endl;
    }
});
```
