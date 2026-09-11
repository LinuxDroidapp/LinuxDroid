#pragma once

#include "lddm/logging/log_message.hpp"
#include <memory>
#include <vector>
#include <mutex>
#include <iosfwd>
#include <fstream>

namespace lddm {

class ILogSink {
public:
    virtual ~ILogSink() = default;
    virtual void write(const LogMessage& message) = 0;
    virtual void flush() = 0;
};

class StreamSink : public ILogSink {
public:
    explicit StreamSink(std::ostream& os, bool colorize = false);
    ~StreamSink() override = default;

    void write(const LogMessage& message) override;
    void flush() override;

private:
    std::ostream& os_;
    bool colorize_{false};
    std::mutex mutex_;
};

class FileSink : public ILogSink {
public:
    explicit FileSink(const std::string& filepath);
    ~FileSink() override;

    void write(const LogMessage& message) override;
    void flush() override;

    [[nodiscard]] bool is_open() const noexcept;

private:
    std::ofstream file_;
    std::mutex mutex_;
};

class MemorySink : public ILogSink {
public:
    MemorySink() = default;
    ~MemorySink() override = default;

    void write(const LogMessage& message) override;
    void flush() override {}

    [[nodiscard]] std::vector<LogMessage> entries() const;
    [[nodiscard]] std::size_t size() const;
    void clear();

private:
    mutable std::mutex mutex_;
    std::vector<LogMessage> entries_;
};

} // namespace lddm

