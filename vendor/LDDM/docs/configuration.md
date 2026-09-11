# LDDM Configuration

## Overview

LDDM uses a standard Linux INI configuration format with typed sections. Configuration is centrally loaded, parsed, validated, and exposed to all subsystems through `lddm::ConfigManager`.

## Search Path & Precedence

Configuration files are resolved in the following priority order:
1. Explicit CLI argument (`--config <path>`)
2. User configuration: `$XDG_CONFIG_HOME/lddm/lddm.conf` or `~/.config/lddm/lddm.conf`
3. System configuration: `/etc/lddm/lddm.conf`
4. Built-in defaults

## Configuration Schema

```ini
[logging]
level = INFO                      # TRACE, DEBUG, INFO, WARN, ERROR, FATAL, OFF
file_path = /var/log/lddm/lddm.log # Path to log file, empty for stdout/stderr
console = true                    # Output to console
colorize = true                   # ANSI color formatting in console

[server]
socket_path = /run/lddm/lddm.sock # Control socket
runtime_dir = /run/lddm           # Runtime transient directory
pid_file = /run/lddm/lddm.pid     # Process ID file

[session]
default_user = root               # Primary user session identity
session_type = wayland            # wayland, x11, or headless
display_number = 0                # X11 or Wayland display index
wayland_display = wayland-0       # Wayland display socket name

[weston]
executable = /usr/bin/weston      # Path to Weston compositor binary
config_path = /etc/xdg/weston/weston.ini # Weston configuration file
socket_name = wayland-0           # Socket served by Weston
backend = headless-backend.so     # Compositor backend module
additional_args = --idle-time=0   # Extra CLI options passed to Weston

[ldde]
executable = /usr/bin/ldde-session # LDDE desktop session entry point
session_target = default          # Session target profile
autostart = true                  # Launch LDDE automatically after compositor

[process]
startup_timeout_ms = 10000        # Max time allowed for processes to start up
stop_timeout_ms = 5000            # Graceful termination window before SIGKILL
max_restart_count = 3             # Max auto-restart attempts within window
restart_window_seconds = 60       # Window in seconds for restart limits

[environment]
XDG_SESSION_DESKTOP = LDDE
QT_QPA_PLATFORM = wayland
GDK_BACKEND = wayland
```

## Validation Rules

- `server.socket_path` and `server.runtime_dir` must not be empty.
- `session.default_user` and `session.session_type` must not be empty.
- `weston.executable` and `weston.socket_name` must not be empty.
- If `ldde.autostart` is true, `ldde.executable` must not be empty.
- `process.startup_timeout_ms` and `process.stop_timeout_ms` must be greater than 0.

CLI verification:
```bash
lddm --validate-config /path/to/lddm.conf
```

