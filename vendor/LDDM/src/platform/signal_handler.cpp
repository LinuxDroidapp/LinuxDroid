#include "lddm/platform/signal_handler.hpp"
#include <unistd.h>
#include <cstring>
#include <cerrno>

namespace lddm {

std::atomic<bool> SignalHandler::termination_requested_{false};
std::atomic<int> SignalHandler::last_signal_{0};
UniqueFd SignalHandler::signal_pipe_read_{};
UniqueFd SignalHandler::signal_pipe_write_{};

void SignalHandler::handle_signal(int sig) {
    last_signal_.store(sig, std::memory_order_relaxed);
    if (sig == SIGINT || sig == SIGTERM) {
        termination_requested_.store(true, std::memory_order_relaxed);
    }

    if (signal_pipe_write_.is_valid()) {
        const uint8_t byte = static_cast<uint8_t>(sig);
        // Write in signal handler must be async-signal-safe
        const ssize_t written = ::write(signal_pipe_write_.get(), &byte, sizeof(byte));
        static_cast<void>(written);
    }
}

Result<void> SignalHandler::initialize() {
    auto pipe_res = UniqueFd::create_pipe(true, true);
    if (!pipe_res.has_value()) {
        return Result<void>::failure(pipe_res.error());
    }

    signal_pipe_read_ = std::move(pipe_res.value().first);
    signal_pipe_write_ = std::move(pipe_res.value().second);

    struct sigaction sa{};
    sa.sa_handler = handle_signal;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_RESTART;

    const int monitored_signals[] = {SIGINT, SIGTERM, SIGHUP, SIGCHLD};
    for (int sig : monitored_signals) {
        if (::sigaction(sig, &sa, nullptr) != 0) {
            return Result<void>::failure(Error(
                ErrorCategory::Platform,
                ErrorCode::PlatformSignalError,
                "sigaction failed for signal " + std::to_string(sig) + ": " + std::strerror(errno)));
        }
    }

    return Result<void>::success();
}

void SignalHandler::restore_defaults() {
    struct sigaction sa{};
    sa.sa_handler = SIG_DFL;
    sigemptyset(&sa.sa_mask);

    const int monitored_signals[] = {SIGINT, SIGTERM, SIGHUP, SIGCHLD};
    for (int sig : monitored_signals) {
        ::sigaction(sig, &sa, nullptr);
    }

    signal_pipe_read_.reset();
    signal_pipe_write_.reset();
    termination_requested_.store(false, std::memory_order_relaxed);
    last_signal_.store(0, std::memory_order_relaxed);
}

bool SignalHandler::is_termination_requested() noexcept {
    return termination_requested_.load(std::memory_order_relaxed);
}

int SignalHandler::last_signal_received() noexcept {
    return last_signal_.load(std::memory_order_relaxed);
}

void SignalHandler::reset_termination_flag() noexcept {
    termination_requested_.store(false, std::memory_order_relaxed);
}

int SignalHandler::event_fd() noexcept {
    return signal_pipe_read_.get();
}

SignalNotification SignalHandler::read_pending_signal() noexcept {
    if (!signal_pipe_read_.is_valid()) {
        return SignalNotification::None;
    }

    uint8_t byte = 0;
    ssize_t bytes_read = ::read(signal_pipe_read_.get(), &byte, sizeof(byte));
    if (bytes_read == sizeof(byte)) {
        return static_cast<SignalNotification>(byte);
    }
    return SignalNotification::None;
}

} // namespace lddm
