#include "test_framework.hpp"
#include "lddm/session/session_paths.hpp"
#include <sys/stat.h>

TEST_CASE(SessionPaths_PathResolution) {
    lddm::SessionId id("sess-path-test");
    std::filesystem::path base_dir = "/tmp/lddm_unit_tests";
    lddm::SessionPaths paths(id, base_dir, "wayland-5");

    EXPECT_EQ(paths.session_root(), base_dir / "sess-path-test");
    EXPECT_EQ(paths.runtime_dir(), base_dir / "sess-path-test" / "run");
    EXPECT_EQ(paths.wayland_socket_path(), base_dir / "sess-path-test" / "run" / "wayland-5");
    EXPECT_EQ(paths.ipc_socket_path(), base_dir / "sess-path-test" / "run" / "session.sock");
    EXPECT_EQ(paths.wayland_socket_name(), "wayland-5");
}

TEST_CASE(SessionPaths_CreateAndRemoveDirectories) {
    lddm::SessionId id("sess-dir-test");
    std::filesystem::path base_dir = "/tmp/lddm_unit_tests";
    lddm::SessionPaths paths(id, base_dir);

    // Initial state: does not exist
    (void)paths.remove_directories();
    EXPECT_FALSE(paths.exists());

    // Create directories
    auto create_res = paths.create_directories();
    EXPECT_TRUE(create_res.has_value());
    EXPECT_TRUE(paths.exists());
    EXPECT_TRUE(std::filesystem::exists(paths.runtime_dir()));
    EXPECT_TRUE(std::filesystem::exists(paths.state_dir()));
    EXPECT_TRUE(std::filesystem::exists(paths.log_dir()));
    EXPECT_TRUE(std::filesystem::exists(paths.tmp_dir()));

    // Verify 0700 permissions
    struct stat st{};
    EXPECT_EQ(::stat(paths.runtime_dir().c_str(), &st), 0);
    EXPECT_EQ(st.st_mode & 0777, 0700);

    // Idempotent creation
    EXPECT_TRUE(paths.create_directories().has_value());

    // Remove directories
    auto remove_res = paths.remove_directories();
    EXPECT_TRUE(remove_res.has_value());
    EXPECT_FALSE(paths.exists());

    // Idempotent removal
    EXPECT_TRUE(paths.remove_directories().has_value());
}

TEST_MAIN()

