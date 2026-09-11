#pragma once

#include "lddm/core/result.hpp"
#include "lddm/platform/unique_fd.hpp"
#include <vector>
#include <filesystem>
#include <mutex>

namespace lddm {

class SessionResourceTracker {
public:
    SessionResourceTracker() = default;
    ~SessionResourceTracker();

    SessionResourceTracker(const SessionResourceTracker&) = delete;
    SessionResourceTracker& operator=(const SessionResourceTracker&) = delete;
    SessionResourceTracker(SessionResourceTracker&& other) noexcept;
    SessionResourceTracker& operator=(SessionResourceTracker&& other) noexcept;

    void track_fd(UniqueFd fd);
    void track_temporary_file(std::filesystem::path path);

    [[nodiscard]] std::size_t tracked_fd_count() const noexcept;
    [[nodiscard]] std::size_t tracked_file_count() const noexcept;

    Result<void> cleanup() noexcept;

private:
    mutable std::mutex mutex_;
    std::vector<UniqueFd> fds_;
    std::vector<std::filesystem::path> temporary_files_;
    bool cleaned_{false};
};

} // namespace lddm

