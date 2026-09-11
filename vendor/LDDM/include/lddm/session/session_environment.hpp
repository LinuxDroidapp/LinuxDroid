#pragma once

#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>
#include <optional>

namespace lddm {

class SessionPaths;
struct SessionConfig;

class SessionEnvironment {
public:
    SessionEnvironment();

    [[nodiscard]] std::optional<std::string> get(std::string_view key) const;
    void set(std::string key, std::string value);
    void unset(std::string_view key);
    [[nodiscard]] bool has(std::string_view key) const noexcept;

    void merge(const std::unordered_map<std::string, std::string>& overrides);

    void populate_session_defaults(const SessionConfig& config, const SessionPaths& paths);

    [[nodiscard]] const std::unordered_map<std::string, std::string>& variables() const noexcept {
        return variables_;
    }

    [[nodiscard]] std::vector<std::string> to_vector() const;
    [[nodiscard]] std::unordered_map<std::string, std::string> redacted_summary() const;

    [[nodiscard]] static bool is_sensitive_key(std::string_view key) noexcept;

private:
    std::unordered_map<std::string, std::string> variables_;
};

} // namespace lddm

