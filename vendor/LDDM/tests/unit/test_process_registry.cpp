#include "test_framework.hpp"
#include "lddm/process/process_registry.hpp"
#include "lddm/core/error.hpp"

TEST_CASE(ProcessRegistry_RegisterAndLookup) {
    lddm::ProcessRegistry registry;

    lddm::ProcessSpec spec1{.name = "proc1", .executable = "/bin/true"};
    lddm::ProcessSpec spec2{.name = "proc2", .executable = "/bin/false"};

    auto p1 = std::make_shared<lddm::Process>(spec1, "handle-1");
    auto p2 = std::make_shared<lddm::Process>(spec2, "handle-2");

    auto r1 = registry.register_process(p1);
    EXPECT_TRUE(r1.has_value());
    auto r2 = registry.register_process(p2);
    EXPECT_TRUE(r2.has_value());

    EXPECT_EQ(registry.count(), 2);

    auto found1 = registry.find_by_handle("handle-1");
    EXPECT_TRUE(found1 != nullptr);
    if (found1) {
        EXPECT_EQ(found1->name(), "proc1");
    }

    auto found2 = registry.find_by_handle("handle-2");
    EXPECT_TRUE(found2 != nullptr);
    if (found2) {
        EXPECT_EQ(found2->name(), "proc2");
    }

    auto not_found = registry.find_by_handle("non-existent");
    EXPECT_TRUE(not_found == nullptr);
}

TEST_CASE(ProcessRegistry_DuplicateRejected) {
    lddm::ProcessRegistry registry;

    lddm::ProcessSpec spec{.name = "proc", .executable = "/bin/true"};
    auto p1 = std::make_shared<lddm::Process>(spec, "same-handle");
    auto p2 = std::make_shared<lddm::Process>(spec, "same-handle");

    auto r1 = registry.register_process(p1);
    EXPECT_TRUE(r1.has_value());

    auto r2 = registry.register_process(p2);
    EXPECT_FALSE(r2.has_value());
    EXPECT_EQ(r2.error().code(), lddm::ErrorCode::ProcessRegistrationFailed);
}

TEST_CASE(ProcessRegistry_RemoveAndClear) {
    lddm::ProcessRegistry registry;

    lddm::ProcessSpec spec1{.name = "proc1", .executable = "/bin/true"};
    lddm::ProcessSpec spec2{.name = "proc2", .executable = "/bin/false"};

    auto p1 = std::make_shared<lddm::Process>(spec1, "handle-1");
    auto p2 = std::make_shared<lddm::Process>(spec2, "handle-2");

    (void)registry.register_process(p1);
    (void)registry.register_process(p2);

    auto rem_res = registry.remove_process("handle-1");
    EXPECT_TRUE(rem_res.has_value());
    EXPECT_EQ(registry.count(), 1);
    EXPECT_TRUE(registry.find_by_handle("handle-1") == nullptr);

    registry.clear();
    EXPECT_EQ(registry.count(), 0);
}

TEST_MAIN()

