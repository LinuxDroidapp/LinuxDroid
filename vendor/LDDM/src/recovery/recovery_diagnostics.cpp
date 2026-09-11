#include "lddm/recovery/recovery_diagnostics.hpp"

namespace lddm::recovery {

RecoveryDiagnostics::RecoveryDiagnostics(std::string session_id) noexcept
    : session_id_(std::move(session_id)) {}

RecoveryDiagnostics::RecoveryDiagnostics(const RecoveryDiagnostics& other) {
    std::lock_guard lock(other.mutex_);
    session_id_ = other.session_id_;
    current_state_ = other.current_state_;
    total_attempts_ = other.total_attempts_;
    successful_recoveries_ = other.successful_recoveries_;
    failed_recoveries_ = other.failed_recoveries_;
    last_reason_ = other.last_reason_;
    last_component_ = other.last_component_;
    last_error_ = other.last_error_;
    history_ = other.history_;
}

RecoveryDiagnostics& RecoveryDiagnostics::operator=(const RecoveryDiagnostics& other) {
    if (this == &other) {
        return *this;
    }
    std::scoped_lock lock(mutex_, other.mutex_);
    session_id_ = other.session_id_;
    current_state_ = other.current_state_;
    total_attempts_ = other.total_attempts_;
    successful_recoveries_ = other.successful_recoveries_;
    failed_recoveries_ = other.failed_recoveries_;
    last_reason_ = other.last_reason_;
    last_component_ = other.last_component_;
    last_error_ = other.last_error_;
    history_ = other.history_;
    return *this;
}

RecoveryDiagnostics::RecoveryDiagnostics(RecoveryDiagnostics&& other) noexcept {
    std::lock_guard lock(other.mutex_);
    session_id_ = std::move(other.session_id_);
    current_state_ = other.current_state_;
    total_attempts_ = other.total_attempts_;
    successful_recoveries_ = other.successful_recoveries_;
    failed_recoveries_ = other.failed_recoveries_;
    last_reason_ = other.last_reason_;
    last_component_ = std::move(other.last_component_);
    last_error_ = std::move(other.last_error_);
    history_ = std::move(other.history_);
}

RecoveryDiagnostics& RecoveryDiagnostics::operator=(RecoveryDiagnostics&& other) noexcept {
    if (this == &other) {
        return *this;
    }
    std::scoped_lock lock(mutex_, other.mutex_);
    session_id_ = std::move(other.session_id_);
    current_state_ = other.current_state_;
    total_attempts_ = other.total_attempts_;
    successful_recoveries_ = other.successful_recoveries_;
    failed_recoveries_ = other.failed_recoveries_;
    last_reason_ = other.last_reason_;
    last_component_ = std::move(other.last_component_);
    last_error_ = std::move(other.last_error_);
    history_ = std::move(other.history_);
    return *this;
}

void RecoveryDiagnostics::set_session_id(std::string session_id) noexcept {
    std::lock_guard lock(mutex_);
    session_id_ = std::move(session_id);
}

std::string RecoveryDiagnostics::session_id() const noexcept {
    std::lock_guard lock(mutex_);
    return session_id_;
}

void RecoveryDiagnostics::set_current_state(RecoveryState state) noexcept {
    std::lock_guard lock(mutex_);
    current_state_ = state;
}

RecoveryState RecoveryDiagnostics::current_state() const noexcept {
    std::lock_guard lock(mutex_);
    return current_state_;
}

void RecoveryDiagnostics::record_recovery_start(std::uint64_t /*recovery_id*/,
                                               const std::string& component,
                                               RecoveryReason reason,
                                               RecoveryPolicy /*policy*/,
                                               std::uint32_t /*attempt*/) noexcept {
    std::lock_guard lock(mutex_);
    total_attempts_++;
    last_reason_ = reason;
    last_component_ = component;
    current_state_ = RecoveryState::Recovering;
}

void RecoveryDiagnostics::record_recovery_success(std::uint64_t recovery_id,
                                                 const std::string& component,
                                                 RecoveryReason reason,
                                                 RecoveryPolicy policy,
                                                 std::uint32_t attempt,
                                                 std::chrono::milliseconds duration) noexcept {
    std::lock_guard lock(mutex_);
    successful_recoveries_++;
    current_state_ = RecoveryState::Recovered;

    RecoveryHistoryRecord record{
        .timestamp = SystemClock::now(),
        .recovery_id = recovery_id,
        .component = component,
        .reason = reason,
        .policy = policy,
        .final_state = RecoveryState::Recovered,
        .attempt_number = attempt,
        .error_message = "",
        .duration_ms = static_cast<std::uint64_t>(duration.count())
    };

    if (history_.size() >= kMaxHistoryRecords) {
        history_.erase(history_.begin());
    }
    history_.push_back(std::move(record));
}

void RecoveryDiagnostics::record_recovery_failure(std::uint64_t recovery_id,
                                                 const std::string& component,
                                                 RecoveryReason reason,
                                                 RecoveryPolicy policy,
                                                 std::uint32_t attempt,
                                                 const Error& error,
                                                 std::chrono::milliseconds duration) noexcept {
    std::lock_guard lock(mutex_);
    failed_recoveries_++;
    current_state_ = RecoveryState::Failed;
    last_error_ = error;

    RecoveryHistoryRecord record{
        .timestamp = SystemClock::now(),
        .recovery_id = recovery_id,
        .component = component,
        .reason = reason,
        .policy = policy,
        .final_state = RecoveryState::Failed,
        .attempt_number = attempt,
        .error_message = error.message(),
        .duration_ms = static_cast<std::uint64_t>(duration.count())
    };

    if (history_.size() >= kMaxHistoryRecords) {
        history_.erase(history_.begin());
    }
    history_.push_back(std::move(record));
}

std::uint32_t RecoveryDiagnostics::total_attempts() const noexcept {
    std::lock_guard lock(mutex_);
    return total_attempts_;
}

std::uint32_t RecoveryDiagnostics::successful_recoveries() const noexcept {
    std::lock_guard lock(mutex_);
    return successful_recoveries_;
}

std::uint32_t RecoveryDiagnostics::failed_recoveries() const noexcept {
    std::lock_guard lock(mutex_);
    return failed_recoveries_;
}

RecoveryReason RecoveryDiagnostics::last_reason() const noexcept {
    std::lock_guard lock(mutex_);
    return last_reason_;
}

std::string RecoveryDiagnostics::last_component() const noexcept {
    std::lock_guard lock(mutex_);
    return last_component_;
}

Error RecoveryDiagnostics::last_error() const noexcept {
    std::lock_guard lock(mutex_);
    return last_error_;
}

std::vector<RecoveryHistoryRecord> RecoveryDiagnostics::history() const {
    std::lock_guard lock(mutex_);
    return history_;
}

void RecoveryDiagnostics::clear_history() noexcept {
    std::lock_guard lock(mutex_);
    history_.clear();
}

} // namespace lddm::recovery
