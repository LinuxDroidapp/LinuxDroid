#include "lddm/session/session_manager.hpp"
#include "lddm/logging/logger.hpp"

namespace lddm {

SessionManager::~SessionManager() {
    (void)stop_all_sessions();
}

Result<std::shared_ptr<Session>> SessionManager::create_session(SessionConfig config) {
    std::lock_guard<std::mutex> lock(mutex_);

    if (sessions_.find(config.id) != sessions_.end()) {
        std::string err_msg = "Session with ID '" + config.id.str() + "' already exists";
        LDDM_LOG_ERROR(LogSubsystem::SESSION, "{}", err_msg);
        return Result<std::shared_ptr<Session>>::failure(Error(
            ErrorCategory::Session,
            ErrorCode::SessionAlreadyExists,
            std::move(err_msg),
            "session_id=" + config.id.str()));
    }

    auto session = std::make_shared<Session>(std::move(config));
    sessions_[session->id()] = session;

    if (!active_session_) {
        active_session_ = session;
        LDDM_LOG_INFO(LogSubsystem::SESSION, "Designated session '{}' as active session", session->id().str());
    }

    return Result<std::shared_ptr<Session>>::success(session);
}

Result<void> SessionManager::register_session(std::shared_ptr<Session> session) {
    if (!session) {
        return Result<void>::failure(Error(
            ErrorCategory::Session,
            ErrorCode::InternalLogicError,
            "Cannot register null session"));
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (sessions_.find(session->id()) != sessions_.end()) {
        std::string err_msg = "Session with ID '" + session->id().str() + "' already exists";
        LDDM_LOG_ERROR(LogSubsystem::SESSION, "{}", err_msg);
        return Result<void>::failure(Error(
            ErrorCategory::Session,
            ErrorCode::SessionAlreadyExists,
            std::move(err_msg),
            "session_id=" + session->id().str()));
    }

    sessions_[session->id()] = session;
    if (!active_session_) {
        active_session_ = session;
        LDDM_LOG_INFO(LogSubsystem::SESSION, "Designated session '{}' as active session", session->id().str());
    }

    return Result<void>::success();
}

std::shared_ptr<Session> SessionManager::find_session(const SessionId& id) const {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = sessions_.find(id);
    if (it != sessions_.end()) {
        return it->second;
    }
    return nullptr;
}

std::shared_ptr<Session> SessionManager::active_session() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return active_session_;
}

Result<void> SessionManager::set_active_session(const SessionId& id) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = sessions_.find(id);
    if (it == sessions_.end()) {
        std::string err_msg = "Cannot activate session '" + id.str() + "': not found";
        LDDM_LOG_ERROR(LogSubsystem::SESSION, "{}", err_msg);
        return Result<void>::failure(Error(
            ErrorCategory::Session,
            ErrorCode::SessionNotFound,
            std::move(err_msg),
            "session_id=" + id.str()));
    }

    active_session_ = it->second;
    LDDM_LOG_INFO(LogSubsystem::SESSION, "Active session set to '{}'", id.str());
    return Result<void>::success();
}

void SessionManager::clear_active_session() noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if (active_session_) {
        LDDM_LOG_INFO(LogSubsystem::SESSION, "Cleared active session '{}'", active_session_->id().str());
        active_session_ = nullptr;
    }
}

Result<void> SessionManager::stop_session(const SessionId& id) {
    std::shared_ptr<Session> target;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = sessions_.find(id);
        if (it == sessions_.end()) {
            return Result<void>::failure(Error(
                ErrorCategory::Session,
                ErrorCode::SessionNotFound,
                "Session not found: " + id.str(),
                "session_id=" + id.str()));
        }
        target = it->second;
    }

    return target->stop();
}

Result<void> SessionManager::remove_session(const SessionId& id) {
    std::shared_ptr<Session> target;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = sessions_.find(id);
        if (it == sessions_.end()) {
            return Result<void>::failure(Error(
                ErrorCategory::Session,
                ErrorCode::SessionNotFound,
                "Session not found: " + id.str(),
                "session_id=" + id.str()));
        }

        target = it->second;
        if (active_session_ && active_session_->id() == id) {
            active_session_ = nullptr;
        }
        sessions_.erase(it);
    }

    // Stop and cleanup outside lock
    if (target->state() == SessionState::RUNNING ||
        target->state() == SessionState::STARTING ||
        target->state() == SessionState::READY) {
        (void)target->stop();
    }
    (void)target->cleanup();

    LDDM_LOG_INFO(LogSubsystem::SESSION, "Removed session '{}'", id.str());
    return Result<void>::success();
}

Result<void> SessionManager::stop_all_sessions() {
    std::vector<std::shared_ptr<Session>> all;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        for (const auto& [_, sess] : sessions_) {
            if (sess) {
                all.push_back(sess);
            }
        }
    }

    for (const auto& sess : all) {
        (void)sess->stop();
    }

    return Result<void>::success();
}

std::size_t SessionManager::session_count() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return sessions_.size();
}

std::vector<std::shared_ptr<Session>> SessionManager::all_sessions() const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<std::shared_ptr<Session>> result;
    result.reserve(sessions_.size());
    for (const auto& [_, sess] : sessions_) {
        if (sess) {
            result.push_back(sess);
        }
    }
    return result;
}

} // namespace lddm

