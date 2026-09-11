# LDDM Development Guidelines

## Architectural Principles

1. **RAII & Explicit Ownership**:
   - Never manage raw file descriptors, memory pointers, or child process handles manually.
   - Use `UniqueFd` for file descriptors, `std::unique_ptr` for polymorphic objects, and custom RAII guards.

2. **Error Propagation via `Result<T>`**:
   - Avoid throwing exceptions across module boundaries.
   - Functions that can fail return `Result<T>` or `Result<void>`.
   - Use `ErrorCategory` and `ErrorCode` with contextual descriptions.

3. **Deterministic Lifecycles**:
   - Subsystem state must be queryable and transition deterministically.
   - Resources must be cleaned up on both normal termination and error unwind paths.

4. **Thread Safety**:
   - Public shared facilities (e.g. `Logger`, `LifecycleStateMachine`, `Session`) must be thread-safe.
   - Synchronize with explicit mutex locks and avoid data races.

5. **Separation of Concerns**:
   - Keep platform specifics confined to `src/platform/`.
   - Never couple LDDM core to Android APIs or JNI.
   - Maintain pure Linux-native semantics.

## Building and Testing

### Build Commands

```bash
# Configure Release build
cmake -B build -DCMAKE_BUILD_TYPE=Release -DLDDM_BUILD_TESTS=ON -DLDDM_WARNINGS_AS_ERRORS=ON

# Compile
cmake --build build -j$(nproc)

# Run tests
ctest --test-dir build --output-on-failure

# Install
DESTDIR=/tmp/lddm-install cmake --install build
```

### Adding New Subsystems or Phases

- Phase L1 will expand `lddm::session` to support multi-session management.
- Phase L2 will introduce the process supervisor in `lddm::process`.
- Phase L3 will implement `ICompositorInstance` for Weston.
- Phase L4 will implement `IDesktopEnvironmentInstance` for LDDE.

