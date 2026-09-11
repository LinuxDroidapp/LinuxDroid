#include "test_framework.hpp"
#include "lddm/config/config_migrator.hpp"
#include "lddm/core/error.hpp"

#include <filesystem>
#include <fstream>

using namespace lddm;

TEST_CASE(ConfigMigration_DetectVersion) {
    // Legacy unversioned config (v0)
    std::string v0_cfg = R"(
[logging]
level = INFO
console = true
)";
    EXPECT_EQ(ConfigMigrator::detect_version(v0_cfg), 0u);

    // Version in [meta] section
    std::string v1_cfg = R"(
[meta]
version = 1

[logging]
level = DEBUG
)";
    EXPECT_EQ(ConfigMigrator::detect_version(v1_cfg), 1u);

    // Version in comment header
    std::string v2_comment_cfg = R"(
# version: 2
[logging]
level = WARN
)";
    EXPECT_EQ(ConfigMigrator::detect_version(v2_comment_cfg), 2u);
}

TEST_CASE(ConfigMigration_MigrateV0ToV1) {
    std::string v0_cfg = R"(
[logging]
level = INFO
file_path = /tmp/test.log

[session]
default_user = testuser
wayland_display = wayland-1
)";

    auto mig_res = ConfigMigrator::migrate_content(v0_cfg, 1);
    EXPECT_TRUE(mig_res.has_value());

    const auto& migrated = mig_res.value();
    EXPECT_EQ(ConfigMigrator::detect_version(migrated), 1u);

    // User configuration must be preserved
    EXPECT_NE(migrated.find("level = INFO"), std::string::npos);
    EXPECT_NE(migrated.find("file_path = /tmp/test.log"), std::string::npos);
    EXPECT_NE(migrated.find("default_user = testuser"), std::string::npos);
    EXPECT_NE(migrated.find("wayland_display = wayland-1"), std::string::npos);
    EXPECT_NE(migrated.find("version = 1"), std::string::npos);
}

TEST_CASE(ConfigMigration_IdempotentAlreadyV1) {
    std::string v1_cfg = R"(
[meta]
version = 1

[logging]
level = WARN
)";

    auto mig_res = ConfigMigrator::migrate_content(v1_cfg, 1);
    EXPECT_TRUE(mig_res.has_value());
    EXPECT_EQ(ConfigMigrator::detect_version(mig_res.value()), 1u);
}

TEST_CASE(ConfigMigration_RejectDowngradeAndFutureVersions) {
    std::string v2_cfg = R"(
[meta]
version = 2
)";

    // Downgrade from v2 to v1 must be rejected
    auto down_res = ConfigMigrator::migrate_content(v2_cfg, 1);
    EXPECT_FALSE(down_res.has_value());
    EXPECT_EQ(down_res.error().code(), ErrorCode::ConfigSchemaInvalid);

    // Target version exceeding CURRENT_CONFIG_VERSION must be rejected
    std::string v1_cfg = "[meta]\nversion = 1\n";
    auto future_res = ConfigMigrator::migrate_content(v1_cfg, 99);
    EXPECT_FALSE(future_res.has_value());
    EXPECT_EQ(future_res.error().code(), ErrorCode::ConfigSchemaInvalid);
}

TEST_CASE(ConfigMigration_FileMigration) {
    std::string test_dir = "/tmp/lddm_test_config_mig";
    std::filesystem::remove_all(test_dir);
    std::filesystem::create_directories(test_dir);

    std::string in_file = test_dir + "/lddm.conf";
    {
        std::ofstream out(in_file);
        out << "[session]\ndefault_user = alice\n";
    }

    std::string out_file = test_dir + "/lddm_migrated.conf";
    auto mig_res = ConfigMigrator::migrate_file(in_file, out_file, 1);
    EXPECT_TRUE(mig_res.has_value());

    EXPECT_TRUE(std::filesystem::exists(out_file));
    std::ifstream in(out_file);
    std::stringstream buf;
    buf << in.rdbuf();
    std::string content = buf.str();

    EXPECT_EQ(ConfigMigrator::detect_version(content), 1u);
    EXPECT_NE(content.find("default_user = alice"), std::string::npos);

    // Nonexistent file should fail cleanly
    auto fail_res = ConfigMigrator::migrate_file(test_dir + "/nonexistent.conf");
    EXPECT_FALSE(fail_res.has_value());
    EXPECT_EQ(fail_res.error().code(), ErrorCode::ConfigFileNotFound);

    std::filesystem::remove_all(test_dir);
}

TEST_MAIN()
