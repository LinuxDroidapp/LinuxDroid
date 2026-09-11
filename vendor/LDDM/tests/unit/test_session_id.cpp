#include "test_framework.hpp"
#include "lddm/session/session_id.hpp"
#include <unordered_set>
#include <sstream>

TEST_CASE(SessionId_CustomString) {
    lddm::SessionId id("custom-session-123");
    EXPECT_EQ(id.str(), "custom-session-123");
    EXPECT_EQ(id.view(), "custom-session-123");
    EXPECT_FALSE(id.empty());
}

TEST_CASE(SessionId_UniqueGeneration) {
    auto id1 = lddm::SessionId::generate();
    auto id2 = lddm::SessionId::generate();
    auto id3 = lddm::SessionId::generate("custom");

    EXPECT_FALSE(id1.empty());
    EXPECT_FALSE(id2.empty());
    EXPECT_NE(id1, id2);
    EXPECT_TRUE(id3.str().find("custom-") == 0);
}

TEST_CASE(SessionId_ComparisonAndHashing) {
    lddm::SessionId a("session-a");
    lddm::SessionId b("session-a");
    lddm::SessionId c("session-b");

    EXPECT_EQ(a, b);
    EXPECT_NE(a, c);

    std::unordered_set<lddm::SessionId> set;
    set.insert(a);
    set.insert(b);
    set.insert(c);

    EXPECT_EQ(set.size(), 2u);
    EXPECT_TRUE(set.find(a) != set.end());
    EXPECT_TRUE(set.find(c) != set.end());
}

TEST_CASE(SessionId_StreamOutput) {
    lddm::SessionId id("stream-test-id");
    std::ostringstream oss;
    oss << id;
    EXPECT_EQ(oss.str(), "stream-test-id");
}

TEST_MAIN()

