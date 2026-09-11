#include "test_framework.hpp"
#include "lddm/logging/logger.hpp"
#include "lddm/logging/log_sink.hpp"
#include <thread>
#include <vector>

TEST_CASE(Logging_LevelParsing) {
    EXPECT_EQ(lddm::parse_log_level("INFO"), lddm::LogLevel::INFO);
    EXPECT_EQ(lddm::parse_log_level("DEBUG"), lddm::LogLevel::DEBUG);
    EXPECT_EQ(lddm::parse_log_level("warn"), lddm::LogLevel::WARN);
    EXPECT_EQ(lddm::parse_log_level("error"), lddm::LogLevel::ERROR);
    EXPECT_FALSE(lddm::parse_log_level("invalid_level").has_value());
}

TEST_CASE(Logging_SubsystemParsing) {
    EXPECT_EQ(lddm::parse_log_subsystem("LDDM"), lddm::LogSubsystem::LDDM);
    EXPECT_EQ(lddm::parse_log_subsystem("SESSION"), lddm::LogSubsystem::SESSION);
    EXPECT_EQ(lddm::parse_log_subsystem("weston"), lddm::LogSubsystem::WESTON);
    EXPECT_EQ(lddm::parse_log_subsystem("ldde"), lddm::LogSubsystem::LDDE);
    EXPECT_FALSE(lddm::parse_log_subsystem("unknown_subsys").has_value());
}

TEST_CASE(Logging_MemorySinkCaptureAndFiltering) {
    auto mem_sink = std::make_shared<lddm::MemorySink>();
    lddm::Logger logger;
    logger.clear_sinks();
    logger.add_sink(mem_sink);
    logger.set_level(lddm::LogLevel::WARN);

    logger.log(lddm::LogLevel::DEBUG, lddm::LogSubsystem::SESSION, "Debug message should be ignored");
    logger.log(lddm::LogLevel::INFO, lddm::LogSubsystem::SESSION, "Info message should be ignored");
    logger.log(lddm::LogLevel::WARN, lddm::LogSubsystem::SESSION, "Warning message logged");
    logger.log(lddm::LogLevel::ERROR, lddm::LogSubsystem::WESTON, "Error message logged");

    EXPECT_EQ(mem_sink->size(), 2u);
    auto entries = mem_sink->entries();
    EXPECT_EQ(entries[0].level, lddm::LogLevel::WARN);
    EXPECT_EQ(entries[0].subsystem, lddm::LogSubsystem::SESSION);
    EXPECT_EQ(entries[0].text, "Warning message logged");

    EXPECT_EQ(entries[1].level, lddm::LogLevel::ERROR);
    EXPECT_EQ(entries[1].subsystem, lddm::LogSubsystem::WESTON);
    EXPECT_EQ(entries[1].text, "Error message logged");
}

TEST_CASE(Logging_ThreadSafety) {
    auto mem_sink = std::make_shared<lddm::MemorySink>();
    lddm::Logger logger;
    logger.clear_sinks();
    logger.add_sink(mem_sink);
    logger.set_level(lddm::LogLevel::TRACE);

    constexpr int num_threads = 4;
    constexpr int logs_per_thread = 50;

    std::vector<std::thread> threads;
    threads.reserve(num_threads);

    for (int t = 0; t < num_threads; ++t) {
        threads.emplace_back([&logger, t]() {
            for (int i = 0; i < logs_per_thread; ++i) {
                logger.log(lddm::LogLevel::INFO, lddm::LogSubsystem::LDDM,
                           "Thread " + std::to_string(t) + " msg " + std::to_string(i));
            }
        });
    }

    for (auto& th : threads) {
        th.join();
    }

    EXPECT_EQ(mem_sink->size(), static_cast<std::size_t>(num_threads * logs_per_thread));
}

TEST_MAIN()

