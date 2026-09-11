#pragma once

#include "lddm/session/session.hpp"
#include "lddm/session/session_id.hpp"
#include "lddm/core/result.hpp"
#include <unordered_map>
#include <memory>
#include <mutex>
#include <vector>
#include <optional>

namespace lddm {

class SessionManager {
public:
    SessionManager() = default;
    ~SessionManager();

    SessionManager(const SessionManager&) = delete;
    SessionManager& operator=(const SessionManager&) = delete;
    SessionManager(SessionManager&&) = delete;
    SessionManager& operator=(SessionManager&&) = delete;

    Result<std::shared_ptr<Session>> create_session(SessionConfig config);
    Result<void> register_session(std::shared_ptr<Session> session);

    [[nodiscard]] std::shared_ptr<Session> find_session(const SessionId& id) const;
    [[nodiscard]] std::shared_ptr<Session> active_session() const;

    Result<void> set_active_session(const SessionId& id);
    void clear_active_session() noexcept;

    Result<void> stop_session(const SessionId& id);
    Result<void> remove_session(const SessionId& id);
    Result<void> stop_all_sessions();

    [[nodiscard]] std::size_t session_count() const noexcept;
    [[nodiscard]] std::vector<std::shared_ptr<Session>> all_sessions() const;

private:
    mutable std::mutex mutex_;
    std::unordered_map<SessionId, std::shared_ptr<Session>> sessions_;
    std::shared_ptr<Session> active_session_{nullptr};
};

} // namespace lddm

