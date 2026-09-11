#include "ldde/system/virtual_keyboard.hpp"
#include "ldde/core/logging.hpp"
#include <cairo/cairo.h>
#include <cmath>
#include <fstream>
#include <fcntl.h>
#include <unistd.h>
#include <linux/input-event-codes.h>

namespace ldde::system {

namespace {

void draw_rounded_rect(cairo_t* cr, double x, double y, double w, double h, double r) {
    r = std::min(r, std::min(w, h) / 2.0);
    cairo_new_sub_path(cr);
    cairo_arc(cr, x + w - r, y + r, r, -M_PI_2, 0);
    cairo_arc(cr, x + w - r, y + h - r, r, 0, M_PI_2);
    cairo_arc(cr, x + r, y + h - r, r, M_PI_2, M_PI);
    cairo_arc(cr, x + r, y + r, r, M_PI, 3.0 * M_PI_2);
    cairo_close_path(cr);
}

void write_fifo_key(int code) {
    int fd = open("/tmp/.linuxdroid_input_fifo", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        const char* xdg = std::getenv("XDG_RUNTIME_DIR");
        if (xdg && xdg[0]) {
            std::string p = std::string(xdg) + "/.linuxdroid_input_fifo";
            fd = open(p.c_str(), O_WRONLY | O_NONBLOCK);
        }
    }
    if (fd >= 0) {
        std::string s = std::to_string(code) + "\n";
        ssize_t w = write(fd, s.c_str(), s.length());
        (void)w;
        close(fd);
    }
}

} // namespace

VirtualKeyboard::VirtualKeyboard() {
    initialize_key_rows();
}

void VirtualKeyboard::initialize_key_rows() {
    key_rows_.clear();

    // Row 1: Esc, Numbers 1-0, -, =, Backspace
    key_rows_.push_back({
        {"Esc", "Esc", KEY_ESC, 1.0, {0,0,0,0}, false, true},
        {"1", "!", KEY_1, 1.0, {0,0,0,0}, false, false},
        {"2", "@", KEY_2, 1.0, {0,0,0,0}, false, false},
        {"3", "#", KEY_3, 1.0, {0,0,0,0}, false, false},
        {"4", "$", KEY_4, 1.0, {0,0,0,0}, false, false},
        {"5", "%", KEY_5, 1.0, {0,0,0,0}, false, false},
        {"6", "^", KEY_6, 1.0, {0,0,0,0}, false, false},
        {"7", "&", KEY_7, 1.0, {0,0,0,0}, false, false},
        {"8", "*", KEY_8, 1.0, {0,0,0,0}, false, false},
        {"9", "(", KEY_9, 1.0, {0,0,0,0}, false, false},
        {"0", ")", KEY_0, 1.0, {0,0,0,0}, false, false},
        {"-", "_", KEY_MINUS, 1.0, {0,0,0,0}, false, false},
        {"=", "+", KEY_EQUAL, 1.0, {0,0,0,0}, false, false},
        {"⌫", "⌫", KEY_BACKSPACE, 1.4, {0,0,0,0}, false, true}
    });

    // Row 2: Tab, QWERTYUIOP, [, ]
    key_rows_.push_back({
        {"Tab", "Tab", KEY_TAB, 1.3, {0,0,0,0}, false, true},
        {"q", "Q", KEY_Q, 1.0, {0,0,0,0}, false, false},
        {"w", "W", KEY_W, 1.0, {0,0,0,0}, false, false},
        {"e", "E", KEY_E, 1.0, {0,0,0,0}, false, false},
        {"r", "R", KEY_R, 1.0, {0,0,0,0}, false, false},
        {"t", "T", KEY_T, 1.0, {0,0,0,0}, false, false},
        {"y", "Y", KEY_Y, 1.0, {0,0,0,0}, false, false},
        {"u", "U", KEY_U, 1.0, {0,0,0,0}, false, false},
        {"i", "I", KEY_I, 1.0, {0,0,0,0}, false, false},
        {"o", "O", KEY_O, 1.0, {0,0,0,0}, false, false},
        {"p", "P", KEY_P, 1.0, {0,0,0,0}, false, false},
        {"[", "{", KEY_LEFTBRACE, 1.0, {0,0,0,0}, false, false},
        {"]", "}", KEY_RIGHTBRACE, 1.0, {0,0,0,0}, false, false}
    });

    // Row 3: Caps, ASDFGHJKL, ;, ', Enter
    key_rows_.push_back({
        {"Caps", "Caps", KEY_CAPSLOCK, 1.5, {0,0,0,0}, true, true},
        {"a", "A", KEY_A, 1.0, {0,0,0,0}, false, false},
        {"s", "S", KEY_S, 1.0, {0,0,0,0}, false, false},
        {"d", "D", KEY_D, 1.0, {0,0,0,0}, false, false},
        {"f", "F", KEY_F, 1.0, {0,0,0,0}, false, false},
        {"g", "G", KEY_G, 1.0, {0,0,0,0}, false, false},
        {"h", "H", KEY_H, 1.0, {0,0,0,0}, false, false},
        {"j", "J", KEY_J, 1.0, {0,0,0,0}, false, false},
        {"k", "K", KEY_K, 1.0, {0,0,0,0}, false, false},
        {"l", "L", KEY_L, 1.0, {0,0,0,0}, false, false},
        {";", ":", KEY_SEMICOLON, 1.0, {0,0,0,0}, false, false},
        {"'", "\"", KEY_APOSTROPHE, 1.0, {0,0,0,0}, false, false},
        {"Enter", "Enter", KEY_ENTER, 1.6, {0,0,0,0}, false, true}
    });

    // Row 4: Shift, ZXCVBNM, ,, ., /, Up, Hide
    key_rows_.push_back({
        {"Shift", "Shift", KEY_LEFTSHIFT, 1.8, {0,0,0,0}, true, true},
        {"z", "Z", KEY_Z, 1.0, {0,0,0,0}, false, false},
        {"x", "X", KEY_X, 1.0, {0,0,0,0}, false, false},
        {"c", "C", KEY_C, 1.0, {0,0,0,0}, false, false},
        {"v", "V", KEY_V, 1.0, {0,0,0,0}, false, false},
        {"b", "B", KEY_B, 1.0, {0,0,0,0}, false, false},
        {"n", "N", KEY_N, 1.0, {0,0,0,0}, false, false},
        {"m", "M", KEY_M, 1.0, {0,0,0,0}, false, false},
        {",", "<", KEY_COMMA, 1.0, {0,0,0,0}, false, false},
        {".", ">", KEY_DOT, 1.0, {0,0,0,0}, false, false},
        {"/", "?", KEY_SLASH, 1.0, {0,0,0,0}, false, false},
        {"▲", "▲", KEY_UP, 1.0, {0,0,0,0}, false, true},
        {"✕", "✕", -1, 1.2, {0,0,0,0}, false, true} // Hide key
    });

    // Row 5: Ctrl, Alt, Space, Left, Down, Right
    key_rows_.push_back({
        {"Ctrl", "Ctrl", KEY_LEFTCTRL, 1.5, {0,0,0,0}, true, true},
        {"Alt", "Alt", KEY_LEFTALT, 1.5, {0,0,0,0}, true, true},
        {"Space", "Space", KEY_SPACE, 5.0, {0,0,0,0}, false, false},
        {"◄", "◄", KEY_LEFT, 1.0, {0,0,0,0}, false, true},
        {"▼", "▼", KEY_DOWN, 1.0, {0,0,0,0}, false, true},
        {"►", "►", KEY_RIGHT, 1.0, {0,0,0,0}, false, true}
    });
}

void VirtualKeyboard::open() {
    if (!is_open_) {
        is_open_ = true;
        pressed_row_ = -1;
        pressed_col_ = -1;
        LDDE_LOG_INFO(System, "Virtual Keyboard opened");
        request_render();
    }
}

void VirtualKeyboard::close() {
    if (is_open_) {
        is_open_ = false;
        pressed_row_ = -1;
        pressed_col_ = -1;
        shift_active_ = false;
        ctrl_active_ = false;
        alt_active_ = false;
        LDDE_LOG_INFO(System, "Virtual Keyboard closed");
        request_render();
    }
}

void VirtualKeyboard::toggle() {
    if (is_open_) {
        close();
    } else {
        open();
    }
}

void VirtualKeyboard::request_render() {
    if (render_callback_) {
        render_callback_();
    }
}

void VirtualKeyboard::update_layout(const display::DisplayPolicy& policy) {
    double scale = policy.scale_policy().effective_scale() > 0 ? policy.scale_policy().effective_scale() : 1.0;
    int32_t screen_w = policy.display_info().width > 0 ? policy.display_info().width : 720;
    int32_t screen_h = policy.display_info().height > 0 ? policy.display_info().height : 1280;

    int32_t kb_h = std::min(static_cast<int32_t>(220 * scale), screen_h / 2);
    int32_t kb_w = std::min(screen_w, static_cast<int32_t>(760 * scale));
    int32_t kb_x = (screen_w - kb_w) / 2;
    // Position just above dock
    int32_t dock_h = static_cast<int32_t>(64 * scale);
    int32_t kb_y = screen_h - kb_h - dock_h - static_cast<int32_t>(8 * scale);

    keyboard_rect_ = core::Rect{kb_x, kb_y, kb_w, kb_h};

    int32_t pad = static_cast<int32_t>(6 * scale);
    int32_t gap = static_cast<int32_t>(4 * scale);
    int32_t avail_h = kb_h - pad * 2;
    int32_t num_rows = static_cast<int32_t>(key_rows_.size());
    int32_t row_h = (avail_h - (num_rows - 1) * gap) / num_rows;

    for (size_t r = 0; r < key_rows_.size(); ++r) {
        auto& row = key_rows_[r];
        double total_weight = 0.0;
        for (const auto& k : row) {
            total_weight += k.width_weight;
        }

        int32_t cur_y = kb_y + pad + static_cast<int32_t>(r) * (row_h + gap);
        int32_t avail_w = kb_w - pad * 2 - static_cast<int32_t>(row.size() - 1) * gap;
        int32_t cur_x = kb_x + pad;

        for (size_t c = 0; c < row.size(); ++c) {
            int32_t kw = static_cast<int32_t>(std::round((row[c].width_weight / total_weight) * avail_w));
            row[c].geometry = core::Rect{cur_x, cur_y, kw, row_h};
            cur_x += kw + gap;
        }
    }
}

void VirtualKeyboard::render(shell::ShmBuffer& buffer, const shell::ShellTheme& theme, const shell::DesignTokens& tokens) {
    (void)theme;
    if (!is_open_ || !buffer.data() || buffer.width() <= 0 || buffer.height() <= 0) return;

    cairo_surface_t* surface = cairo_image_surface_create_for_data(
        static_cast<unsigned char*>(buffer.data()),
        CAIRO_FORMAT_ARGB32,
        buffer.width(),
        buffer.height(),
        buffer.stride());

    cairo_t* cr = cairo_create(surface);

    double scale = tokens.scale > 0 ? tokens.scale : 1.0;
    double kx = keyboard_rect_.x;
    double ky = keyboard_rect_.y;
    double kw = keyboard_rect_.width;
    double kh = keyboard_rect_.height;
    double radius = 16.0 * scale;

    // Keyboard background panel
    draw_rounded_rect(cr, kx, ky, kw, kh, radius);
    cairo_set_source_rgba(cr, 0.08, 0.11, 0.17, 0.96);
    cairo_fill_preserve(cr);

    cairo_set_source_rgba(cr, 1.0, 1.0, 1.0, 0.15);
    cairo_set_line_width(cr, 1.2 * scale);
    cairo_stroke(cr);

    bool use_shift = (shift_active_ ^ caps_lock_);

    // Render keys
    for (size_t r = 0; r < key_rows_.size(); ++r) {
        for (size_t c = 0; c < key_rows_[r].size(); ++c) {
            const auto& key = key_rows_[r][c];
            double x = key.geometry.x;
            double y = key.geometry.y;
            double w = key.geometry.width;
            double h = key.geometry.height;
            double kr = 6.0 * scale;

            bool is_pressed = (pressed_row_ == static_cast<int32_t>(r) && pressed_col_ == static_cast<int32_t>(c));
            bool is_latched = false;
            if (key.evdev_code == KEY_LEFTSHIFT) is_latched = shift_active_;
            else if (key.evdev_code == KEY_CAPSLOCK) is_latched = caps_lock_;
            else if (key.evdev_code == KEY_LEFTCTRL) is_latched = ctrl_active_;
            else if (key.evdev_code == KEY_LEFTALT) is_latched = alt_active_;

            draw_rounded_rect(cr, x, y, w, h, kr);

            if (is_pressed) {
                cairo_set_source_rgba(cr, 0.35, 0.65, 0.95, 0.60);
            } else if (is_latched) {
                cairo_set_source_rgba(cr, 0.20, 0.50, 0.90, 0.50);
            } else if (key.is_special) {
                cairo_set_source_rgba(cr, 1.0, 1.0, 1.0, 0.10);
            } else {
                cairo_set_source_rgba(cr, 1.0, 1.0, 1.0, 0.05);
            }
            cairo_fill_preserve(cr);

            // Border
            if (is_latched || is_pressed) {
                cairo_set_source_rgba(cr, 0.50, 0.80, 1.0, 0.90);
                cairo_set_line_width(cr, 1.5 * scale);
            } else {
                cairo_set_source_rgba(cr, 1.0, 1.0, 1.0, 0.10);
                cairo_set_line_width(cr, 0.8 * scale);
            }
            cairo_stroke(cr);

            // Key label
            const std::string& label = use_shift ? key.shift_label : key.normal_label;
            cairo_select_font_face(cr, "sans-serif", CAIRO_FONT_SLANT_NORMAL,
                                   key.is_special ? CAIRO_FONT_WEIGHT_NORMAL : CAIRO_FONT_WEIGHT_BOLD);
            double font_sz = (label.length() > 2 ? 10.5 : 13.5) * scale;
            cairo_set_font_size(cr, font_sz);

            cairo_text_extents_t ext;
            cairo_text_extents(cr, label.c_str(), &ext);

            double tx = x + (w - ext.width) / 2.0 - ext.x_bearing;
            double ty = y + (h - ext.height) / 2.0 - ext.y_bearing;

            if (is_latched || is_pressed) {
                cairo_set_source_rgba(cr, 1.0, 1.0, 1.0, 1.0);
            } else if (key.is_special) {
                cairo_set_source_rgba(cr, 0.75, 0.80, 0.88, 1.0);
            } else {
                cairo_set_source_rgba(cr, 0.92, 0.94, 0.97, 1.0);
            }
            cairo_move_to(cr, tx, ty);
            cairo_show_text(cr, label.c_str());
        }
    }

    cairo_destroy(cr);
    cairo_surface_destroy(surface);
}

void VirtualKeyboard::dispatch_key(const VirtualKey& key) {
    if (key.evdev_code == -1) {
        // Hide key
        close();
        return;
    }

    if (key.evdev_code == KEY_LEFTSHIFT) {
        shift_active_ = !shift_active_;
        request_render();
        return;
    }
    if (key.evdev_code == KEY_CAPSLOCK) {
        caps_lock_ = !caps_lock_;
        request_render();
        return;
    }
    if (key.evdev_code == KEY_LEFTCTRL) {
        ctrl_active_ = !ctrl_active_;
        request_render();
        return;
    }
    if (key.evdev_code == KEY_LEFTALT) {
        alt_active_ = !alt_active_;
        request_render();
        return;
    }

    // Normal or special key press
    write_fifo_key(key.evdev_code);

    if (shift_active_) {
        shift_active_ = false;
        request_render();
    }
}

bool VirtualKeyboard::handle_touch_down(int32_t x, int32_t y) {
    if (!is_open_) return false;

    core::Point pt{x, y};
    if (!keyboard_rect_.contains(pt)) {
        return false;
    }

    for (size_t r = 0; r < key_rows_.size(); ++r) {
        for (size_t c = 0; c < key_rows_[r].size(); ++c) {
            if (key_rows_[r][c].geometry.contains(pt)) {
                pressed_row_ = static_cast<int32_t>(r);
                pressed_col_ = static_cast<int32_t>(c);
                request_render();
                return true;
            }
        }
    }

    return true;
}

bool VirtualKeyboard::handle_touch_up(int32_t x, int32_t y) {
    if (!is_open_) return false;

    if (pressed_row_ >= 0 && pressed_row_ < static_cast<int32_t>(key_rows_.size())) {
        if (pressed_col_ >= 0 && pressed_col_ < static_cast<int32_t>(key_rows_[pressed_row_].size())) {
            core::Point pt{x, y};
            const auto& key = key_rows_[pressed_row_][pressed_col_];
            if (key.geometry.contains(pt)) {
                dispatch_key(key);
            }
        }
    }

    pressed_row_ = -1;
    pressed_col_ = -1;
    request_render();
    return true;
}

void VirtualKeyboard::handle_touch_cancel() {
    if (!is_open_) return;
    pressed_row_ = -1;
    pressed_col_ = -1;
    request_render();
}

bool VirtualKeyboard::handle_pointer_motion(int32_t x, int32_t y) {
    if (!is_open_) return false;
    core::Point pt{x, y};
    return keyboard_rect_.contains(pt);
}

bool VirtualKeyboard::handle_pointer_button(uint32_t button, uint32_t state, int32_t x, int32_t y) {
    (void)button;
    if (!is_open_) return false;

    if (state == 1) { // Press
        return handle_touch_down(x, y);
    } else if (state == 0) { // Release
        return handle_touch_up(x, y);
    }
    return false;
}

} // namespace ldde::system
