#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <source_location>
#include <iosfwd>
#include <optional>

namespace lddm {

enum class ErrorCategory : std::uint32_t {
    Configuration = 1,
    Session       = 2,
    Process       = 3,
    Platform      = 4,
    Compositor    = 5,
    Desktop       = 6,
    Resource      = 7,
    Internal      = 8,
    Recovery      = 9
};

[[nodiscard]] std::string_view to_string(ErrorCategory category) noexcept;

enum class ErrorCode : std::uint32_t {
    // Generic / Success
    Success = 0,

    // Configuration errors (100-199)
    ConfigFileNotFound         = 100,
    ConfigFileReadError        = 101,
    ConfigParseSyntaxError     = 102,
    ConfigValidationFailed     = 103,
    ConfigMissingRequiredKey   = 104,
    ConfigInvalidValue         = 105,
    ConfigMigrationFailed      = 106,
    ConfigSchemaInvalid        = 107,

    // Session errors (200-299)
    SessionAlreadyActive           = 200,
    SessionNotFound                = 201,
    SessionInvalidState            = 202,
    SessionStartupFailed           = 203,
    SessionTeardownFailed          = 204,
    SessionEnvironmentError        = 205,
    SessionAlreadyExists           = 206,
    SessionInitializationFailed    = 207,
    SessionRuntimeDirectoryFailed  = 208,
    SessionEnvironmentFailed       = 209,
    SessionStartFailed             = 210,
    SessionStopFailed              = 211,
    SessionCleanupFailed           = 212,

    // Process errors (300-399)
    ProcessSpawnFailed         = 300,
    ProcessTerminated          = 301,
    ProcessTimeout             = 302,
    ProcessSignalFailed        = 303,
    ProcessNotFound            = 304,
    ProcessExecFailed          = 305,
    ProcessInvalidState        = 306,
    ProcessWaitFailed          = 307,
    ProcessTerminationFailed   = 308,
    ProcessRegistrationFailed  = 309,
    ProcessCleanupFailed       = 310,

    // Platform errors (400-499)
    PlatformSyscallFailed      = 400,
    PlatformFdError            = 401,
    PlatformPathResolution     = 402,
    PlatformSignalError        = 403,
    PlatformClockError         = 404,
    PlatformPermissionDenied   = 405,

    // Compositor errors (500-599)
    CompositorSpawnFailed          = 500,
    CompositorSocketError          = 501,
    CompositorCrash                = 502,
    CompositorTimeout              = 503,
    CompositorConfigError          = 504,
    CompositorExecutableNotFound   = 505,
    CompositorReadinessFailed      = 506,
    CompositorInvalidState         = 507,
    CompositorShutdownFailed       = 508,
    CompositorNotRunning           = 509,

    // Desktop errors (600-699)
    DesktopSpawnFailed             = 600,
    DesktopSocketError             = 601,
    DesktopCrash                   = 602,
    DesktopTimeout                 = 603,
    LddeInitFailed                 = 604,
    LddeSpawnFailed                = 605,
    LddeCrash                      = 606,
    LddeConfigError                = 607,
    LddeExecutableNotFound         = 608,
    LddeReadinessFailed            = 609,
    LddeInvalidState               = 610,
    LddeShutdownFailed             = 611,
    LddeNotRunning                 = 612,
    LddeTimeout                    = 613,
    LddeProtocolError              = 614,
    GraphicalSessionNotReady       = 615,
    GraphicalSessionStartFailed    = 616,

    // Resource errors (700-799)
    ResourceExhausted          = 700,
    ResourceAllocationFailed   = 701,
    ResourceAlreadyInUse       = 702,
    ResourceInvalid            = 703,

    // Internal errors (800-899)
    InternalLogicError         = 800,
    InternalNotImplemented     = 801,
    InternalInvalidState       = 802,
    InternalUnknown            = 899,

    // Recovery errors (900-999)
    RecoveryNotAllowed         = 900,
    RecoveryInProgress         = 901,
    RecoveryPreconditionFailed = 902,
    RecoveryCleanupFailed      = 903,
    RecoveryRestartFailed      = 904,
    RecoveryReadinessFailed    = 905,
    RecoveryTimeout            = 906,
    RecoveryExhausted          = 907,
    RecoveryStateInvalid       = 908,
    SessionRecoveryFailed      = 909
};

[[nodiscard]] std::string_view to_string(ErrorCode code) noexcept;

class Error {
public:
    Error() noexcept = default;

    Error(ErrorCategory category,
          ErrorCode code,
          std::string message,
          std::string context = {},
          std::source_location location = std::source_location::current())
        : category_(category)
        , code_(code)
        , message_(std::move(message))
        , context_(std::move(context))
        , file_name_(location.file_name())
        , function_name_(location.function_name())
        , line_(location.line()) {}

    [[nodiscard]] ErrorCategory category() const noexcept { return category_; }
    [[nodiscard]] ErrorCode code() const noexcept { return code_; }
    [[nodiscard]] const std::string& message() const noexcept { return message_; }
    [[nodiscard]] const std::string& context() const noexcept { return context_; }
    [[nodiscard]] std::string_view file_name() const noexcept { return file_name_; }
    [[nodiscard]] std::string_view function_name() const noexcept { return function_name_; }
    [[nodiscard]] std::uint32_t line() const noexcept { return line_; }

    [[nodiscard]] bool is_ok() const noexcept { return code_ == ErrorCode::Success; }
    [[nodiscard]] explicit operator bool() const noexcept { return !is_ok(); }

    [[nodiscard]] std::string to_string() const;

    bool operator==(const Error& other) const noexcept {
        return category_ == other.category_ && code_ == other.code_;
    }

    bool operator!=(const Error& other) const noexcept {
        return !(*this == other);
    }

private:
    ErrorCategory category_{ErrorCategory::Internal};
    ErrorCode code_{ErrorCode::Success};
    std::string message_{};
    std::string context_{};
    std::string file_name_{};
    std::string function_name_{};
    std::uint32_t line_{0};
};

std::ostream& operator<<(std::ostream& os, ErrorCategory category);
std::ostream& operator<<(std::ostream& os, ErrorCode code);
std::ostream& operator<<(std::ostream& os, const Error& error);

} // namespace lddm
