---
name: linuxdroid-android-engineering
description: Professional-grade engineering skill for developing, debugging, reviewing, and maintaining the LinuxDroid Android rootless Linux environment. Use this skill whenever working on LinuxDroid Android application code, native runtime code, PRoot integration, Linux userspace lifecycle, CLI/GUI boot flow, installation, persistence, diagnostics, testing, or Android compatibility. Treat the existing LinuxDroid source and verified runtime behavior as the source of truth. Do not introduce Shizuku or root-based assumptions.
---

# LinuxDroid Android Engineering Skill

## 1. Mission

Build and maintain LinuxDroid as a production-grade Android application that provides a persistent, usable, rootless Linux userspace on Android.

The engineering objective is:

**Android host application → LinuxDroid runtime/native layer → PRoot → Linux userspace**

LinuxDroid must remain usable as a CLI even when optional GUI functionality is unavailable.

The AI is an engineering agent, not an autonomous architect with permission to redesign the project arbitrarily. It must understand the existing implementation before changing it and preserve verified working infrastructure.

---

## 2. Non-Negotiable Engineering Principles

### Source of truth
1. Inspect the actual repository before proposing implementation changes.
2. Treat verified source code, build configuration, tests, and runtime diagnostics as higher authority than assumptions.
3. Do not infer that a component is broken merely because another implementation appears cleaner.
4. Do not replace working infrastructure without evidence that replacement is necessary.
5. Prefer the smallest production-quality change that solves the actual problem.

### Production quality
Every change should be:
- deterministic
- testable
- maintainable
- recoverable
- observable
- compatible with the supported Android environment
- resistant to partial failure
- free of unnecessary architectural complexity

### Preserve working functionality
Before modifying a subsystem:
- identify what currently works
- identify its public interfaces
- identify its dependencies
- identify its tests
- identify its runtime behavior
- establish a regression baseline

Working PRoot CLI infrastructure is presumed valuable and must not be casually rewritten.

### Failure isolation
Optional functionality must not destroy core functionality.

In particular:
- CLI must remain usable if GUI installation fails.
- GUI startup failure must not corrupt the Linux userspace.
- Package installation failure must be diagnosable and recoverable.
- A failed migration must not leave the application in an unknowable state.

---

## 3. Target Platform

Primary target:
- Android
- modern Android releases
- API/SDK 36-class environments
- arm64-v8a
- production consumer devices

The implementation must respect Android's application sandbox, scoped storage behavior, process model, linker behavior, permission model, and lifecycle constraints.

Never assume:
- root access
- unrestricted filesystem access
- Linux desktop semantics on Android
- system-level privileges
- kernel namespaces/features that Android does not expose
- conventional Linux boot behavior outside the PRoot guest

---

## 4. Explicitly Out of Scope

Do NOT introduce or depend on:
- Shizuku
- Magisk
- root access
- privileged Android services
- kernel modifications
- custom kernels
- system partition modifications
- Android system-image modifications
- unnecessary virtualization
- unnecessary containerization
- speculative abstractions
- duplicate runtime implementations
- duplicate PRoot implementations

If a requested feature appears to require one of these, first determine whether an unprivileged Android-compatible implementation exists. Do not silently introduce the dependency.

---

## 5. Architecture Model

Keep the architecture conceptually separated into these responsibilities:

### Android application layer
Responsible for:
- Android UI
- lifecycle
- user actions
- application state
- installation/update flows
- permissions
- persistent configuration
- displaying diagnostics
- starting/stopping LinuxDroid runtime operations

### LinuxDroid runtime layer
Responsible for:
- host preparation
- runtime environment preparation
- process launching
- PRoot invocation
- guest lifecycle
- environment variables
- mounts/bind mappings
- logging
- exit-state handling
- CLI/GUI runtime orchestration

### PRoot layer
Responsible for:
- rootless process virtualization
- filesystem path translation
- guest process execution
- Linux userspace compatibility mechanisms

If a PRoot CLI/build already works, integrate with it rather than reimplementing it.

### Linux userspace/rootfs
Responsible for:
- `/bin`, `/usr`, `/etc`, libraries, utilities
- shell
- package manager
- userland programs
- guest init
- GUI stack when installed

The application should not compensate for missing rootfs packages that are guaranteed by the custom rootfs build.

---

## 6. Rootfs Boundary

The custom LinuxDroid rootfs is a separate build concern.

When the project has a known contract stating that required packages/files exist in the custom rootfs:
- trust that contract unless runtime evidence disproves it
- do not add redundant package installation logic
- do not add defensive package discovery everywhere
- do not make application startup depend on unnecessary package checks

If a required executable/library is missing at runtime, determine whether the defect belongs to:
1. rootfs construction,
2. runtime environment,
3. PRoot invocation,
4. Android/native compatibility,
5. application orchestration.

Fix the correct layer.

---

## 7. Boot and Runtime Model

LinuxDroid should maintain a clear lifecycle.

Typical conceptual flow:

1. Android application starts.
2. LinuxDroid verifies its local runtime state.
3. Host preboot prepares the Android-side runtime.
4. Guest preboot prepares the PRoot guest environment.
5. PRoot enters the Linux userspace.
6. Guest init executes.
7. CLI session or GUI runtime is started.
8. Runtime state and exit status are recorded.
9. Shutdown/cleanup occurs deterministically.

Guest init is a Linux userspace initialization mechanism executed inside the PRoot environment. Do not describe or implement it as Android early-userspace.

A guest init path such as `/usr/sbin/linuxdroid-init` must be treated as a guest executable whose loader, permissions, architecture, libraries, and runtime environment all matter.

---

## 8. CLI-First Architecture

CLI is the core product capability.

Required behavior:
- LinuxDroid should provide a usable CLI independently of GUI installation.
- GUI is optional.
- GUI failure must not disable CLI.
- Users must be able to inspect and repair GUI installation from the CLI.
- Package installation should be available through an explicit user-controlled mechanism.
- GUI installation should be explicit and observable.

Do not make application startup permanently dependent on successful GUI initialization.

A robust conceptual flow is:

**Start CLI → verify GUI state → if GUI exists, offer/start GUI → if absent, retain CLI and provide GUI installation path.**

---

## 9. GUI Boundary

The GUI is an optional layer over the Linux userspace.

Treat these as separate failure domains:
- Linux CLI
- GUI packages
- GUI runtime
- display/session startup
- Android presentation/embedding

A GUI failure should produce actionable diagnostics without invalidating the CLI environment.

Do not hide GUI installation failures behind generic "startup failed" messages.

---

## 10. Process Execution Rules

For every externally launched process, make these properties explicit:
- executable path
- argument vector
- working directory
- environment
- stdin/stdout/stderr
- inherited file descriptors
- lifecycle ownership
- timeout policy where applicable
- exit code
- signal termination
- log location

Do not rely on implicit shell behavior when direct process execution is safer and deterministic.

When debugging:
- distinguish `execve` failure from process startup failure
- distinguish loader failure from application failure
- distinguish signal termination from normal exit
- capture the actual executable and arguments
- record exit code and signal
- inspect stderr

Exit code `255` is a symptom, not a root cause.

---

## 11. Native/JNI Engineering

When native code is involved:
- verify ABI
- verify ELF architecture
- verify dynamic dependencies
- verify loader/interpreter
- verify executable permissions
- verify filesystem path
- verify JNI signatures
- verify lifetime/ownership
- avoid unsafe pointer assumptions
- avoid undefined behavior
- preserve useful native diagnostics

For crashes such as `SIGSEGV`:
1. establish the exact crashing process
2. determine whether crash occurs before or after guest init
3. isolate host/native/PRoot/guest responsibility
4. obtain native diagnostics where possible
5. reproduce with the smallest command
6. fix the responsible layer
7. rerun regression tests

Do not "fix" a native crash by randomly changing Android versions, compiler flags, or PRoot behavior without evidence.

---

## 12. Android Compatibility Engineering

When changing compile/target/min SDK, AGP, Gradle, Kotlin, NDK, or CMake:
- inspect the existing build matrix
- verify toolchain compatibility
- build from a clean state
- run unit/instrumentation/native tests
- verify the actual device runtime
- check warnings separately from errors

A warning must not automatically be treated as the cause of a runtime failure.

For Android SDK/API changes, distinguish:
- compile-time API availability
- target SDK behavioral changes
- runtime OS behavior
- device/vendor-specific behavior

Record tested device/ABI/Android version when diagnosing device-specific failures.

---

## 13. Storage and Persistence

LinuxDroid's userspace data must persist across application restarts.

Treat the rootfs as user data, not disposable build output.

Storage operations must account for:
- incomplete extraction
- interrupted writes
- insufficient space
- permissions
- stale temporary files
- version mismatch
- corruption
- migration failure

Prefer atomic or recoverable state transitions for important metadata.

Never claim a rootfs is installed merely because an extraction command started.

---

## 14. Installation and Migration

Installation/migration must be stateful and recoverable.

Recommended states include concepts such as:
- NOT_INSTALLED
- INSTALLING
- INSTALLED
- UPDATING
- FAILED
- NEEDS_REPAIR

A failed operation must leave enough information to determine:
- what stage failed
- what was completed
- what remains
- whether retry is safe
- whether cleanup is required

Do not combine unrelated responsibilities into one opaque installation script.

Keep already-working runtime components untouched unless migration requires a verified interface change.

---

## 15. Diagnostics

Diagnostics are first-class product infrastructure.

A useful diagnostic record should identify:
- timestamp
- app/runtime version
- Android version/API
- device/ABI
- operation
- executable
- arguments where safe
- working directory
- relevant environment
- exit code
- signal
- stdout/stderr
- stage reached
- error classification

Recommended log categories:
- host-preboot
- guest-preboot
- process
- guest-init
- CLI
- GUI
- installation
- migration
- native/runtime

Avoid creating logs that duplicate the same information without a clear ownership boundary.

---

## 16. Debugging Methodology

Always use evidence-driven isolation.

### Step 1 — Reproduce
Find the smallest command/path that reproduces the failure.

### Step 2 — Classify
Determine whether the failure is:
- Android
- application
- native
- PRoot
- loader
- filesystem
- rootfs
- package
- GUI
- lifecycle

### Step 3 — Inspect
Read source and runtime logs before editing code.

### Step 4 — Establish baseline
Run the existing known-good path if available.

### Step 5 — Change one responsible layer
Avoid multi-layer speculative fixes.

### Step 6 — Test
Run targeted tests first, then relevant regression tests.

### Step 7 — Verify on device
A successful Gradle build is not proof of successful LinuxDroid runtime behavior.

### Step 8 — Record result
Document what changed and what was verified.

---

## 17. Common Failure Interpretation

### `execve` failure
Investigate:
- path
- existence
- permissions
- ELF format
- architecture
- interpreter/loader
- shared libraries
- PRoot translation
- environment

### Exit 255
Do not treat it as a diagnosis.
Inspect:
- stderr
- PRoot logs
- guest-init logs
- preceding process state
- loader failures
- signal status

### `SIGSEGV`
First determine which process actually crashed.

Possible layers:
- Android native code
- PRoot
- guest executable
- dynamic loader
- library

Never assume "LinuxDroid crashed" means the Android application itself crashed.

### Missing log
A missing log can indicate:
- code path never reached
- logger initialized too late
- wrong output path
- process exited before logger initialization
- permissions/storage issue

Do not infer success from absence of an error log.

---

## 18. Testing Strategy

Maintain multiple levels:

### Static/build validation
- Gradle build
- lint where applicable
- native compilation
- dependency resolution

### Unit tests
Test:
- state transitions
- command construction
- configuration
- path handling
- parsing
- error classification

### Integration tests
Test:
- runtime preparation
- PRoot invocation
- guest init
- CLI startup
- installation
- GUI detection

### Device tests
Test on actual supported Android hardware.

At minimum verify:
- app starts
- CLI starts
- shell works
- rootfs persists
- restart works
- process termination works
- GUI absence does not break CLI
- GUI installation failure does not break CLI
- diagnostics are produced

Never equate "build passed" with "LinuxDroid works."

---

## 19. Code Change Protocol

Before coding:
1. inspect repository structure
2. identify relevant modules
3. inspect current implementation
4. inspect tests
5. inspect build configuration
6. inspect recent diagnostics if supplied
7. identify the smallest responsible change

During coding:
- preserve existing interfaces where practical
- avoid unrelated refactors
- avoid speculative abstractions
- avoid duplicate implementations
- keep error handling explicit
- add tests for changed behavior

After coding:
1. compile
2. run targeted tests
3. run regression tests
4. inspect generated artifacts
5. verify device behavior when runtime-related
6. summarize exactly what changed

---

## 20. Decision Rules

When multiple solutions exist, prefer in this order:

1. Existing verified implementation
2. Minimal change to existing architecture
3. Android-native mechanism appropriate to the problem
4. Small isolated new component
5. Larger architectural change only with evidence

Do not select a solution because it is theoretically elegant if it makes the production system more complex without measurable benefit.

---

## 21. Anti-Overengineering Rules

Do not add:
- generic frameworks for one use case
- abstraction layers without multiple real implementations
- redundant health checks
- redundant package detection
- duplicate logging
- duplicate configuration systems
- unnecessary databases
- unnecessary services
- unnecessary IPC
- unnecessary background workers
- unnecessary runtime daemons

Every new subsystem must have a concrete responsibility and measurable justification.

---


## 22. Code Standards

LinuxDroid code must follow a **clean, minimal, modular, reusable, and maintainable** engineering standard.

### Organization
- Keep files and modules organized by responsibility.
- One component should have one clear primary responsibility.
- Keep Android UI, runtime orchestration, native code, process execution, installation, persistence, and diagnostics separated.
- Avoid putting unrelated logic into large classes or utility files.
- Prefer a small number of well-defined modules over many tiny abstractions.
- Keep public APIs narrow and intentional.
- Keep implementation details private where possible.

### Simplicity
- Prefer the simplest design that correctly solves the problem.
- Do not add abstractions merely to make code look architecturally sophisticated.
- Do not create a framework when a small module is sufficient.
- Do not generalize code until there is a real reuse requirement.
- Avoid premature optimization.
- Avoid clever code when straightforward code is easier to understand and maintain.

### Modularity
- Modules should have clear boundaries and responsibilities.
- Minimize coupling between Android, native, PRoot, guest-runtime, installation, and UI code.
- Depend on stable interfaces rather than implementation details.
- A module should be replaceable without requiring unrelated modules to change.
- Shared functionality should have a single canonical implementation.

### Reusability
- Extract genuinely reusable behavior when it is used in multiple places or has a clearly defined reusable contract.
- Prefer parameterized, deterministic functions over duplicated logic.
- Do not create generic helper layers solely for hypothetical future use.
- Avoid copy-pasting business-critical logic.

### Readability
Code should be understandable by another engineer without requiring extensive reverse engineering.

Use:
- descriptive names
- small focused functions
- explicit control flow
- clear data models
- consistent formatting
- meaningful error messages
- comments explaining **why**, not obvious **what**

Avoid:
- cryptic abbreviations
- deeply nested conditionals
- giant functions
- giant classes
- magic numbers/strings
- unnecessary one-line tricks
- hidden side effects
- implicit global state

### Error Handling
- Handle expected failures explicitly.
- Preserve the original cause of failures.
- Use structured error information where practical.
- Never swallow exceptions/errors silently.
- Do not use generic catch-all handling as a substitute for understanding failure modes.
- Error messages should identify the operation and useful context.
- Runtime errors should map cleanly to diagnostic logs and user-visible states where appropriate.

### State and Side Effects
- Keep state ownership explicit.
- Avoid mutable global state.
- Avoid hidden filesystem/process side effects.
- Make lifecycle transitions explicit.
- Do not let unrelated modules mutate each other's state directly.
- Prefer deterministic functions for path construction, configuration, parsing, validation, and state decisions.

### Constants and Configuration
- Centralize important constants.
- Do not scatter duplicated paths, command names, state values, or configuration keys throughout the codebase.
- Distinguish immutable constants from user/runtime configuration.
- Do not hard-code device-specific assumptions unless they are part of the supported contract.

### Logging
- Log meaningful state transitions and failures.
- Do not log sensitive information unnecessarily.
- Do not duplicate the same event across multiple layers.
- Use consistent log categories and operation identifiers.
- Logs should help reconstruct what happened without requiring source-code guessing.

### Comments and Documentation
Comments should explain:
- why a non-obvious decision exists
- Android/PRoot compatibility constraints
- lifecycle invariants
- workarounds for known platform behavior
- important ownership or cleanup requirements

Do not write comments that merely restate the code.

### Refactoring
Refactor when it materially improves:
- correctness
- readability
- maintainability
- testability
- modularity

Do not refactor unrelated code while implementing a feature or fixing a bug.

When refactoring working runtime infrastructure:
1. establish a baseline
2. preserve behavior
3. make the smallest safe change
4. run regression tests
5. verify runtime behavior

### Code Review Checklist
Before considering code complete, verify:

- Is the code easy to locate and understand?
- Does every module have a clear responsibility?
- Is there unnecessary duplication?
- Is there unnecessary abstraction?
- Can the implementation be simpler?
- Are names descriptive?
- Are functions and classes appropriately sized?
- Are side effects explicit?
- Are errors handled correctly?
- Are important constants centralized?
- Is the code testable?
- Does it preserve existing working behavior?
- Would another engineer understand the implementation quickly?

### Code Quality Principle

**Clean code is not code with the most abstractions. Clean code is code with the fewest necessary concepts, clear boundaries, explicit behavior, and no unnecessary complexity.**

## 22. Security Rules

Never weaken Android security merely to make a feature work.

- Do not request unnecessary permissions.
- Do not execute untrusted input as shell commands.
- Validate paths crossing Android/Linux boundaries.
- Prevent path traversal during extraction/install operations.
- Avoid unsafe temporary-file handling.
- Keep privileged assumptions out of the architecture.
- Never silently grant elevated access.

---

## 23. AI Agent Operating Procedure

When assigned a LinuxDroid task, the AI should respond internally through this sequence:

**Understand → Inspect → Baseline → Classify → Plan → Implement → Build → Test → Device Verify → Report**

The AI must not jump directly from a user symptom to a rewrite.

For source-repository tasks, inspect actual files before making claims about implementation.

For runtime failures, prioritize logs and reproducible commands over speculation.

For migration tasks, preserve verified working components and change only the necessary integration points.

For architecture decisions, minimize moving parts while preserving production reliability.

---

## 24. Definition of Done

A LinuxDroid change is complete only when applicable criteria are satisfied:

- implementation is correct
- architecture remains coherent
- existing working behavior is preserved
- relevant tests pass
- build succeeds
- runtime behavior is verified where applicable
- failure states are observable
- no unnecessary dependency was introduced
- no Shizuku/root dependency was introduced
- documentation/configuration is updated when necessary
- no known regression remains

The final report should state:
- what changed
- why it changed
- what was deliberately left untouched
- tests executed
- runtime/device verification
- remaining limitations, if any

---

## 25. Core Principle

**LinuxDroid is a rootless Linux userspace product running inside Android, not a miniature Android replacement and not a generic Linux emulator project.**

Keep the Android host, LinuxDroid runtime, PRoot, and guest userspace boundaries clear.

Preserve what works.

Fix the layer that is actually broken.

Prefer evidence over assumptions.

Prefer simple production architecture over clever architecture.

Keep CLI reliable.

Treat GUI as optional.

Do not introduce Shizuku or root-based dependencies.
