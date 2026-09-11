#pragma once

#include "lddm/core/result.hpp"
#include <utility>

namespace lddm {

class UniqueFd {
public:
    constexpr UniqueFd() noexcept : fd_(-1) {}
    constexpr explicit UniqueFd(int fd) noexcept : fd_(fd) {}

    ~UniqueFd();

    UniqueFd(const UniqueFd&) = delete;
    UniqueFd& operator=(const UniqueFd&) = delete;

    UniqueFd(UniqueFd&& other) noexcept : fd_(other.release()) {}

    UniqueFd& operator=(UniqueFd&& other) noexcept {
        if (this != &other) {
            reset(other.release());
        }
        return *this;
    }

    [[nodiscard]] constexpr int get() const noexcept { return fd_; }
    [[nodiscard]] constexpr bool is_valid() const noexcept { return fd_ >= 0; }
    [[nodiscard]] explicit constexpr operator bool() const noexcept { return is_valid(); }

    int release() noexcept {
        int old = fd_;
        fd_ = -1;
        return old;
    }

    void reset(int new_fd = -1) noexcept;

    Result<void> set_close_on_exec(bool enable = true);
    Result<void> set_nonblocking(bool enable = true);

    static Result<std::pair<UniqueFd, UniqueFd>> create_pipe(bool cloexec = true, bool nonblocking = false);

private:
    int fd_{-1};
};

} // namespace lddm
