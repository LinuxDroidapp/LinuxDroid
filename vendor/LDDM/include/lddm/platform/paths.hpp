#pragma once

#include "lddm/core/result.hpp"
#include <string>
#include <filesystem>

namespace lddm {

class Paths {
public:
    [[nodiscard]] static std::string runtime_dir();
    [[nodiscard]] static std::string config_dir();
    [[nodiscard]] static std::string log_dir();
    [[nodiscard]] static std::string home_dir();

    static Result<void> ensure_directory(const std::filesystem::path& path);
};

} // namespace lddm

