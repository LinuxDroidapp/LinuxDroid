#pragma once

#include "lddm/session/session_id.hpp"
#include "lddm/session/session_config.hpp"
#include "lddm/session/session_paths.hpp"
#include "lddm/session/session_environment.hpp"
#include "lddm/session/session_state.hpp"

#include <memory>

namespace lddm {

class ProcessSupervisor;

struct SessionContext {
    const SessionIdentity& identity;
    const SessionConfig& config;
    const SessionPaths& paths;
    const SessionEnvironment& environment;
    SessionState state;
    std::shared_ptr<ProcessSupervisor> supervisor{nullptr};
};

} // namespace lddm

