#include "lddm/weston/weston_readiness.hpp"
#include "lddm/core/error.hpp"
#include "lddm/platform/clock.hpp"

#include <filesystem>
#include <thread>
#include <cstring>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <signal.h>
#include <cerrno>

namespace lddm::weston {

std::string_view to_string(WaylandSocketStatus status) noexcept {
    switch (status) {
        case WaylandSocketStatus::SocketNotCreated:       return "SocketNotCreated";
        case WaylandSocketStatus::SocketCreatedUnusable:   return "SocketCreatedUnusable";
        case WaylandSocketStatus::WaylandConnectionUsable: return "WaylandConnectionUsable";
        case WaylandSocketStatus::WestonExited:           return "WestonExited";
    }
    return "Unknown";
}

static bool is_process_dead_or_zombie(pid_t pid) {
    if (pid <= 0) {
        return false;
    }
    if (kill(pid, 0) != 0) {
        if (errno == ESRCH) {
            return true;
        }
        return false;
    }

    // Process exists in the process table. Check if it is a zombie.
    char stat_path[64];
    std::snprintf(stat_path, sizeof(stat_path), "/proc/%d/stat", static_cast<int>(pid));
    FILE* f = std::fopen(stat_path, "r");
    if (!f) {
        if (errno == ENOENT) {
            return true;
        }
        return false;
    }
    char buf[256];
    if (std::fgets(buf, sizeof(buf), f)) {
        std::fclose(f);
        const char* last_paren = std::strrchr(buf, ')');
        if (last_paren && *(last_paren + 1) == ' ') {
            char state = *(last_paren + 2);
            if (state == 'Z' || state == 'X') {
                return true;
            }
        }
    } else {
        std::fclose(f);
    }
    return false;
}

WaylandSocketStatus WestonReadinessDetector::check_socket(
    const std::string& socket_path,
    pid_t pid) {
    // 1. Process liveness check
    if (pid > 0 && is_process_dead_or_zombie(pid)) {
        return WaylandSocketStatus::WestonExited;
    }

    // 2. Filesystem check
    std::error_code ec;
    if (!std::filesystem::exists(socket_path, ec)) {
        return WaylandSocketStatus::SocketNotCreated;
    }

    if (!std::filesystem::is_socket(socket_path, ec)) {
        return WaylandSocketStatus::SocketCreatedUnusable;
    }

    // 3. Socket connection usability check
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) {
        return WaylandSocketStatus::SocketCreatedUnusable;
    }

    struct sockaddr_un addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, socket_path.c_str(), sizeof(addr.sun_path) - 1);

    int ret = connect(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr));
    int err = errno;
    close(fd);

    if (ret == 0 || err == EINPROGRESS) {
        return WaylandSocketStatus::WaylandConnectionUsable;
    }

    if (err == ECONNREFUSED || err == EAGAIN) {
        return WaylandSocketStatus::SocketCreatedUnusable;
    }

    if (err == ENOENT) {
        return WaylandSocketStatus::SocketNotCreated;
    }

    return WaylandSocketStatus::SocketCreatedUnusable;
}

Result<void> WestonReadinessDetector::wait_for_readiness(
    const std::string& socket_path,
    pid_t pid,
    std::chrono::milliseconds timeout,
    std::chrono::milliseconds poll_interval) {
    auto deadline = SystemClock::now() + timeout;

    while (SystemClock::now() < deadline) {
        auto status = check_socket(socket_path, pid);
        switch (status) {
            case WaylandSocketStatus::WaylandConnectionUsable:
                return Result<void>::success();

            case WaylandSocketStatus::WestonExited:
                return Result<void>::failure(Error(
                    ErrorCategory::Compositor,
                    ErrorCode::CompositorCrash,
                    "Weston process exited before becoming ready",
                    "socket=" + socket_path + ", pid=" + std::to_string(pid)));

            case WaylandSocketStatus::SocketNotCreated:
            case WaylandSocketStatus::SocketCreatedUnusable:
                break;
        }

        std::this_thread::sleep_for(poll_interval);
    }

    // Final check at deadline
    auto final_status = check_socket(socket_path, pid);
    if (final_status == WaylandSocketStatus::WaylandConnectionUsable) {
        return Result<void>::success();
    }

    if (final_status == WaylandSocketStatus::WestonExited) {
        return Result<void>::failure(Error(
            ErrorCategory::Compositor,
            ErrorCode::CompositorCrash,
            "Weston process exited before becoming ready",
            "socket=" + socket_path + ", pid=" + std::to_string(pid)));
    }

    return Result<void>::failure(Error(
        ErrorCategory::Compositor,
        ErrorCode::CompositorTimeout,
        "Weston socket readiness timed out (" + std::to_string(timeout.count()) + "ms): status=" +
            std::string(to_string(final_status)),
        "socket=" + socket_path + ", pid=" + std::to_string(pid)));
}

} // namespace lddm::weston
