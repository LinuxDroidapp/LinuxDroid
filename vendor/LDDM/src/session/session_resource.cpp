#include "lddm/session/session_resource.hpp"
#include "lddm/logging/logger.hpp"
#include <system_error>

namespace lddm {

SessionResourceTracker::~SessionResourceTracker() {
    (void)cleanup();
}

SessionResourceTracker::SessionResourceTracker(SessionResourceTracker&& other) noexcept {
    std::lock_guard<std::mutex> lock(other.mutex_);
    fds_ = std::move(other.fds_);
    temporary_files_ = std::move(other.temporary_files_);
    cleaned_ = other.cleaned_;
    other.cleaned_ = true;
}

SessionResourceTracker& SessionResourceTracker::operator=(SessionResourceTracker&& other) noexcept {
    if (this != &other) {
        std::scoped_lock lock(mutex_, other.mutex_);
        (void)cleanup();
        fds_ = std::move(other.fds_);
        temporary_files_ = std::move(other.temporary_files_);
        cleaned_ = other.cleaned_;
        other.cleaned_ = true;
    }
    return *this;
}

void SessionResourceTracker::track_fd(UniqueFd fd) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (fd.is_valid()) {
        fds_.push_back(std::move(fd));
    }
}

void SessionResourceTracker::track_temporary_file(std::filesystem::path path) {
    std::lock_guard<std::mutex> lock(mutex_);
    temporary_files_.push_back(std::move(path));
}

std::size_t SessionResourceTracker::tracked_fd_count() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return fds_.size();
}

std::size_t SessionResourceTracker::tracked_file_count() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return temporary_files_.size();
}

Result<void> SessionResourceTracker::cleanup() noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if (cleaned_) {
        return Result<void>::success();
    }

    // 1. Close all tracked file descriptors
    fds_.clear();

    // 2. Remove all tracked temporary files
    std::error_code ec;
    for (const auto& path : temporary_files_) {
        if (std::filesystem::exists(path, ec)) {
            std::filesystem::remove(path, ec);
            if (ec) {
                LDDM_LOG_WARN(LogSubsystem::SESSION, "Failed to remove temporary file '{}': {}",
                              path.string(), ec.message());
                ec.clear();
            }
        }
    }
    temporary_files_.clear();

    cleaned_ = true;
    LDDM_LOG_DEBUG(LogSubsystem::SESSION, "Session resources cleaned up successfully");
    return Result<void>::success();
}

} // namespace lddm

