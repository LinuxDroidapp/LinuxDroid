#include "test_framework.hpp"
#include "lddm/weston/weston_readiness.hpp"
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <filesystem>
#include <fstream>
#include <cstring>

using namespace lddm::weston;

TEST_CASE(WestonReadiness_SocketNotCreated) {
    std::string path = "/tmp/lddm_nonexistent_socket_test";
    std::filesystem::remove(path);

    auto status = WestonReadinessDetector::check_socket(path, getpid());
    EXPECT_EQ(status, WaylandSocketStatus::SocketNotCreated);
}

TEST_CASE(WestonReadiness_WestonExited) {
    std::string path = "/tmp/lddm_nonexistent_socket_test";
    // PID 999999 is dead
    auto status = WestonReadinessDetector::check_socket(path, 999999);
    EXPECT_EQ(status, WaylandSocketStatus::WestonExited);
}

TEST_CASE(WestonReadiness_SocketUnusableRegularFile) {
    std::string path = "/tmp/lddm_regular_file_socket_test";
    std::ofstream ofs(path);
    ofs << "not a socket";
    ofs.close();

    auto status = WestonReadinessDetector::check_socket(path, getpid());
    EXPECT_EQ(status, WaylandSocketStatus::SocketCreatedUnusable);

    std::filesystem::remove(path);
}

TEST_CASE(WestonReadiness_WaylandConnectionUsable) {
    std::string path = "/tmp/lddm_test_listening_socket";
    std::filesystem::remove(path);

    int sfd = socket(AF_UNIX, SOCK_STREAM, 0);
    EXPECT_TRUE(sfd >= 0);

    struct sockaddr_un addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, path.c_str(), sizeof(addr.sun_path) - 1);

    int bind_res = bind(sfd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr));
    EXPECT_EQ(bind_res, 0);

    int listen_res = listen(sfd, 5);
    EXPECT_EQ(listen_res, 0);

    auto status = WestonReadinessDetector::check_socket(path, getpid());
    EXPECT_EQ(status, WaylandSocketStatus::WaylandConnectionUsable);

    auto wait_res = WestonReadinessDetector::wait_for_readiness(
        path, getpid(), std::chrono::milliseconds(500), std::chrono::milliseconds(10));
    EXPECT_TRUE(wait_res.has_value());

    close(sfd);
    std::filesystem::remove(path);
}

TEST_CASE(WestonReadiness_WaitTimeout) {
    std::string path = "/tmp/lddm_timeout_socket_test";
    std::filesystem::remove(path);

    auto wait_res = WestonReadinessDetector::wait_for_readiness(
        path, getpid(), std::chrono::milliseconds(100), std::chrono::milliseconds(20));
    EXPECT_FALSE(wait_res.has_value());
    EXPECT_EQ(wait_res.error().code(), lddm::ErrorCode::CompositorTimeout);
}

TEST_MAIN()
