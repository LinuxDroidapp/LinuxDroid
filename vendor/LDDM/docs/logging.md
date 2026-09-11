# LDDM Centralized Logging

## 1. Design Overview

LDDM features a structured, thread-safe, centralized logging facility designed for production diagnostics. It supports multi-sink output, ANSI colorization, and zero-allocation severity filtering.

## 2. Severity Levels

| Level | Value | Usage |
| :--- | :--- | :--- |
| `TRACE` | 0 | Highly detailed tracing of internal state changes, loops, and raw I/O. |
| `DEBUG` | 1 | Detailed diagnostic information useful during development. |
| `INFO`  | 2 | Routine operational milestones (session startup, transition events). |
| `WARN`  | 3 | Recoverable warnings and unusual conditions that did not abort operation. |
| `ERROR` | 4 | Subsystem failure, command failure, or invalid transition request. |
| `FATAL` | 5 | Unrecoverable failures leading to immediate termination. |
| `OFF`   | 6 | Disables logging entirely. |

## 3. Subsystems / Categories

Log messages are categorized into distinct subsystems:

* `LDDM`: Core daemon lifecycle, CLI, and top-level orchestrator.
* `SESSION`: Graphical session management and user environment.
* `PROCESS`: Process supervisor, signal tracking, child exit monitoring.
* `CONFIG`: Configuration loading, parsing, and validation.
* `PLATFORM`: Low-level Linux primitives (pipes, clocks, file descriptors, signals).
* `WESTON`: Wayland compositor process lifecycle and IPC.
* `LDDE`: Desktop environment session and service orchestration.

## 4. Log Format

Logs are formatted according to the following standard:

```text
[YYYY-MM-DDTHH:MM:SS.mmmZ] [tid:THREAD_ID] [LEVEL] [SUBSYSTEM] Message (file:line)
```

Example:
```text
[2026-09-05T03:15:00.123Z] [tid:1398234] [INFO ] [LDDM    ] Starting LDDM version 0.1.0-alpha (src/main.cpp:85)
[2026-09-05T03:15:00.124Z] [tid:1398234] [INFO ] [LDDM    ] Lifecycle transition: INITIALIZING -> READY (src/core/lifecycle.cpp:115)
```

## 5. Usage in Code

Convenience macros are provided in `lddm/logging/logger.hpp`:

```cpp
#include "lddm/logging/logger.hpp"

LDDM_LOG_INFO(lddm::LogSubsystem::SESSION, "Session {} started for user {}", session_id, user_name);
LDDM_LOG_ERROR(lddm::LogSubsystem::WESTON, "Compositor exited with status {}", exit_code);
```

