#pragma once

#include "lddm/core/result.hpp"
#include <string>

namespace lddm {

struct SessionContext;

class ISessionComponent {
public:
    virtual ~ISessionComponent() = default;

    [[nodiscard]] virtual const std::string& name() const noexcept = 0;
    [[nodiscard]] virtual bool is_running() const noexcept = 0;

    virtual Result<void> initialize(const SessionContext& context) {
        (void)context;
        return Result<void>::success();
    }

    virtual Result<void> start() = 0;
    virtual Result<void> stop() = 0;
};

class ICompositorInstance : public ISessionComponent {
public:
    ~ICompositorInstance() override = default;

    [[nodiscard]] virtual const std::string& socket_path() const noexcept = 0;
};

class IDesktopEnvironmentInstance : public ISessionComponent {
public:
    ~IDesktopEnvironmentInstance() override = default;
};

} // namespace lddm
