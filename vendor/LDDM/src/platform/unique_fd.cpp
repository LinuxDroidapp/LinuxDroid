#include "lddm/platform/unique_fd.hpp"
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>

namespace lddm {

UniqueFd::~UniqueFd() {
    reset();
}

void UniqueFd::reset(int new_fd) noexcept {
    if (fd_ >= 0) {
        ::close(fd_);
    }
    fd_ = new_fd;
}

Result<void> UniqueFd::set_close_on_exec(bool enable) {
    if (!is_valid()) {
        return Result<void>::failure(Error(
            ErrorCategory::Platform,
            ErrorCode::PlatformFdError,
            "Cannot set CLOEXEC on invalid file descriptor"));
    }

    int flags = ::fcntl(fd_, F_GETFD);
    if (flags < 0) {
        return Result<void>::failure(Error(
            ErrorCategory::Platform,
            ErrorCode::PlatformSyscallFailed,
            "fcntl(F_GETFD) failed: " + std::string(std::strerror(errno))));
    }

    if (enable) {
        flags |= FD_CLOEXEC;
    } else {
        flags &= ~FD_CLOEXEC;
    }

    if (::fcntl(fd_, F_SETFD, flags) < 0) {
        return Result<void>::failure(Error(
            ErrorCategory::Platform,
            ErrorCode::PlatformSyscallFailed,
            "fcntl(F_SETFD) failed: " + std::string(std::strerror(errno))));
    }

    return Result<void>::success();
}

Result<void> UniqueFd::set_nonblocking(bool enable) {
    if (!is_valid()) {
        return Result<void>::failure(Error(
            ErrorCategory::Platform,
            ErrorCode::PlatformFdError,
            "Cannot set nonblocking on invalid file descriptor"));
    }

    int flags = ::fcntl(fd_, F_GETFL);
    if (flags < 0) {
        return Result<void>::failure(Error(
            ErrorCategory::Platform,
            ErrorCode::PlatformSyscallFailed,
            "fcntl(F_GETFL) failed: " + std::string(std::strerror(errno))));
    }

    if (enable) {
        flags |= O_NONBLOCK;
    } else {
        flags &= ~O_NONBLOCK;
    }

    if (::fcntl(fd_, F_SETFL, flags) < 0) {
        return Result<void>::failure(Error(
            ErrorCategory::Platform,
            ErrorCode::PlatformSyscallFailed,
            "fcntl(F_SETFL) failed: " + std::string(std::strerror(errno))));
    }

    return Result<void>::success();
}

Result<std::pair<UniqueFd, UniqueFd>> UniqueFd::create_pipe(bool cloexec, bool nonblocking) {
    int fds[2] = {-1, -1};
    int flags = 0;
#ifdef O_CLOEXEC
    if (cloexec) flags |= O_CLOEXEC;
#endif
#ifdef O_NONBLOCK
    if (nonblocking) flags |= O_NONBLOCK;
#endif

    if (::pipe2(fds, flags) < 0) {
        return Result<std::pair<UniqueFd, UniqueFd>>::failure(Error(
            ErrorCategory::Platform,
            ErrorCode::PlatformSyscallFailed,
            "pipe2 failed: " + std::string(std::strerror(errno))));
    }

    return Result<std::pair<UniqueFd, UniqueFd>>::success(
        std::make_pair(UniqueFd(fds[0]), UniqueFd(fds[1])));
}

} // namespace lddm

