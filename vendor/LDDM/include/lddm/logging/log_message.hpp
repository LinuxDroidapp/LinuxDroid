#pragma once

#include "lddm/logging/log_level.hpp"
#include <chrono>
#include <string>
#include <string_view>
#include <source_location>
#include <thread>

namespace lddm {

struct LogMessage {
    std::chrono::system_clock::time_point timestamp{std::chrono::system_clock::now()};
    std::thread::id thread_id{std::this_thread::get_id()};
    LogLevel level{LogLevel::INFO};
    LogSubsystem subsystem{LogSubsystem::LDDM};
    std::string_view file_name{};
    std::string_view function_name{};
    std::uint32_t line{0};
    std::string text{};

    [[nodiscard]] std::string format(bool colorize = false) const;
};

} // namespace lddm

