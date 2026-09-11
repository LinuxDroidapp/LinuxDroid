#include "lddm/config/config_parser.hpp"
#include <fstream>
#include <sstream>
#include <algorithm>
#include <cctype>

namespace lddm {

namespace {

std::string trim(std::string_view str) {
    auto start = str.find_first_not_of(" \t\r\n");
    if (start == std::string_view::npos) {
        return "";
    }
    auto end = str.find_last_not_of(" \t\r\n");
    return std::string(str.substr(start, end - start + 1));
}

std::string strip_quotes(std::string_view str) {
    if (str.size() >= 2) {
        if ((str.front() == '"' && str.back() == '"') ||
            (str.front() == '\'' && str.back() == '\'')) {
            return std::string(str.substr(1, str.size() - 2));
        }
    }
    return std::string(str);
}

bool parse_bool(std::string_view val, bool default_val = false) {
    std::string s = trim(val);
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    if (s == "true" || s == "1" || s == "yes" || s == "on") return true;
    if (s == "false" || s == "0" || s == "no" || s == "off") return false;
    return default_val;
}

std::uint32_t parse_uint32(std::string_view val, std::uint32_t default_val = 0) {
    try {
        std::string s = trim(val);
        if (s.empty()) return default_val;
        return static_cast<std::uint32_t>(std::stoul(s));
    } catch (...) {
        return default_val;
    }
}

std::vector<std::string> parse_string_list(std::string_view val) {
    std::vector<std::string> items;
    std::string current;
    bool in_quotes = false;
    char quote_char = 0;

    for (char c : val) {
        if (!in_quotes && (c == '"' || c == '\'')) {
            in_quotes = true;
            quote_char = c;
        } else if (in_quotes && c == quote_char) {
            in_quotes = false;
            quote_char = 0;
        } else if (!in_quotes && (c == ',' || c == ' ')) {
            std::string trimmed = trim(current);
            if (!trimmed.empty()) {
                items.push_back(strip_quotes(trimmed));
            }
            current.clear();
        } else {
            current.push_back(c);
        }
    }
    std::string trimmed = trim(current);
    if (!trimmed.empty()) {
        items.push_back(strip_quotes(trimmed));
    }
    return items;
}

} // namespace

Result<LddmConfig> ConfigParser::parse_string(std::string_view content, const LddmConfig& base) {
    LddmConfig config = base;
    std::string content_str(content);
    std::istringstream stream(content_str);
    std::string current_section;
    std::string line;
    std::uint32_t line_number = 0;

    while (std::getline(stream, line)) {
        ++line_number;
        std::string trimmed = trim(line);

        // Skip empty lines and comments
        if (trimmed.empty() || trimmed.front() == '#' || trimmed.front() == ';') {
            continue;
        }

        // Section header: [section_name]
        if (trimmed.front() == '[' && trimmed.back() == ']') {
            current_section = trim(trimmed.substr(1, trimmed.size() - 2));
            std::transform(current_section.begin(), current_section.end(), current_section.begin(), [](unsigned char c) {
                return static_cast<char>(std::tolower(c));
            });
            continue;
        }

        // Key = Value
        auto eq_pos = trimmed.find('=');
        if (eq_pos == std::string::npos) {
            return Result<LddmConfig>::failure(Error(
                ErrorCategory::Configuration,
                ErrorCode::ConfigParseSyntaxError,
                "Syntax error: missing '=' on line " + std::to_string(line_number) + ": " + trimmed));
        }

        std::string key = trim(trimmed.substr(0, eq_pos));
        std::string value = strip_quotes(trim(trimmed.substr(eq_pos + 1)));

        std::string key_lower = key;
        std::transform(key_lower.begin(), key_lower.end(), key_lower.begin(), [](unsigned char c) {
            return static_cast<char>(std::tolower(c));
        });

        if (current_section == "logging") {
            if (key_lower == "level") {
                if (auto lvl = parse_log_level(value)) {
                    config.logging.level = *lvl;
                } else {
                    return Result<LddmConfig>::failure(Error(
                        ErrorCategory::Configuration,
                        ErrorCode::ConfigInvalidValue,
                        "Invalid log level '" + value + "' on line " + std::to_string(line_number)));
                }
            } else if (key_lower == "file_path" || key_lower == "file") {
                config.logging.file_path = value;
            } else if (key_lower == "console" || key_lower == "console_output") {
                config.logging.console_output = parse_bool(value, true);
            } else if (key_lower == "colorize" || key_lower == "color") {
                config.logging.colorize = parse_bool(value, true);
            }
        } else if (current_section == "server") {
            if (key_lower == "socket_path" || key_lower == "socket") {
                config.server.socket_path = value;
            } else if (key_lower == "runtime_dir") {
                config.server.runtime_dir = value;
            } else if (key_lower == "pid_file") {
                config.server.pid_file = value;
            }
        } else if (current_section == "session") {
            if (key_lower == "default_user" || key_lower == "user") {
                config.session.default_user = value;
            } else if (key_lower == "session_type" || key_lower == "type") {
                config.session.session_type = value;
            } else if (key_lower == "display_number" || key_lower == "display") {
                config.session.display_number = parse_uint32(value, 0);
            } else if (key_lower == "wayland_display") {
                config.session.wayland_display = value;
            }
        } else if (current_section == "weston") {
            if (key_lower == "executable" || key_lower == "path") {
                config.weston.executable = value;
            } else if (key_lower == "config_path" || key_lower == "config") {
                config.weston.config_path = value;
            } else if (key_lower == "socket_name" || key_lower == "socket") {
                config.weston.socket_name = value;
            } else if (key_lower == "backend") {
                config.weston.backend = value;
            } else if (key_lower == "additional_args" || key_lower == "args") {
                config.weston.additional_args = parse_string_list(value);
            }
        } else if (current_section == "ldde") {
            if (key_lower == "executable" || key_lower == "path") {
                config.ldde.executable = value;
            } else if (key_lower == "session_target" || key_lower == "target") {
                config.ldde.session_target = value;
            } else if (key_lower == "autostart") {
                config.ldde.autostart = parse_bool(value, true);
            }
        } else if (current_section == "process") {
            if (key_lower == "startup_timeout_ms") {
                config.process.startup_timeout_ms = parse_uint32(value, 10000);
            } else if (key_lower == "stop_timeout_ms") {
                config.process.stop_timeout_ms = parse_uint32(value, 5000);
            } else if (key_lower == "max_restart_count") {
                config.process.max_restart_count = parse_uint32(value, 3);
            } else if (key_lower == "restart_window_seconds") {
                config.process.restart_window_seconds = parse_uint32(value, 60);
            }
        } else if (current_section == "environment") {
            config.environment.variables[key] = value;
        }
    }

    return Result<LddmConfig>::success(config);
}

Result<LddmConfig> ConfigParser::parse_file(const std::string& filepath, const LddmConfig& base) {
    std::ifstream file(filepath);
    if (!file.is_open()) {
        return Result<LddmConfig>::failure(Error(
            ErrorCategory::Configuration,
            ErrorCode::ConfigFileNotFound,
            "Could not open configuration file: " + filepath));
    }

    std::stringstream buffer;
    buffer << file.rdbuf();
    return parse_string(buffer.str(), base);
}

} // namespace lddm
