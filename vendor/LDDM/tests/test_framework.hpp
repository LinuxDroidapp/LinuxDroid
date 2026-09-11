#pragma once

#include <iostream>
#include <vector>
#include <string>
#include <functional>
#include <sstream>
#include <source_location>
#include <optional>
#include <type_traits>

namespace lddm::test {

template <typename T>
struct is_optional : std::false_type {};

template <typename T>
struct is_optional<std::optional<T>> : std::true_type {};

template <typename T>
std::string to_printable(const T& val) {
    if constexpr (is_optional<T>::value) {
        if (val.has_value()) {
            return "optional(" + to_printable(*val) + ")";
        }
        return "nullopt";
    } else if constexpr (requires { std::ostringstream() << val; }) {
        std::ostringstream oss;
        oss << val;
        return oss.str();
    } else {
        return "<unprintable>";
    }
}

struct TestCase {
    std::string name;
    std::function<void()> func;
};

class TestRegistry {
public:
    static TestRegistry& instance() {
        static TestRegistry reg;
        return reg;
    }

    void register_test(std::string name, std::function<void()> func) {
        tests_.push_back({std::move(name), std::move(func)});
    }

    int run_all() {
        std::size_t passed = 0;
        std::size_t failed = 0;

        std::cout << "[==========] Running " << tests_.size() << " test(s).\n";

        for (const auto& test : tests_) {
            std::cout << "[ RUN      ] " << test.name << "\n";
            current_test_failed_ = false;
            current_test_name_ = test.name;

            try {
                test.func();
            } catch (const std::exception& ex) {
                std::cerr << "  [EXCEPTION] Caught unhandled exception: " << ex.what() << "\n";
                current_test_failed_ = true;
            } catch (...) {
                std::cerr << "  [EXCEPTION] Caught unknown exception.\n";
                current_test_failed_ = true;
            }

            if (current_test_failed_) {
                std::cout << "[  FAILED  ] " << test.name << "\n";
                ++failed;
            } else {
                std::cout << "[       OK ] " << test.name << "\n";
                ++passed;
            }
        }

        std::cout << "[==========] " << tests_.size() << " test(s) run.\n";
        std::cout << "[  PASSED  ] " << passed << " test(s).\n";
        if (failed > 0) {
            std::cout << "[  FAILED  ] " << failed << " test(s).\n";
            return 1;
        }
        return 0;
    }

    void record_failure(const std::string& message, const std::source_location& loc) {
        current_test_failed_ = true;
        std::cerr << "  [FAILURE] " << loc.file_name() << ":" << loc.line()
                  << " in " << loc.function_name() << "(): " << message << "\n";
    }

private:
    std::vector<TestCase> tests_;
    bool current_test_failed_{false};
    std::string current_test_name_{};
};

struct AutoTestRegister {
    AutoTestRegister(std::string name, std::function<void()> func) {
        TestRegistry::instance().register_test(std::move(name), std::move(func));
    }
};

} // namespace lddm::test

#define TEST_CASE(name) \
    static void test_func_##name(); \
    static ::lddm::test::AutoTestRegister reg_##name(#name, &test_func_##name); \
    static void test_func_##name()

#define EXPECT_TRUE(cond) \
    do { \
        if (!(cond)) { \
            ::lddm::test::TestRegistry::instance().record_failure("Expected true: (" #cond ")", std::source_location::current()); \
        } \
    } while(0)

#define EXPECT_FALSE(cond) \
    do { \
        if ((cond)) { \
            ::lddm::test::TestRegistry::instance().record_failure("Expected false: (" #cond ")", std::source_location::current()); \
        } \
    } while(0)

#define EXPECT_EQ(a, b) \
    do { \
        if (!((a) == (b))) { \
            std::ostringstream _oss; \
            _oss << "Expected equality: (" #a " == " #b ") [" << ::lddm::test::to_printable(a) << " != " << ::lddm::test::to_printable(b) << "]"; \
            ::lddm::test::TestRegistry::instance().record_failure(_oss.str(), std::source_location::current()); \
        } \
    } while(0)

#define EXPECT_NE(a, b) \
    do { \
        if ((a) == (b)) { \
            std::ostringstream _oss; \
            _oss << "Expected inequality: (" #a " != " #b ") [" << ::lddm::test::to_printable(a) << " == " << ::lddm::test::to_printable(b) << "]"; \
            ::lddm::test::TestRegistry::instance().record_failure(_oss.str(), std::source_location::current()); \
        } \
    } while(0)

#define EXPECT_THROW(expr, ExceptionType) \
    do { \
        bool _threw = false; \
        try { \
            static_cast<void>(expr); \
        } catch (const ExceptionType&) { \
            _threw = true; \
        } catch (...) { \
            ::lddm::test::TestRegistry::instance().record_failure("Expected " #ExceptionType ", but caught different exception for: " #expr, std::source_location::current()); \
            _threw = true; \
        } \
        if (!_threw) { \
            ::lddm::test::TestRegistry::instance().record_failure("Expected exception " #ExceptionType " not thrown for: " #expr, std::source_location::current()); \
        } \
    } while(0)

#define EXPECT_NO_THROW(expr) \
    do { \
        try { \
            static_cast<void>(expr); \
        } catch (const std::exception& _ex) { \
            ::lddm::test::TestRegistry::instance().record_failure(std::string("Expected no exception, but caught: ") + _ex.what() + " for: " #expr, std::source_location::current()); \
        } catch (...) { \
            ::lddm::test::TestRegistry::instance().record_failure("Expected no exception, but caught unknown exception for: " #expr, std::source_location::current()); \
        } \
    } while(0)

#define TEST_MAIN() \
    int main() { \
        return ::lddm::test::TestRegistry::instance().run_all(); \
    }
