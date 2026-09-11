#pragma once

#include "lddm/logging/log_sink.hpp"
#include <format>
#include <memory>
#include <mutex>
#include <vector>
#include <string_view>

namespace lddm {

class Logger {
public:
    Logger();
    ~Logger();

    Logger(const Logger&) = delete;
    Logger& operator=(const Logger&) = delete;
    Logger(Logger&&) = delete;
    Logger& operator=(Logger&&) = delete;

    static Logger& instance();

    void set_level(LogLevel level) noexcept;
    [[nodiscard]] LogLevel level() const noexcept;

    [[nodiscard]] bool should_log(LogLevel level) const noexcept;

    void add_sink(std::shared_ptr<ILogSink> sink);
    void clear_sinks();
    [[nodiscard]] std::size_t sink_count() const;

    void log(LogLevel level,
             LogSubsystem subsystem,
             std::string message,
             std::source_location location = std::source_location::current());

    template <typename... Args>
    void log_fmt(LogLevel level,
                LogSubsystem subsystem,
                std::source_location location,
                std::format_string<Args...> fmt,
                Args&&... args) {
        if (!should_log(level)) {
            return;
        }
        std::string formatted = std::format(fmt, std::forward<Args>(args)...);
        log(level, subsystem, std::move(formatted), location);
    }

    void flush();

private:
    LogLevel level_{LogLevel::INFO};
    std::vector<std::shared_ptr<ILogSink>> sinks_;
    mutable std::mutex mutex_;
};

} // namespace lddm

#define LDDM_LOG_TRACE(subsystem, ...) \
    ::lddm::Logger::instance().log_fmt(::lddm::LogLevel::TRACE, (subsystem), std::source_location::current(), __VA_ARGS__)

#define LDDM_LOG_DEBUG(subsystem, ...) \
    ::lddm::Logger::instance().log_fmt(::lddm::LogLevel::DEBUG, (subsystem), std::source_location::current(), __VA_ARGS__)

#define LDDM_LOG_INFO(subsystem, ...) \
    ::lddm::Logger::instance().log_fmt(::lddm::LogLevel::INFO, (subsystem), std::source_location::current(), __VA_ARGS__)

#define LDDM_LOG_WARN(subsystem, ...) \
    ::lddm::Logger::instance().log_fmt(::lddm::LogLevel::WARN, (subsystem), std::source_location::current(), __VA_ARGS__)

#define LDDM_LOG_ERROR(subsystem, ...) \
    ::lddm::Logger::instance().log_fmt(::lddm::LogLevel::ERROR, (subsystem), std::source_location::current(), __VA_ARGS__)

#define LDDM_LOG_FATAL(subsystem, ...) \
    ::lddm::Logger::instance().log_fmt(::lddm::LogLevel::FATAL, (subsystem), std::source_location::current(), __VA_ARGS__)

