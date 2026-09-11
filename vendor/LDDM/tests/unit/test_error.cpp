#include "test_framework.hpp"
#include "lddm/core/error.hpp"
#include "lddm/core/result.hpp"
#include <sstream>

TEST_CASE(Error_CategoriesToString) {
    EXPECT_EQ(lddm::to_string(lddm::ErrorCategory::Configuration), "Configuration");
    EXPECT_EQ(lddm::to_string(lddm::ErrorCategory::Session), "Session");
    EXPECT_EQ(lddm::to_string(lddm::ErrorCategory::Process), "Process");
    EXPECT_EQ(lddm::to_string(lddm::ErrorCategory::Platform), "Platform");
    EXPECT_EQ(lddm::to_string(lddm::ErrorCategory::Compositor), "Compositor");
    EXPECT_EQ(lddm::to_string(lddm::ErrorCategory::Desktop), "Desktop");
    EXPECT_EQ(lddm::to_string(lddm::ErrorCategory::Resource), "Resource");
    EXPECT_EQ(lddm::to_string(lddm::ErrorCategory::Internal), "Internal");
}

TEST_CASE(Error_CodesToString) {
    EXPECT_EQ(lddm::to_string(lddm::ErrorCode::Success), "Success");
    EXPECT_EQ(lddm::to_string(lddm::ErrorCode::ConfigFileNotFound), "ConfigFileNotFound");
    EXPECT_EQ(lddm::to_string(lddm::ErrorCode::SessionAlreadyActive), "SessionAlreadyActive");
    EXPECT_EQ(lddm::to_string(lddm::ErrorCode::ProcessSpawnFailed), "ProcessSpawnFailed");
    EXPECT_EQ(lddm::to_string(lddm::ErrorCode::PlatformFdError), "PlatformFdError");
}

TEST_CASE(Error_ConstructionAndProperties) {
    lddm::Error err(lddm::ErrorCategory::Configuration,
                    lddm::ErrorCode::ConfigValidationFailed,
                    "Invalid timeout value",
                    "section [process]");

    EXPECT_EQ(err.category(), lddm::ErrorCategory::Configuration);
    EXPECT_EQ(err.code(), lddm::ErrorCode::ConfigValidationFailed);
    EXPECT_EQ(err.message(), "Invalid timeout value");
    EXPECT_EQ(err.context(), "section [process]");
    EXPECT_FALSE(err.is_ok());
    EXPECT_TRUE(static_cast<bool>(err));
    EXPECT_FALSE(err.file_name().empty());
    EXPECT_NE(err.line(), 0u);

    std::ostringstream oss;
    oss << err;
    std::string out = oss.str();
    EXPECT_TRUE(out.find("ConfigValidationFailed") != std::string::npos);
    EXPECT_TRUE(out.find("Invalid timeout value") != std::string::npos);
}

TEST_CASE(Result_ValueSuccess) {
    lddm::Result<int> res = lddm::Result<int>::success(42);
    EXPECT_TRUE(res.has_value());
    EXPECT_TRUE(res.is_ok());
    EXPECT_FALSE(res.is_error());
    EXPECT_EQ(res.value(), 42);
    EXPECT_EQ(*res, 42);
    EXPECT_EQ(res.value_or(100), 42);
}

TEST_CASE(Result_ValueFailure) {
    lddm::Error err(lddm::ErrorCategory::Platform, lddm::ErrorCode::PlatformClockError, "clock failure");
    lddm::Result<int> res = lddm::Result<int>::failure(err);

    EXPECT_FALSE(res.has_value());
    EXPECT_FALSE(res.is_ok());
    EXPECT_TRUE(res.is_error());
    EXPECT_EQ(res.error().code(), lddm::ErrorCode::PlatformClockError);
    EXPECT_EQ(res.value_or(100), 100);
    EXPECT_THROW(res.value(), std::runtime_error);
}

TEST_CASE(Result_VoidSuccessAndFailure) {
    auto ok_res = lddm::Result<void>::success();
    EXPECT_TRUE(ok_res.has_value());
    EXPECT_TRUE(ok_res.is_ok());
    EXPECT_FALSE(ok_res.is_error());
    EXPECT_NO_THROW(ok_res.value());

    lddm::Error err(lddm::ErrorCategory::Internal, lddm::ErrorCode::InternalLogicError, "internal");
    auto err_res = lddm::Result<void>::failure(err);
    EXPECT_FALSE(err_res.has_value());
    EXPECT_TRUE(err_res.is_error());
    EXPECT_EQ(err_res.error().code(), lddm::ErrorCode::InternalLogicError);
    EXPECT_THROW(err_res.value(), std::runtime_error);
}

TEST_MAIN()

