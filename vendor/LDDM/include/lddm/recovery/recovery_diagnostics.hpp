#pragma once

#include "lddm/core/types.hpp"
#include "lddm/core/error.hpp"
#include "lddm/recovery/recovery_types.hpp"
#include <vector>
#include <string>
#include <mutex>
#include <chrono>

namespace lddm::recovery {

struct RecoveryHistoryRecord {
    SystemTimePoint timestamp{SystemClock::now()};
    std::uint64_t recovery_id{0};
    std::string component{};
    RecoveryReason reason{RecoveryReason::Unknown};
    RecoveryPolicy policy{RecoveryPolicy::NoRecovery};
    RecoveryState final_state{RecoveryState::Idle};
    std::uint32_t attempt_number{0};
    std::string error_message{};
    std::uint64_t duration_ms{0};
};

class RecoveryDiagnostics {
public:
    explicit RecoveryDiagnostics(std::string session_id = "") noexcept;
    ~RecoveryDiagnostics() = default;

    RecoveryDiagnostics(const RecoveryDiagnostics& other);
    RecoveryDiagnostics& operator=(const RecoveryDiagnostics& other);
    RecoveryDiagnostics(RecoveryDiagnostics&& other) noexcept;
    RecoveryDiagnostics& operator=(RecoveryDiagnostics&& other) noexcept;

    void set_session_id(std::string session_id) noexcept;
    [[nodiscard]] std::string session_id() const noexcept;

    void set_current_state(RecoveryState state) noexcept;
    [[nodiscard]] RecoveryState current_state() const noexcept;

    void record_recovery_start(std::uint64_t recovery_id,
                               const std::string& component,
                               RecoveryReason reason,
                               RecoveryPolicy policy,
                               std::uint32_t attempt) noexcept;

    void record_recovery_success(std::uint64_t recovery_id,
                                 const std::string& component,
                                 RecoveryReason reason,
                                 RecoveryPolicy policy,
                                 std::uint32_t attempt,
                                 std::chrono::milliseconds duration) noexcept;

    void record_recovery_failure(std::uint64_t recovery_id,
                                 const std::string& component,
                                 RecoveryReason reason,
                                 RecoveryPolicy policy,
                                 std::uint32_t attempt,
                                 const Error& error,
                                 std::chrono::milliseconds duration) noexcept;

    [[nodiscard]] std::uint32_t total_attempts() const noexcept;
    [[nodiscard]] std::uint32_t successful_recoveries() const noexcept;
    [[nodiscard]] std::uint32_t failed_recoveries() const noexcept;
    [[nodiscard]] RecoveryReason last_reason() const noexcept;
    [[nodiscard]] std::string last_component() const noexcept;
    [[nodiscard]] Error last_error() const noexcept;
    [[nodiscard]] std::vector<RecoveryHistoryRecord> history() const;

    void clear_history() noexcept;

private:
    static constexpr std::size_t kMaxHistoryRecords = 50;

    mutable std::mutex mutex_;
    std::string session_id_{};
    RecoveryState current_state_{RecoveryState::Idle};
    std::uint32_t total_attempts_{0};
    std::uint32_t successful_recoveries_{0};
    std::uint32_t failed_recoveries_{0};
    RecoveryReason last_reason_{RecoveryReason::Unknown};
    std::string last_component_{};
    Error last_error_{};
    std::vector<RecoveryHistoryRecord> history_{};
};

} // namespace lddm::recovery
