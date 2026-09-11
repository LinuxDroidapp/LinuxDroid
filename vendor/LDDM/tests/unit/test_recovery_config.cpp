#include "test_framework.hpp"
#include "lddm/recovery/recovery_config.hpp"

using namespace lddm::recovery;

TEST_CASE(RecoveryConfig_Defaults) {
    RecoveryConfig cfg = RecoveryConfig::create_default();
    EXPECT_TRUE(cfg.enabled);
    EXPECT_EQ(cfg.max_attempts, 3u);
    EXPECT_EQ(cfg.window_ms, 60000u);
    EXPECT_EQ(cfg.backoff_initial_ms, 50u);
    EXPECT_EQ(cfg.backoff_multiplier, 2.0);
    EXPECT_EQ(cfg.backoff_max_ms, 1000u);
    EXPECT_TRUE(cfg.allow_component_restart);
    EXPECT_TRUE(cfg.allow_session_restart);
    EXPECT_TRUE(cfg.validate().has_value());
}

TEST_CASE(RecoveryConfig_Validation) {
    {
        RecoveryConfig cfg;
        cfg.max_attempts = 0;
        EXPECT_FALSE(cfg.validate().has_value());
    }
    {
        RecoveryConfig cfg;
        cfg.window_ms = 50;
        EXPECT_FALSE(cfg.validate().has_value());
    }
    {
        RecoveryConfig cfg;
        cfg.backoff_multiplier = 0.5;
        EXPECT_FALSE(cfg.validate().has_value());
    }
    {
        RecoveryConfig cfg;
        cfg.backoff_initial_ms = 2000;
        cfg.backoff_max_ms = 500;
        EXPECT_FALSE(cfg.validate().has_value());
    }
}

TEST_CASE(RecoveryConfig_BackoffCalculation) {
    RecoveryConfig cfg;
    cfg.backoff_initial_ms = 100;
    cfg.backoff_multiplier = 2.0;
    cfg.backoff_max_ms = 1000;

    EXPECT_EQ(cfg.calculate_backoff(0).count(), 0);
    EXPECT_EQ(cfg.calculate_backoff(1).count(), 100);
    EXPECT_EQ(cfg.calculate_backoff(2).count(), 200);
    EXPECT_EQ(cfg.calculate_backoff(3).count(), 400);
    EXPECT_EQ(cfg.calculate_backoff(4).count(), 800);
    EXPECT_EQ(cfg.calculate_backoff(5).count(), 1000); // capped at backoff_max_ms
    EXPECT_EQ(cfg.calculate_backoff(10).count(), 1000);
}

TEST_MAIN()
