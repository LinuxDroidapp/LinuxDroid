#pragma once

#include "lddm/core/result.hpp"
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>

namespace lddm {

class Environment {
public:
    [[nodiscard]] static std::optional<std::string> get(std::string_view key);
    static Result<void> set(std::string_view key, std::string_view value, bool overwrite = true);
    static Result<void> unset(std::string_view key);
    [[nodiscard]] static std::unordered_map<std::string, std::string> snapshot();
};

} // namespace lddm

