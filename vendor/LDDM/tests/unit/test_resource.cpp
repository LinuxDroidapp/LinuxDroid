#include "test_framework.hpp"
#include "lddm/platform/unique_fd.hpp"
#include <unistd.h>
#include <fcntl.h>

TEST_CASE(Resource_UniqueFdBasics) {
    lddm::UniqueFd fd_invalid;
    EXPECT_FALSE(fd_invalid.is_valid());
    EXPECT_EQ(fd_invalid.get(), -1);
    EXPECT_FALSE(static_cast<bool>(fd_invalid));
}

TEST_CASE(Resource_UniqueFdPipeAndMove) {
    auto pipe_res = lddm::UniqueFd::create_pipe(true, true);
    EXPECT_TRUE(pipe_res.has_value());

    auto [reader, writer] = std::move(pipe_res.value());
    EXPECT_TRUE(reader.is_valid());
    EXPECT_TRUE(writer.is_valid());

    int r_raw = reader.get();
    EXPECT_TRUE(r_raw >= 0);

    // Move construct
    lddm::UniqueFd moved_reader(std::move(reader));
    EXPECT_FALSE(reader.is_valid());
    EXPECT_EQ(reader.get(), -1);
    EXPECT_TRUE(moved_reader.is_valid());
    EXPECT_EQ(moved_reader.get(), r_raw);

    // Move assign
    lddm::UniqueFd assigned_reader;
    assigned_reader = std::move(moved_reader);
    EXPECT_FALSE(moved_reader.is_valid());
    EXPECT_TRUE(assigned_reader.is_valid());
    EXPECT_EQ(assigned_reader.get(), r_raw);

    // Release
    int raw_released = assigned_reader.release();
    EXPECT_EQ(raw_released, r_raw);
    EXPECT_FALSE(assigned_reader.is_valid());
    ::close(raw_released);
}

TEST_CASE(Resource_UniqueFdFlags) {
    auto pipe_res = lddm::UniqueFd::create_pipe(false, false);
    EXPECT_TRUE(pipe_res.has_value());

    auto [reader, writer] = std::move(pipe_res.value());
    EXPECT_TRUE(reader.set_close_on_exec(true).has_value());
    EXPECT_TRUE(reader.set_nonblocking(true).has_value());

    int fl = ::fcntl(reader.get(), F_GETFL);
    EXPECT_TRUE((fl & O_NONBLOCK) != 0);

    int fd_flags = ::fcntl(reader.get(), F_GETFD);
    EXPECT_TRUE((fd_flags & FD_CLOEXEC) != 0);
}

TEST_CASE(Resource_DeterministicCleanupOnFailurePath) {
    bool cleaned_up = false;
    struct ResourceGuard {
        bool* flag;
        ~ResourceGuard() { *flag = true; }
    };

    auto failing_operation = [&]() -> lddm::Result<void> {
        ResourceGuard guard{&cleaned_up};
        return lddm::Result<void>::failure(lddm::Error(
            lddm::ErrorCategory::Resource,
            lddm::ErrorCode::ResourceAllocationFailed,
            "Simulated resource failure"));
    };

    auto res = failing_operation();
    EXPECT_TRUE(res.is_error());
    EXPECT_TRUE(cleaned_up);
}

TEST_MAIN()

