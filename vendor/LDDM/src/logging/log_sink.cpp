#include "lddm/logging/log_sink.hpp"
#include <iostream>

namespace lddm {

StreamSink::StreamSink(std::ostream& os, bool colorize)
    : os_(os), colorize_(colorize) {}

void StreamSink::write(const LogMessage& message) {
    std::lock_guard<std::mutex> lock(mutex_);
    os_ << message.format(colorize_) << '\n';
    if (message.level >= LogLevel::WARN) {
        os_.flush();
    }
}

void StreamSink::flush() {
    std::lock_guard<std::mutex> lock(mutex_);
    os_.flush();
}

FileSink::FileSink(const std::string& filepath)
    : file_(filepath, std::ios::out | std::ios::app) {}

FileSink::~FileSink() {
    flush();
}

void FileSink::write(const LogMessage& message) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (file_.is_open()) {
        file_ << message.format(false) << '\n';
        if (message.level >= LogLevel::WARN) {
            file_.flush();
        }
    }
}

void FileSink::flush() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (file_.is_open()) {
        file_.flush();
    }
}

bool FileSink::is_open() const noexcept {
    return file_.is_open();
}

void MemorySink::write(const LogMessage& message) {
    std::lock_guard<std::mutex> lock(mutex_);
    entries_.push_back(message);
}

std::vector<LogMessage> MemorySink::entries() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return entries_;
}

std::size_t MemorySink::size() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return entries_.size();
}

void MemorySink::clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    entries_.clear();
}

} // namespace lddm

