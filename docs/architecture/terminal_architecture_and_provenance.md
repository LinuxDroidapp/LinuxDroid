# LinuxDroid Original Terminal Engine: Architecture & Provenance

## 1. Executive Summary & Core Objective

LinuxDroid provides an original, first-party terminal implementation built from first principles in Kotlin and Jetpack Compose. Modeled after **macOS Terminal**'s interaction and usability conventions, it adapts desktop terminal ergonomics to mobile touchscreens while maintaining complete independence from third-party terminal emulator packages.

The implementation directly connects with LinuxDroid's real PTY process and Linux userspace runtime (`aarch64` PRoot environment), supporting interactive shells (`bash`, `zsh`), full-screen curses/TUI applications (`vim`, `nano`, `htop`, `less`, `tmux`), and terminal-driven developer tools like the **Antigravity CLI** and **GitHub Codespaces**.

---

## 2. Strict Third-Party Independence & Provenance Report

### Verification of Independence
A strict audit of `gradle/libs.versions.toml`, `build.gradle.kts`, and all module dependency configurations verifies:
- **Zero ConnectBot code or dependencies**.
- **Zero Termux app or terminal-emulator libraries**.
- **Zero JSch or third-party SSH/terminal packages**.
- **Zero embedded or vendored terminal engines**.

### Technology Provenance
The entire terminal stack consists exclusively of:
1. **Kotlin Standard Library & Coroutines**: Stateful streaming UTF-8 decoding, reactive flows (`StateFlow`, `SharedFlow`), thread synchronization (`@Synchronized`, `Mutex`).
2. **Android Jetpack & Compose**: Hardware-accelerated text rendering (`Text`, `AnnotatedString`, `SpanStyle`), virtualized viewport (`LazyColumn`), tactile haptic feedback (`LocalHapticFeedback`), and clipboard integration (`ClipboardManager`).
3. **LinuxDroid Native PTY Bridge**: Standard POSIX pseudoterminal interface (`openpty`, `ioctl(TIOCSCTTY)`, `fork`, `execve`).

---

## 3. Modular Subsystem Architecture

The terminal engine is organized into modular, testable components under `core/core-runtime/src/main/kotlin/com/linuxdroid/core/runtime/terminal/`:

```
com.linuxdroid.core.runtime.terminal/
├── TerminalModel.kt             // Character cell, styled spans, line data, cursor state
├── TerminalGrid.kt              // 2D matrix, alternate screen, scroll margins, editing
├── TerminalScrollback.kt        // Ring-buffer history with stable viewport navigation
├── TerminalParser.kt            // Stateful streaming ECMA-48 / VT escape sequence parser
├── TerminalSelection.kt         // Touch selection, word boundaries, soft-wrap reassembly
├── TerminalInputController.kt   // VT keycode encoder, Application Cursor Keys, Bracketed Paste
└── TerminalTheme.kt             // macOS presets (Pro, Basic, Homebrew), TrueColor, contrast
```

### 3.1 Terminal Rendering & Cell Management (`TerminalGrid.kt`, `TerminalModel.kt`)
- **2D Character Matrix**: High-performance cell matrix representing active rows and columns.
- **Dual Screen Buffers**:
  - *Primary Screen Buffer*: Standard shell history and prompt rendering.
  - *Alternate Screen Buffer* (`\u001B[?1049h` / `\u001B[?1049l`): Dedicated full-screen matrix for curses/TUI tools (`vim`, `htop`, `nano`), ensuring TUI screens never pollute shell scrollback.
- **Scrolling Margins (DECSTBM `\u001B[<top>;<bottom>r`)**: Enables full-screen applications to define fixed status headers and footers while scrolling middle regions.
- **Editing Operations**: Insertion and deletion of lines (`IL`, `DL`) and characters (`ICH`, `DCH`, `ECH`), erase in display (`ED` modes 0, 1, 2, 3), and erase in line (`EL` modes 0, 1, 2).
- **Cursor Shaping**: Support for Block, Underline, and Bar cursor shapes with smooth blinking state.

### 3.2 Streaming VT Escape-Sequence Parser (`TerminalParser.kt`)
- **Stateful Partial Sequence Reassembly**:
  - Unlike naive terminal buffers that corrupt or discard escape sequences split across PTY read chunk boundaries (e.g. `\u001B[31` in chunk 1 and `mText\u001B[0m` in chunk 2), `TerminalParser` maintains an internal `pendingEscapeBuffer`.
  - Incomplete escape sequences at chunk boundaries are held until subsequent bytes arrive and are stitched seamlessly.
- **Color Model Support**:
  - Standard 8/16 ANSI colors (`\u001B[30..37m`, `\u001B[90..97m`).
  - Extended 256-color palette (`\u001B[38;5;<n>m`, `\u001B[48;5;<n>m`).
  - 24-bit TrueColor (`\u001B[38;2;<r>;<g>;<b>m`, `\u001B[48;2;<r>;<g>;<b>m`).
- **Text Attributes**: Bold, Dim, Italic, Underline, Strikethrough, Inverse/Reverse video.
- **OSC Sequences**: OSC 0 and OSC 2 parsing for dynamic shell window titles (e.g. `user@host: ~/project`).
- **Device Status Reporting (DSR `\u001B[6n`)**: Returns 1-indexed cursor position response (`\u001B[<row>;<col>R`) back to the shell process for applications like bash readline and vim.

### 3.3 Scrollback Storage & Stable Viewport Navigation (`TerminalScrollback.kt`)
- **Ring-Buffer Architecture**: Bounded memory footprint (default 2,000 lines, configurable) preventing memory exhaustion during long-running builds or large logs.
- **macOS Terminal Viewport Stability**:
  - When the user scrolls up into history (`scrollOffset > 0`), incoming streaming output from background processes does not jerk or auto-scroll the user's viewport.
  - When at the bottom (`scrollOffset == 0`), new output automatically follows the live tail.
  - Single-tap or keyboard input snaps the viewport back to the bottom.

### 3.4 Touch Selection & Text Extraction (`TerminalSelection.kt`, `TerminalScreen.kt`)
- **Direct Touch Selection**: Precise coordinate mapping (`TerminalPosition(row, col)`) directly on the terminal grid surface without external modal dialogs.
- **Word Boundary Expansion**: Smart word selection on double-tap/long-press honoring shell and programming delimiters (`()[]{}<>"'`, space, punctuation).
- **Draggable Selection Handles**: Fine-grained drag adjustment of selection start and end positions across terminal lines.
- **Terminal-Owned Floating Action Toolbar**: Compact overlay pill providing `Copy`, `Paste`, `Select All`, and `Cancel`. Selection automatically prevents inadvertent software keyboard (IME) emergence during text selection or handle adjustment.
- **Soft-Wrap Reassembly**: Reassembles wrapped lines into continuous strings without inserting artificial newlines, preserving URLs and command strings when copied.
- **Block (Columnar) Selection**: Rectangular column region extraction for columnar data manipulation.

### 3.5 Input Encoding & PTY Forwarding (`TerminalInputController.kt`)
- **Full xterm Modifier Pipeline**:
  - Implements standard xterm modifier parameter calculation (`1 + shift(1) + alt(2) + ctrl(4)`).
  - Modified cursor arrows: `Shift+Down` (`\u001B[1;2B`), `Ctrl+Down` (`\u001B[1;5B`), `Alt+Down` (`\u001B[1;3B`), `Ctrl+Shift+Down` (`\u001B[1;6B`), etc., ensuring full compatibility with Antigravity CLI and modern TUIs.
  - In standard xterm/VT specs, modified arrows always use CSI format (`\u001B[1;<mod><A/B/C/D>`) even when Application Cursor Keys (DECCKM) is active.
- **Navigation & Function Keys**:
  - `Home`, `End`, `PageUp`, `PageDown`, `Insert`, `Delete` with full modifier encoding.
  - Tab and Backtab (`Shift+Tab` -> `\u001B[Z`).
  - Function keys F1 through F12 with modifier support.
- **Control Keys & Character Input**:
  - Full mapping for Ctrl+A through Ctrl+Z (`\x01` through `\x1A`), Ctrl+C (SIGINT), Ctrl+D (EOF), Ctrl+Z (SIGTSTP), Ctrl+L (clear), Ctrl+U (kill line), Ctrl+W (rubout word).
  - Alt/Meta prefixing (`\u001B<char>`) for terminal commands.
- **Accessory Keyboard Modifier Bar**:
  - Independently toggleable, sticky/latching `Ctrl`, `Alt`, and `Shift` buttons on the mobile accessory bar that automatically latch and reset upon key consumption.
- **Opt-in Diagnostic Input Logging**:
  - Configurable input pipeline logging (`isDiagnosticLoggingEnabled`, `diagnosticLogger`) with automatic redaction of typed character values to protect passwords, keys, and confidential shell commands.
- **Application Cursor Keys (DECCKM `\u001B[?1h` / `\u001B[?1l`)**: Dynamically toggles unmodified arrow keys between CSI (`\u001B[A..D`) and SS3 (`\u001BOA..D`) as requested by curses applications.
- **Bracketed Paste Mode (`\u001B[?2004h` / `\u001B[?2004l`)**: Automatically wraps multi-line pasted text in `\u001B[200~` and `\u001B[201~` markers so shells and editors handle pasted blocks safely without immediate execution.

### 3.6 macOS Terminal Presets & Contrast Adaptation (`TerminalTheme.kt`)
- **macOS Pro**: Dark charcoal background (`#181818`) with crisp neutral typography (`#F1F5F9`) and emerald accent cursor (`#22C55E`).
- **macOS Basic**: Light theme (`#FFFFFF`) with deep slate text (`#0F172A`) and royal blue cursor (`#2563EB`).
- **Homebrew**: High-contrast CRT black background (`#000000`) with phosphorescent green foreground (`#22C55E`).
- **Adaptive Contrast Optimization**: Intelligently enhances dark/light ANSI colors against the current theme background so dark blue or black text never becomes illegible on dark themes.

---

## 4. UI & Touchscreen Ergonomics (`TerminalScreen.kt`)

- **macOS Terminal Window Chrome**:
  - Window header bar featuring macOS traffic light window controls (Close, Minimize/Clear, Maximize/Keyboard).
  - Dynamic shell title binding updated live from OSC escape sequences (`user@host: ~/project`).
  - Terminal geometry badge (e.g. `80x24`, `120x40`).
- **Presentation Modes & Floating Windows**:
  - **Fullscreen Presentation**: Standard full-screen terminal experience for focused development.
  - **In-App Floating Window (Mode A)**:
    - macOS-styled movable window with title bar drag interaction.
    - Interactive corner resize handle in the bottom-right corner.
    - Traffic light minimize action collapses the window into a compact floating pill showing current shell status.
    - Tap to restore back to floating window size and position.
  - **System-Wide Floating Overlay (Mode B)**:
    - Background Android Service (`FloatingTerminalService`) using `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`.
    - Allows monitoring builds, servers, or long-running tasks across Android home screen and other apps.
    - Integrated permission flow verifying `Settings.canDrawOverlays` with direct intent to Android settings and graceful fallback to in-app float if denied.
  - **Session & Process Preservation**:
    - Moving between fullscreen, in-app float, or overlay never terminates or restarts the underlying PTY shell process.
- **Scrollback Stability & Resize Debouncing**:
  - Viewport anchor stability: when reading history (`!isFollowingTail`), incoming output does not disturb the scroll position.
  - Discreet "Jump to bottom" button appears when scrolled away from live output, displaying unread incoming lines count (e.g. `+12`).
  - Settling debounce (~180ms) applied to PTY column/row resizing when opening or closing software keyboards to prevent resize flooding.
- **Accessory Keyboard & Control Bar**:
  - Touch-accessible accessory bar providing ESC, TAB, CTRL, ALT, SHIFT, arrow navigation, and pipe/symbol keys independently of the soft keyboard.
  - Spotlight-style quick command chips for rapid shell operations.
  - In-terminal search bar with match navigation and hit counts.
- **Diagnostic & Export Facilities**:
  - Integrated export modal allowing full scrollback copying, failure report generation, and diagnostic archive sharing.

---

## 5. Verification & Testing

### Automated Test Coverage
The terminal implementation includes comprehensive unit tests verifying all critical paths:
1. `com.linuxdroid.core.runtime.TerminalBufferTest`:
   - Plain text streaming append.
   - ANSI color parsing and styled spans.
   - 2D absolute cursor positioning.
   - Alternate screen switching and scrollback isolation.
   - Multi-byte UTF-8 split chunk decoding (4-byte emoji boundary test).
   - 256-color and TrueColor decoding.
2. `com.linuxdroid.core.runtime.terminal.TerminalParserTest`:
   - Partial escape sequence stitching across chunk boundaries.
   - 256-color and TrueColor background/foreground parsing.
   - Cursor relative movements (CUP, CUU, CUD, CUF, CUB).
   - Alternate screen mode notifications.
   - OSC 0 and OSC 2 window title sequence decoding.
   - Device status report (DSR 6) 1-indexed response formatting.
3. `com.linuxdroid.core.runtime.terminal.TerminalSelectionTest`:
   - Delimiter-aware word boundary detection.
   - Multi-line linear text extraction.
   - Soft-wrapped line reassembly without artificial newlines.
   - Block / rectangular column selection extraction.
4. `com.linuxdroid.core.runtime.terminal.TerminalScrollbackTest`:
   - Ring-buffer line eviction at capacity.
   - Viewport scroll offset stability during active output streaming.
5. `com.linuxdroid.core.runtime.terminal.TerminalInputControllerTest`:
   - Unmodified arrow escape codes under standard and DECCKM modes.
   - Shift + Arrow Down (`\u001B[1;2B`) compatibility for Antigravity.
   - Ctrl, Alt, and combination modifier key mappings.
   - Navigation keys (Home, End, PageUp, PageDown) with modifiers.
   - Tab and Shift+Tab (Backtab `\u001B[Z`).
   - Bracketed paste wrapper formatting.
   - Opt-in diagnostic logging with sensitive character redaction.

### Build Verification
- Verified test suite execution: `BUILD SUCCESSFUL` (all 82+ tests passing, 0 failures).
- Verified cached fast-path APK build: `BUILD SUCCESSFUL in 1m 59s`.
- APK generated: `app/build/outputs/apk/debug/app-debug.apk`.
