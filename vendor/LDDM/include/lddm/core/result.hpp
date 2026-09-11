#pragma once

#include "lddm/core/error.hpp"
#include <variant>
#include <utility>
#include <stdexcept>

namespace lddm {

template <typename T, typename E = Error>
class Result {
public:
    using value_type = T;
    using error_type = E;

    constexpr Result(const T& val) : storage_(val) {}
    constexpr Result(T&& val) : storage_(std::move(val)) {}
    constexpr Result(const E& err) : storage_(err) {}
    constexpr Result(E&& err) : storage_(std::move(err)) {}

    static Result success(T value) {
        return Result(std::move(value));
    }

    static Result failure(E error) {
        return Result(std::move(error));
    }

    [[nodiscard]] constexpr bool has_value() const noexcept {
        return std::holds_alternative<T>(storage_);
    }

    [[nodiscard]] constexpr bool is_ok() const noexcept {
        return has_value();
    }

    [[nodiscard]] constexpr bool is_error() const noexcept {
        return !has_value();
    }

    [[nodiscard]] explicit constexpr operator bool() const noexcept {
        return has_value();
    }

    [[nodiscard]] constexpr const T& value() const& {
        if (!has_value()) {
            throw std::runtime_error("Attempted to access value on error Result");
        }
        return std::get<T>(storage_);
    }

    [[nodiscard]] constexpr T& value() & {
        if (!has_value()) {
            throw std::runtime_error("Attempted to access value on error Result");
        }
        return std::get<T>(storage_);
    }

    [[nodiscard]] constexpr T&& value() && {
        if (!has_value()) {
            throw std::runtime_error("Attempted to access value on error Result");
        }
        return std::get<T>(std::move(storage_));
    }

    [[nodiscard]] constexpr const T& operator*() const& { return value(); }
    [[nodiscard]] constexpr T& operator*() & { return value(); }
    [[nodiscard]] constexpr const T* operator->() const { return &value(); }
    [[nodiscard]] constexpr T* operator->() { return &value(); }

    [[nodiscard]] constexpr const E& error() const& {
        if (has_value()) {
            throw std::runtime_error("Attempted to access error on success Result");
        }
        return std::get<E>(storage_);
    }

    [[nodiscard]] constexpr E& error() & {
        if (has_value()) {
            throw std::runtime_error("Attempted to access error on success Result");
        }
        return std::get<E>(storage_);
    }

    [[nodiscard]] constexpr E&& error() && {
        if (has_value()) {
            throw std::runtime_error("Attempted to access error on success Result");
        }
        return std::get<E>(std::move(storage_));
    }

    template <typename U>
    [[nodiscard]] T value_or(U&& default_value) const& {
        return has_value() ? std::get<T>(storage_) : static_cast<T>(std::forward<U>(default_value));
    }

    template <typename U>
    [[nodiscard]] T value_or(U&& default_value) && {
        return has_value() ? std::get<T>(std::move(storage_)) : static_cast<T>(std::forward<U>(default_value));
    }

private:
    std::variant<T, E> storage_;
};

// Specialization for void
template <typename E>
class Result<void, E> {
public:
    using value_type = void;
    using error_type = E;

    constexpr Result() noexcept : error_(std::nullopt) {}
    constexpr Result(const E& err) : error_(err) {}
    constexpr Result(E&& err) : error_(std::move(err)) {}

    static Result success() noexcept {
        return Result();
    }

    static Result failure(E error) {
        return Result(std::move(error));
    }

    [[nodiscard]] constexpr bool has_value() const noexcept {
        return !error_.has_value();
    }

    [[nodiscard]] constexpr bool is_ok() const noexcept {
        return !error_.has_value();
    }

    [[nodiscard]] constexpr bool is_error() const noexcept {
        return error_.has_value();
    }

    [[nodiscard]] explicit constexpr operator bool() const noexcept {
        return !error_.has_value();
    }

    void value() const {
        if (is_error()) {
            throw std::runtime_error("Attempted to access value on error Result<void>");
        }
    }

    [[nodiscard]] constexpr const E& error() const& {
        if (!error_.has_value()) {
            throw std::runtime_error("Attempted to access error on success Result<void>");
        }
        return *error_;
    }

    [[nodiscard]] constexpr E& error() & {
        if (!error_.has_value()) {
            throw std::runtime_error("Attempted to access error on success Result<void>");
        }
        return *error_;
    }

    [[nodiscard]] constexpr E&& error() && {
        if (!error_.has_value()) {
            throw std::runtime_error("Attempted to access error on success Result<void>");
        }
        return std::move(*error_);
    }

private:
    std::optional<E> error_;
};

} // namespace lddm

