#pragma once

#include "lddm/core/result.hpp"
#include "lddm/platform/unique_fd.hpp"
#include <atomic>
#include <csignal>

namespace lddm {

enum class SignalNotification : int {
    None    = 0,
    Interrupt = SIGINT,
    Terminate = SIGTERM,
    Hangup    = SIGHUP,
    Child     = SIGCHLD
};

class SignalHandler {
public:
    static Result<void> initialize();
    static void restore_defaults();

    [[nodiscard]] static bool is_termination_requested() noexcept;
    [[nodiscard]] static int last_signal_received() noexcept;
    static void reset_termination_flag() noexcept;

    // Self-pipe read FD for event loop integration
    [[nodiscard]] static int event_fd() noexcept;
    static SignalNotification read_pending_signal() noexcept;

private:
    static void handle_signal(int sig);

    static std::atomic<bool> termination_requested_;
    static std::atomic<int> last_signal_;
    static UniqueFd signal_pipe_read_;
    static UniqueFd signal_pipe_write_;
};

} // namespace lddm

