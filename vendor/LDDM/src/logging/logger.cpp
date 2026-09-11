#include "lddm/logging/logger.hpp"
#include <iostream>

namespace lddm {

Logger::Logger() {
    // Default console sink to std::clog
    sinks_.push_back(std::make_shared<StreamSink>(std::clog, true));
}

Logger::~Logger() {
    flush();
}

Logger& Logger::instance() {
    static Logger default_instance;
    return default_instance;
}

void Logger::set_level(LogLevel level) noexcept {
    level_ = level;
}

LogLevel Logger::level() const noexcept {
    return level_;
}

bool Logger::should_log(LogLevel level) const noexcept {
    if (level_ == LogLevel::OFF) {
        return false;
    }
    return static_cast<std::uint8_t>(level) >= static_cast<std::uint8_t>(level_);
}

void Logger::add_sink(std::shared_ptr<ILogSink> sink) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (sink) {
        sinks_.push_back(std::move(sink));
    }
}

void Logger::clear_sinks() {
    std::lock_guard<std::mutex> lock(mutex_);
    sinks_.clear();
}

std::size_t Logger::sink_count() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return sinks_.size();
}

void Logger::log(LogLevel level,
                 LogSubsystem subsystem,
                 std::string message,
                 std::source_location location) {
    if (!should_log(level)) {
        return;
    }

    LogMessage msg{
        .timestamp = std::chrono::system_clock::now(),
        .thread_id = std::this_thread::get_id(),
        .level = level,
        .subsystem = subsystem,
        .file_name = location.file_name(),
        .function_name = location.function_name(),
        .line = location.line(),
        .text = std::move(message)
    };

    std::vector<std::shared_ptr<ILogSink>> sinks_copy;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        sinks_copy = sinks_;
    }

    for (const auto& sink : sinks_copy) {
        if (sink) {
            sink->write(msg);
        }
    }
}

void Logger::flush() {
    std::vector<std::shared_ptr<ILogSink>> sinks_copy;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        sinks_copy = sinks_;
    }

    for (const auto& sink : sinks_copy) {
        if (sink) {
            sink->flush();
        }
    }
}

} // namespace lddm

