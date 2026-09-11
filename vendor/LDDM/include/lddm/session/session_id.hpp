#pragma once

#include "lddm/core/types.hpp"
#include <string>
#include <string_view>
#include <compare>
#include <iosfwd>
#include <functional>

namespace lddm {

class SessionId {
public:
    SessionId() = default;
    explicit SessionId(std::string id);

    [[nodiscard]] const std::string& str() const noexcept { return id_; }
    [[nodiscard]] std::string_view view() const noexcept { return id_; }
    [[nodiscard]] bool empty() const noexcept { return id_.empty(); }

    [[nodiscard]] static SessionId generate(std::string_view prefix = "session");

    auto operator<=>(const SessionId& other) const = default;
    bool operator==(const SessionId& other) const = default;

private:
    std::string id_{};
};

std::ostream& operator<<(std::ostream& os, const SessionId& session_id);

enum class SessionType : std::uint8_t;

struct SessionIdentity {
    SessionId id{};
    std::uint32_t type{0}; // Cast from SessionType
    UserId user_id{0};
    GroupId group_id{0};
    std::string username{"root"};
    SystemTimePoint created_at{SystemClock::now()};
};

} // namespace lddm

namespace std {
template <>
struct hash<lddm::SessionId> {
    std::size_t operator()(const lddm::SessionId& id) const noexcept {
        return std::hash<std::string>{}(id.str());
    }
};
} // namespace std

