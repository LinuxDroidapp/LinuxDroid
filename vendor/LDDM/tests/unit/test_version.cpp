#include "test_framework.hpp"
#include "lddm/version.hpp"

TEST_CASE(Version_Components) {
    EXPECT_EQ(lddm::Version::major(), 0u);
    EXPECT_EQ(lddm::Version::minor(), 1u);
    EXPECT_EQ(lddm::Version::patch(), 0u);
    EXPECT_EQ(lddm::Version::suffix(), "-alpha");
}

TEST_CASE(Version_String) {
    auto ver_str = lddm::Version::string();
    EXPECT_FALSE(ver_str.empty());
    EXPECT_EQ(ver_str, "0.1.0-alpha");
}

TEST_CASE(Version_InfoStruct) {
    auto info = lddm::Version::info();
    EXPECT_EQ(info.major, 0u);
    EXPECT_EQ(info.minor, 1u);
    EXPECT_EQ(info.patch, 0u);
    EXPECT_EQ(info.suffix, "-alpha");
    EXPECT_EQ(info.full_string, "0.1.0-alpha");
}

TEST_MAIN()

