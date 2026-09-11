#include "test_framework.hpp"
#include "lddm/ldde/ldde_spec.hpp"

using namespace lddm::ldde;

TEST_CASE(LddeSpec_Validation) {
    LddeSpec spec;
    spec.executable = "";
    spec.readiness_path = "/tmp/ready";
    EXPECT_FALSE(spec.validate().has_value());

    spec.executable = "/usr/bin/ldde-session";
    spec.readiness_path = "";
    EXPECT_FALSE(spec.validate().has_value());

    spec.readiness_path = "/tmp/ready";
    spec.working_directory = "/tmp";
    EXPECT_TRUE(spec.validate().has_value());

    spec.working_directory = "/nonexistent/invalid/dir/12345";
    EXPECT_FALSE(spec.validate().has_value());
}

TEST_CASE(LddeSpec_ArgumentBuilder) {
    LddeConfig config;
    config.session_target = "kiosk";
    config.additional_args = {"--debug", "--no-lock"};

    auto args = LddeSpec::build_arguments(config);
    EXPECT_EQ(args.size(), 3);
    EXPECT_EQ(args[0], "--session=kiosk");
    EXPECT_EQ(args[1], "--debug");
    EXPECT_EQ(args[2], "--no-lock");
}

TEST_CASE(LddeSpec_ToProcessSpec) {
    LddeSpec spec;
    spec.executable = "/usr/bin/ldde-session";
    spec.arguments = {"--session=default"};
    spec.working_directory = "/tmp";
    spec.readiness_path = "/tmp/ready";
    spec.environment = {{"FOO", "BAR"}};
    spec.shutdown_timeout = std::chrono::milliseconds(4000);

    auto ps = spec.to_process_spec();
    EXPECT_EQ(ps.name, "ldde");
    EXPECT_EQ(ps.executable, "/usr/bin/ldde-session");
    EXPECT_EQ(ps.arguments.size(), 1);
    EXPECT_EQ(ps.arguments[0], "--session=default");
    EXPECT_EQ(ps.working_directory, "/tmp");
    EXPECT_TRUE(ps.enable_process_group);
    EXPECT_EQ(ps.stop_timeout, std::chrono::milliseconds(4000));
    EXPECT_EQ(ps.environment.at("FOO"), "BAR");
}

TEST_MAIN()
