#pragma once

#include "lddm/core/result.hpp"
#include "lddm/ldde/ldde_types.hpp"
#include <string>
#include <vector>
#include <unordered_map>
#include <cstdint>

namespace lddm::ldde {

struct LddeConfig {
    std::string executable{"/usr/bin/ldde-session"};
    std::string session_target{"default"};
    bool autostart{true};

    std::uint32_t startup_timeout_ms{10000};
    std::uint32_t stop_timeout_ms{5000};
    std::uint32_t readiness_timeout_ms{10000};

    LddeReadinessMode readiness_mode{LddeReadinessMode::File};
    std::string readiness_file_name{"ldde-session.ready"};
    std::string readiness_socket_name{"ldde-session.ready.sock"};

    std::uint32_t contract_version{1};

    std::vector<std::string> additional_args{};
    std::unordered_map<std::string, std::string> environment_overrides{};

    [[nodiscard]] static LddeConfig create_default();
    [[nodiscard]] Result<void> validate() const;
};

class LddeExecutableResolver {
public:
    [[nodiscard]] static Result<std::string> resolve(const std::string& configured_path);
};

} // namespace lddm::ldde
