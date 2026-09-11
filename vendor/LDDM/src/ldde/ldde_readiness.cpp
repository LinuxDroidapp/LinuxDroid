#include "lddm/ldde/ldde_readiness.hpp"
#include "lddm/core/error.hpp"
#include "lddm/platform/clock.hpp"

#include <filesystem>
#include <fstream>
#include <thread>
#include <cstring>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <signal.h>
#include <cerrno>

namespace lddm::ldde {

std::string_view to_string(LddeReadinessStatus status) noexcept {
    switch (status) {
        case LddeReadinessStatus::NotReady:      return "NotReady";
        case LddeReadinessStatus::Ready:         return "Ready";
        case LddeReadinessStatus::ProcessExited: return "ProcessExited";
        case LddeReadinessStatus::ProtocolError: return "ProtocolError";
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

LddeReadinessStatus LddeReadinessDetector::check_readiness(
    const std::string& path,
    LddeReadinessMode mode,
    pid_t pid) {
    // 1. Process liveness check
    if (pid > 0 && is_process_dead_or_zombie(pid)) {
        return LddeReadinessStatus::ProcessExited;
    }

    // 2. Mode-specific check
    if (mode == LddeReadinessMode::File) {
        std::error_code ec;
        if (!std::filesystem::exists(path, ec)) {
            return LddeReadinessStatus::NotReady;
        }

        std::ifstream ifs(path);
        if (!ifs.is_open()) {
            return LddeReadinessStatus::NotReady;
        }

        std::string line;
        bool has_ready_status = false;
        bool has_failure_status = false;

        while (std::getline(ifs, line)) {
            // Trim leading/trailing spaces
            auto first = line.find_first_not_of(" \t\r\n");
            if (first == std::string::npos) continue;
            auto last = line.find_last_not_of(" \t\r\n");
            std::string trimmed = line.substr(first, last - first + 1);

            if (trimmed == "STATUS=READY") {
                has_ready_status = true;
            } else if (trimmed.rfind("STATUS=FAILED", 0) == 0 || trimmed.rfind("STATUS=ERROR", 0) == 0) {
                has_failure_status = true;
            }
        }

        if (has_failure_status) {
            return LddeReadinessStatus::ProtocolError;
        }
        if (has_ready_status) {
            return LddeReadinessStatus::Ready;
        }

        return LddeReadinessStatus::NotReady;
    } else if (mode == LddeReadinessMode::Socket) {
        std::error_code ec;
        if (!std::filesystem::exists(path, ec)) {
            return LddeReadinessStatus::NotReady;
        }

        int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) {
            return LddeReadinessStatus::NotReady;
        }

        struct sockaddr_un addr;
        std::memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        std::strncpy(addr.sun_path, path.c_str(), sizeof(addr.sun_path) - 1);

        int ret = connect(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr));
        close(fd);

        if (ret == 0) {
            return LddeReadinessStatus::Ready;
        }

        return LddeReadinessStatus::NotReady;
    }

    return LddeReadinessStatus::NotReady;
}

Result<void> LddeReadinessDetector::wait_for_readiness(
    const std::string& path,
    LddeReadinessMode mode,
    pid_t pid,
    std::chrono::milliseconds timeout,
    std::chrono::milliseconds poll_interval) {
    auto deadline = SystemClock::now() + timeout;

    while (SystemClock::now() < deadline) {
        auto status = check_readiness(path, mode, pid);
        switch (status) {
            case LddeReadinessStatus::Ready:
                return Result<void>::success();

            case LddeReadinessStatus::ProcessExited:
                return Result<void>::failure(Error(
                    ErrorCategory::Desktop,
                    ErrorCode::LddeCrash,
                    "LDDE process exited before becoming ready",
                    "path=" + path + ", pid=" + std::to_string(pid)));

            case LddeReadinessStatus::ProtocolError:
                return Result<void>::failure(Error(
                    ErrorCategory::Desktop,
                    ErrorCode::LddeProtocolError,
                    "LDDE readiness check reported failure or protocol error",
                    "path=" + path + ", pid=" + std::to_string(pid)));

            case LddeReadinessStatus::NotReady:
                break;
        }

        std::this_thread::sleep_for(poll_interval);
    }

    // Final check at deadline
    auto final_status = check_readiness(path, mode, pid);
    if (final_status == LddeReadinessStatus::Ready) {
        return Result<void>::success();
    }

    if (final_status == LddeReadinessStatus::ProcessExited) {
        return Result<void>::failure(Error(
            ErrorCategory::Desktop,
            ErrorCode::LddeCrash,
            "LDDE process exited before becoming ready",
            "path=" + path + ", pid=" + std::to_string(pid)));
    }

    if (final_status == LddeReadinessStatus::ProtocolError) {
        return Result<void>::failure(Error(
            ErrorCategory::Desktop,
            ErrorCode::LddeProtocolError,
            "LDDE readiness check reported failure or protocol error",
            "path=" + path + ", pid=" + std::to_string(pid)));
    }

    return Result<void>::failure(Error(
        ErrorCategory::Desktop,
        ErrorCode::LddeTimeout,
        "LDDE readiness timed out (" + std::to_string(timeout.count()) + "ms): status=" +
            std::string(to_string(final_status)),
        "path=" + path + ", pid=" + std::to_string(pid)));
}

} // namespace lddm::ldde
