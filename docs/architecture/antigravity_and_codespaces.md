# Antigravity CLI and GitHub Codespaces Integration

## 1. Overview
LinuxDroid extends rootless Linux capability on Android by integrating two major remote and agentic workflows:
1. **Google Antigravity CLI (`agy`)**: Official autonomous coding agent CLI running directly within the local PRoot guest userspace, featuring one-tap automated launch, Google OAuth orchestration, and a built-in Remote Control WebView.
2. **GitHub Codespaces**: Remote cloud dev environments orchestrated via the GitHub REST API and connected via interactive terminal PTY or Web IDE.

---

## 2. Feature A: Official Google Antigravity CLI

### 2.1 Architecture
- **Domain Model (`:core:core-model`)**:
  - `AntigravityStatus`: State enum (`NOT_INSTALLED`, `INSTALLING`, `INSTALLED`, `CHECKING_FOR_UPDATES`, `UPDATING`, `LAUNCHING`, `RUNNING`, `FAILED`).
  - `AntigravityAuthState`: Explicit authentication state machine:
    - `NOT_STARTED`
    - `STARTING_CLI`
    - `WAITING_FOR_LOGIN_METHOD`
    - `OPENING_GOOGLE_AUTH`
    - `WAITING_FOR_AUTH_RESULT`
    - `SUBMITTING_AUTH_RESULT`
    - `AUTHENTICATING`
    - `WAITING_FOR_REMOTE_CONTROL_URL`
    - `REMOTE_CONTROL_READY`
    - `ERROR`
    - `CANCELLED`
  - `AntigravityState`: Immutable state data class containing installation status, auth state, current version, latest version, update availability, progress messages, authorization URL, active remote control URL, and saved session detection.
  - `AntigravityManifest`: Data transfer object representing the remote official manifest.

- **Manager (`:linux:bootstrap:AntigravityManager`)**:
  - **Dynamic Architecture Detection**: Maps Android ABI (`arm64-v8a` -> `linux_arm64`, `x86_64` -> `linux_amd64`).
  - **Official Manifest Resolution**: Fetches official metadata from Google's manifest endpoint (`https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/${platform}.json`).
  - **Integrity Verification**: Computes SHA-512 hashes of downloaded tar archives against manifest checksums prior to extraction.
  - **Atomic Installation & Rollback**:
    - Unpacks binaries into staging directory `<rootfs>/tmp/agy_staging`.
    - Backs up existing executable to `<rootfs>/usr/local/bin/agy.bak`.
    - Atomically moves binary to `/usr/local/bin/agy` and sets POSIX execution bits (`chmod +x` via `NativeBridge.setExecutable`).
    - Verifies execution via PRoot guest execution (`agy --version`). If execution fails, automatically restores `.bak`.
  - **Saved Session Verification**: Checks guest rootfs paths (`/root/.config/antigravity`, `/root/.antigravity`, `/root/.gemini/antigravity-cli`, `/etc/antigravity`) for existing authentication tokens without reading or exposing credentials.
  - **Non-blocking Automatic Update Checking**:
    - Compares semantic versioning without interrupting user terminal sessions.
  - **Terminal Launch Coordination**:
    - Launches `agy --remote-control --dangerously-skip-permissions` inside the existing Linux PTY terminal.

### 2.2 Terminal Stream Automation (`:linux:bootstrap:AntigravityTerminalAutomation`)
- **ANSI Escape Code Normalization**:
  - Strips terminal escape codes, cursor positioning, and formatting bytes using regex pattern `\u001B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])`.
- **Line-Wrapped URL Reconstruction**:
  - PTY lines formatted for 80 columns wrap long Google OAuth and Remote Control URLs across multiple lines.
  - The automation engine reassembles split URLs across line breaks prior to regex matching.
- **Automated Login Method Selection**:
  - Positively identifies unauthenticated CLI prompt:
    `Welcome to the Antigravity CLI. You are currently not signed in.`
    `1. Google OAuth`
    `2. Use a Google Cloud project`
  - Automatically sends `1\n` to PTY input.
  - Enforces single-shot guard flags to prevent duplicate input injection when prompts are redrawn.
  - Never blindly sends `1` to non-matching prompts; manual keyboard input remains active at all times.
- **Google OAuth Authorization URL Extraction**:
  - Extracts and validates HTTPS URLs matching `https://accounts.google.com/o/oauth2/auth?...`.
  - Rejects foreign, malformed, or non-HTTPS URLs.
- **Browser Launch & Coordination**:
  - Launches Android Chrome via `Intent.ACTION_VIEW` targeting package `com.android.chrome`.
  - Gracefully falls back to default system browser if Chrome is absent.
  - CLI process remains attached and running in interactive Linux PTY.
- **Authorization Code Transfer & Deep Linking**:
  - Supports machine-readable deep links: `antigravity://auth`, `antigravity://oauth/callback`, `linuxdroid://oauth/callback`.
  - Secure Fallback: When the browser displays an authorization code, LinuxDroid provides user-initiated actions:
    - Dedicated "Paste Code" chip (reads clipboard upon explicit user click).
    - "Enter Code" dialog allowing manual code input.
    - Submits `$code\n` to CLI stdin and updates state to `SUBMITTING_AUTH_RESULT` -> `AUTHENTICATING`.
    - Explicitly adheres to security policies: does not silently inspect clipboard or scrape browser contents.
- **Remote Control Readiness & URL Detection**:
  - Detects readiness banner:
    `Remote control on for this session.`
    `Open https://antigravity.google.com/r/<session-id>-v2 on another device to take over.`
    (or `https://antigravity.google/join/<session-id>`)
  - Validates HTTPS scheme and official hostnames (`antigravity.google`, `antigravity.google.com`).
  - Extracts exact session identifier without truncation or reconstruction.
  - Automatically triggers navigation to the built-in WebView.

### 2.3 Built-in Antigravity WebView (`AntigravityWebViewScreen`)
- **Process Preservation**:
  - The local CLI process running in PRoot continues executing uninterrupted while the user interacts with the WebView.
- **Top Bar Controls**:
  - "Back to Terminal" navigation action.
  - "REMOTE CONTROL" status chip.
  - Refresh button.
  - "Open in Chrome" fallback action.
- **Security & Sandboxing**:
  - `javaScriptEnabled = true` (required by Antigravity Web application).
  - `domStorageEnabled = true` (required for local app state).
  - `allowFileAccess = false`, `allowContentAccess = false`.
  - `allowFileAccessFromFileURLs = false`, `allowUniversalAccessFromFileURLs = false`.
  - `mixedContentMode = MIXED_CONTENT_NEVER_ALLOW`.
  - URL Whitelisting: Only `antigravity.google`, `antigravity.google.com`, and `accounts.google.com` navigate within WebView; all other origins delegate to external browser intents.
- **Resilience**:
  - Full loading indicator via `WebChromeClient.onProgressChanged`.
  - Error state handling with Retry and browser fallback.
  - Hardware back-button handling navigating web history before popping screens.

---

## 3. Feature B: GitHub Codespaces Integration

### Architecture
- **Secure KeyStore Storage (`:core:core-storage:SecureCredentialStorage`)**:
  - Encrypted storage utilizing hardware-backed Android KeyStore (`AndroidKeyStore`) with AES-256-GCM.
  - Secure fallback with obfuscated key generation for legacy/emulated environments.
  - Safely persists GitHub OAuth tokens and Personal Access Tokens (PAT).

- **Authentication Manager (`:core:core-network:GitHubAuthManager`)**:
  - **RFC 8628 Device Authorization Flow**:
    - Requests device code from `https://github.com/login/device/code` with scopes `codespace`, `repo`, `user`.
    - Provides user code, verification URI, and countdown timer.
    - Polling loop with exponential backoff and rate-limit / slow-down handling.
  - **Personal Access Token (PAT) Fallback**:
    - Validates token format and queries `https://api.github.com/user` to verify scopes and identity.
  - **User Profile Fetching**:
    - Exposes current logged-in user details (`login`, `name`, `avatarUrl`).

- **Codespaces Manager (`:core:core-network:CodespacesManager`)**:
  - Queries `https://api.github.com/user/codespaces`.
  - Supports start (`POST /user/codespaces/{name}/start`) and stop (`POST /user/codespaces/{name}/stop`) lifecycle operations.
  - Generates connection command for LinuxDroid PTY:
    - Launches `gh codespace ssh -c "<name>"` when GitHub CLI is available in guest rootfs.
    - Provides fallback instructions and direct browser Web IDE link (`webUrl`).

---

## 4. UI & Neumorphic Design System
- **Neumorphic Consistency**:
  - Implements custom soft-depth surfaces (`NeuSurface`, `NeuCard`, `NeuBadge`, `NeuButton`) adhering to LinuxDroid's visual language.
  - Responsive light and dark modes with theme-aware elevation and inset shadows.
- **Home Screen Cards**:
  - Quick action cards on the home dashboard indicating Antigravity installation/execution status and Codespaces account connectivity.
  - One-tap "Start Antigravity" / "Remote Web" / "Resume CLI" actions.
- **Terminal Banner**:
  - Contextual action bar tracking each step of Google OAuth authentication and Remote Control readiness.
