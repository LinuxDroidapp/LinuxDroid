#include "lddm/platform/environment.hpp"
#include <cstdlib>
#include <cerrno>
#include <cstring>

extern char** environ;

namespace lddm {

std::optional<std::string> Environment::get(std::string_view key) {
    std::string key_str(key);
    const char* val = ::getenv(key_str.c_str());
    if (val != nullptr) {
        return std::string(val);
    }
    return std::nullopt;
}

Result<void> Environment::set(std::string_view key, std::string_view value, bool overwrite) {
    std::string key_str(key);
    std::string val_str(value);
    if (::setenv(key_str.c_str(), val_str.c_str(), overwrite ? 1 : 0) != 0) {
        return Result<void>::failure(Error(
            ErrorCategory::Platform,
            ErrorCode::PlatformSyscallFailed,
            "setenv failed: " + std::string(std::strerror(errno))));
    }
    return Result<void>::success();
}

Result<void> Environment::unset(std::string_view key) {
    std::string key_str(key);
    if (::unsetenv(key_str.c_str()) != 0) {
        return Result<void>::failure(Error(
            ErrorCategory::Platform,
            ErrorCode::PlatformSyscallFailed,
            "unsetenv failed: " + std::string(std::strerror(errno))));
    }
    return Result<void>::success();
}

std::unordered_map<std::string, std::string> Environment::snapshot() {
    std::unordered_map<std::string, std::string> env_map;
    if (environ == nullptr) {
        return env_map;
    }

    for (char** current = environ; *current != nullptr; ++current) {
        std::string entry(*current);
        auto eq_pos = entry.find('=');
        if (eq_pos != std::string::npos) {
            std::string k = entry.substr(0, eq_pos);
            std::string v = entry.substr(eq_pos + 1);
            env_map[std::move(k)] = std::move(v);
        }
    }

    return env_map;
}

} // namespace lddm

