#include "test_framework.hpp"
#include "lddm/platform/clock.hpp"
#include "lddm/platform/environment.hpp"
#include "lddm/platform/paths.hpp"
#include "lddm/platform/signal_handler.hpp"
#include <thread>
#include <chrono>

TEST_CASE(Platform_ClockMonotonic) {
    auto t1 = lddm::ClockUtil::monotonic_nanos();
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
    auto t2 = lddm::ClockUtil::monotonic_nanos();

    EXPECT_TRUE(t2 > t1);
    EXPECT_TRUE(lddm::ClockUtil::monotonic_millis() > 0);
}

TEST_CASE(Platform_ClockIso8601) {
    auto iso = lddm::ClockUtil::current_time_iso8601();
    EXPECT_FALSE(iso.empty());
    // Format: YYYY-MM-DDTHH:MM:SS.mmmZ (24 characters)
    EXPECT_TRUE(iso.size() >= 20);
    EXPECT_TRUE(iso.find('T') != std::string::npos);
    EXPECT_EQ(iso.back(), 'Z');
}

TEST_CASE(Platform_Environment) {
    const std::string test_key = "LDDM_TEST_ENV_VAR_L0";
    const std::string test_val = "lddm_foundation_test";

    auto set_res = lddm::Environment::set(test_key, test_val);
    EXPECT_TRUE(set_res.has_value());

    auto got = lddm::Environment::get(test_key);
    EXPECT_TRUE(got.has_value());
    if (got) {
        EXPECT_EQ(*got, test_val);
    }

    auto unset_res = lddm::Environment::unset(test_key);
    EXPECT_TRUE(unset_res.has_value());

    auto got_after = lddm::Environment::get(test_key);
    EXPECT_FALSE(got_after.has_value());
}

TEST_CASE(Platform_Paths) {
    EXPECT_FALSE(lddm::Paths::runtime_dir().empty());
    EXPECT_FALSE(lddm::Paths::config_dir().empty());
    EXPECT_FALSE(lddm::Paths::home_dir().empty());
}

TEST_CASE(Platform_SignalHandlerState) {
    EXPECT_FALSE(lddm::SignalHandler::is_termination_requested());
}

TEST_MAIN()

