#pragma once

#include "lddm/core/result.hpp"
#include <string_view>
#include <string>
#include <functional>
#include <mutex>
#include <vector>

namespace lddm {

enum class LifecycleState : std::uint32_t {
    CREATED      = 0,
    INITIALIZING = 1,
    READY        = 2,
    STARTING     = 3,
    RUNNING      = 4,
    STOPPING     = 5,
    STOPPED      = 6,
    FAILED       = 7
};

[[nodiscard]] std::string_view to_string(LifecycleState state) noexcept;
std::ostream& operator<<(std::ostream& os, LifecycleState state);

struct StateTransitionEvent {
    LifecycleState from{LifecycleState::CREATED};
    LifecycleState to{LifecycleState::CREATED};
    std::string reason{};
};

using StateChangeCallback = std::function<void(const StateTransitionEvent&)>;

class LifecycleStateMachine {
public:
    explicit LifecycleStateMachine(LifecycleState initial_state = LifecycleState::CREATED);
    ~LifecycleStateMachine() = default;

    LifecycleStateMachine(const LifecycleStateMachine&) = delete;
    LifecycleStateMachine& operator=(const LifecycleStateMachine&) = delete;
    LifecycleStateMachine(LifecycleStateMachine&&) = delete;
    LifecycleStateMachine& operator=(LifecycleStateMachine&&) = delete;

    [[nodiscard]] LifecycleState state() const noexcept;
    [[nodiscard]] bool is_terminal() const noexcept;
    [[nodiscard]] bool is_running() const noexcept;

    [[nodiscard]] bool can_transition_to(LifecycleState target) const noexcept;
    [[nodiscard]] static bool is_valid_transition(LifecycleState from, LifecycleState to) noexcept;

    Result<void> transition_to(LifecycleState target, std::string reason = "");

    void register_observer(StateChangeCallback callback);
    void clear_observers();

private:
    mutable std::mutex mutex_;
    LifecycleState current_state_{LifecycleState::CREATED};
    std::vector<StateChangeCallback> observers_;
};

} // namespace lddm
