#include "ldde/system/power_menu.hpp"
#include "ldde/core/logging.hpp"
#include <cairo/cairo.h>
#include <cmath>
#include <fstream>
#include <cstdlib>

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

void write_action_file(const std::string& action) {
    std::ofstream f1("/tmp/linuxdroid_session_action");
    if (f1.is_open()) {
        f1 << action << "\n";
        f1.close();
    }
    const char* xdg = std::getenv("XDG_RUNTIME_DIR");
    if (xdg && xdg[0]) {
        std::ofstream f2(std::string(xdg) + "/linuxdroid_session_action");
        if (f2.is_open()) {
            f2 << action << "\n";
            f2.close();
        }
    }
}

} // namespace

PowerMenu::PowerMenu() {
    items_.push_back({"android", "→ Android", "Minimize desktop & return to Android host", {0, 0, 0, 0}});
    items_.push_back({"shutdown", "⏻ Shutdown", "Terminate desktop session & shutdown environment", {0, 0, 0, 0}});
    items_.push_back({"cancel", "Cancel", "Return to desktop", {0, 0, 0, 0}});
}

void PowerMenu::open() {
    is_open_ = true;
    pressed_index_ = -1;
    selected_index_ = 0;
    request_render();
}

void PowerMenu::close() {
    if (!is_open_) return;
    is_open_ = false;
    pressed_index_ = -1;
    request_render();
}

void PowerMenu::toggle() {
    if (is_open_) {
        close();
    } else {
        open();
    }
}

void PowerMenu::request_render() {
    if (render_callback_) {
        render_callback_();
    }
}

void PowerMenu::update_layout(const display::DisplayPolicy& policy) {
    double scale = policy.scale_policy().effective_scale() > 0 ? policy.scale_policy().effective_scale() : 1.0;
    int32_t screen_w = policy.display_info().width > 0 ? policy.display_info().width : 720;
    int32_t screen_h = policy.display_info().height > 0 ? policy.display_info().height : 1280;

    int32_t card_w = std::min(static_cast<int32_t>(340 * scale), screen_w - static_cast<int32_t>(32 * scale));
    int32_t card_h = static_cast<int32_t>(270 * scale);
    int32_t card_x = (screen_w - card_w) / 2;
    int32_t card_y = (screen_h - card_h) / 2;

    modal_rect_ = core::Rect{card_x, card_y, card_w, card_h};

    int32_t pad_h = static_cast<int32_t>(18 * scale);
    int32_t start_y = card_y + static_cast<int32_t>(68 * scale);
    int32_t item_w = card_w - pad_h * 2;
    int32_t item_h = static_cast<int32_t>(54 * scale);
    int32_t item_gap = static_cast<int32_t>(10 * scale);

    for (size_t i = 0; i < items_.size(); ++i) {
        items_[i].geometry = core::Rect{card_x + pad_h, start_y + static_cast<int32_t>(i) * (item_h + item_gap), item_w, item_h};
    }
}

void PowerMenu::render(shell::ShmBuffer& buffer, const shell::ShellTheme& theme, const shell::DesignTokens& tokens) {
    (void)theme;
    if (!is_open_ || !buffer.data() || buffer.width() <= 0 || buffer.height() <= 0) return;

    cairo_surface_t* surface = cairo_image_surface_create_for_data(
        static_cast<unsigned char*>(buffer.data()),
        CAIRO_FORMAT_ARGB32,
        buffer.width(),
        buffer.height(),
        buffer.stride());

    cairo_t* cr = cairo_create(surface);

    // 1. Scrim backdrop
    cairo_set_source_rgba(cr, 0.0, 0.0, 0.0, 0.60);
    cairo_paint(cr);

    double scale = tokens.scale > 0 ? tokens.scale : 1.0;
    double px = modal_rect_.x;
    double py = modal_rect_.y;
    double pw = modal_rect_.width;
    double ph = modal_rect_.height;
    double radius = 18.0 * scale;

    // 2. Modal card
    draw_rounded_rect(cr, px, py, pw, ph, radius);
    cairo_set_source_rgba(cr, 0.09, 0.12, 0.18, 0.98);
    cairo_fill_preserve(cr);

    cairo_set_source_rgba(cr, 1.0, 1.0, 1.0, 0.16);
    cairo_set_line_width(cr, 1.2 * scale);
    cairo_stroke(cr);

    // 3. Header: Title and subtitle
    cairo_select_font_face(cr, "sans-serif", CAIRO_FONT_SLANT_NORMAL, CAIRO_FONT_WEIGHT_BOLD);
    cairo_set_font_size(cr, 17.0 * scale);
    cairo_set_source_rgba(cr, 0.95, 0.96, 0.98, 1.0);
    cairo_move_to(cr, px + 20.0 * scale, py + 32.0 * scale);
    cairo_show_text(cr, "Power Menu");

    cairo_select_font_face(cr, "sans-serif", CAIRO_FONT_SLANT_NORMAL, CAIRO_FONT_WEIGHT_NORMAL);
    cairo_set_font_size(cr, 11.5 * scale);
    cairo_set_source_rgba(cr, 0.60, 0.65, 0.75, 1.0);
    cairo_move_to(cr, px + 20.0 * scale, py + 50.0 * scale);
    cairo_show_text(cr, "LinuxDroid Desktop Session");

    // 4. Menu Items
    for (size_t i = 0; i < items_.size(); ++i) {
        const auto& item = items_[i];
        double ix = item.geometry.x;
        double iy = item.geometry.y;
        double iw = item.geometry.width;
        double ih = item.geometry.height;
        double ir = 10.0 * scale;

        bool is_pressed = (pressed_index_ == static_cast<int32_t>(i));
        bool is_hovered = (hovered_index_ == static_cast<int32_t>(i));
        bool is_selected = (selected_index_ == static_cast<int32_t>(i));

        draw_rounded_rect(cr, ix, iy, iw, ih, ir);

        if (item.id == "shutdown") {
            // Shutdown button (red tint)
            if (is_pressed) {
                cairo_set_source_rgba(cr, 0.85, 0.20, 0.20, 0.35);
            } else if (is_hovered || is_selected) {
                cairo_set_source_rgba(cr, 0.85, 0.20, 0.20, 0.22);
            } else {
                cairo_set_source_rgba(cr, 0.85, 0.20, 0.20, 0.12);
            }
        } else if (item.id == "android") {
            // Android minimize button (blue tint)
            if (is_pressed) {
                cairo_set_source_rgba(cr, 0.20, 0.55, 0.90, 0.35);
            } else if (is_hovered || is_selected) {
                cairo_set_source_rgba(cr, 0.20, 0.55, 0.90, 0.22);
            } else {
                cairo_set_source_rgba(cr, 0.20, 0.55, 0.90, 0.12);
            }
        } else {
            // Cancel button (neutral)
            if (is_pressed) {
                cairo_set_source_rgba(cr, 1.0, 1.0, 1.0, 0.18);
            } else if (is_hovered || is_selected) {
                cairo_set_source_rgba(cr, 1.0, 1.0, 1.0, 0.12);
            } else {
                cairo_set_source_rgba(cr, 1.0, 1.0, 1.0, 0.06);
            }
        }
        cairo_fill_preserve(cr);

        // Border
        if (is_selected || is_hovered) {
            if (item.id == "shutdown") {
                cairo_set_source_rgba(cr, 0.95, 0.30, 0.30, 0.85);
            } else if (item.id == "android") {
                cairo_set_source_rgba(cr, 0.35, 0.70, 1.0, 0.85);
            } else {
                cairo_set_source_rgba(cr, 1.0, 1.0, 1.0, 0.40);
            }
            cairo_set_line_width(cr, 1.5 * scale);
        } else {
            cairo_set_source_rgba(cr, 1.0, 1.0, 1.0, 0.10);
            cairo_set_line_width(cr, 1.0 * scale);
        }
        cairo_stroke(cr);

        // Label
        cairo_select_font_face(cr, "sans-serif", CAIRO_FONT_SLANT_NORMAL, CAIRO_FONT_WEIGHT_BOLD);
        cairo_set_font_size(cr, 14.5 * scale);
        if (item.id == "shutdown") {
            cairo_set_source_rgba(cr, 1.0, 0.45, 0.45, 1.0);
        } else if (item.id == "android") {
            cairo_set_source_rgba(cr, 0.45, 0.80, 1.0, 1.0);
        } else {
            cairo_set_source_rgba(cr, 0.90, 0.92, 0.96, 1.0);
        }
        cairo_move_to(cr, ix + 16.0 * scale, iy + 24.0 * scale);
        cairo_show_text(cr, item.label.c_str());

        // Subtitle
        cairo_select_font_face(cr, "sans-serif", CAIRO_FONT_SLANT_NORMAL, CAIRO_FONT_WEIGHT_NORMAL);
        cairo_set_font_size(cr, 11.0 * scale);
        cairo_set_source_rgba(cr, 0.60, 0.65, 0.75, 1.0);
        cairo_move_to(cr, ix + 16.0 * scale, iy + 42.0 * scale);
        cairo_show_text(cr, item.subtitle.c_str());
    }

    cairo_destroy(cr);
    cairo_surface_destroy(surface);
}

void PowerMenu::trigger_action(const std::string& id) {
    LDDE_LOG_INFO(System, "Power menu action selected: " << id);
    if (id == "android") {
        write_action_file("minimize");
        close();
    } else if (id == "shutdown") {
        write_action_file("shutdown");
        close();
        if (shutdown_callback_) {
            shutdown_callback_();
        }
    } else if (id == "cancel") {
        close();
    }
}

bool PowerMenu::handle_touch_down(int32_t x, int32_t y) {
    if (!is_open_) return false;

    core::Point pt{x, y};
    for (size_t i = 0; i < items_.size(); ++i) {
        if (items_[i].geometry.contains(pt)) {
            pressed_index_ = static_cast<int32_t>(i);
            selected_index_ = static_cast<int32_t>(i);
            request_render();
            return true;
        }
    }

    // Tapping outside modal card dismisses menu
    if (!modal_rect_.contains(pt)) {
        close();
        return true;
    }

    return true;
}

bool PowerMenu::handle_touch_up(int32_t x, int32_t y) {
    if (!is_open_) return false;

    if (pressed_index_ >= 0 && pressed_index_ < static_cast<int32_t>(items_.size())) {
        core::Point pt{x, y};
        if (items_[pressed_index_].geometry.contains(pt)) {
            trigger_action(items_[pressed_index_].id);
        }
    }

    pressed_index_ = -1;
    request_render();
    return true;
}

void PowerMenu::handle_touch_cancel() {
    if (!is_open_) return;
    pressed_index_ = -1;
    request_render();
}

bool PowerMenu::handle_pointer_motion(int32_t x, int32_t y) {
    if (!is_open_) return false;

    core::Point pt{x, y};
    int32_t new_hovered = -1;
    for (size_t i = 0; i < items_.size(); ++i) {
        if (items_[i].geometry.contains(pt)) {
            new_hovered = static_cast<int32_t>(i);
            break;
        }
    }

    if (new_hovered != hovered_index_) {
        hovered_index_ = new_hovered;
        if (hovered_index_ >= 0) {
            selected_index_ = hovered_index_;
        }
        request_render();
    }
    return true;
}

bool PowerMenu::handle_pointer_button(uint32_t button, uint32_t state, int32_t x, int32_t y) {
    (void)button;
    if (!is_open_) return false;

    if (state == 1) { // Press
        return handle_touch_down(x, y);
    } else if (state == 0) { // Release
        return handle_touch_up(x, y);
    }
    return false;
}

bool PowerMenu::handle_key(uint32_t key_symbol) {
    if (!is_open_) return false;

    // Escape: 0xff1b
    if (key_symbol == 0xff1b) {
        close();
        return true;
    }
    // Arrow Up: 0xff52, Arrow Left: 0xff51
    if (key_symbol == 0xff52 || key_symbol == 0xff51) {
        selected_index_ = (selected_index_ - 1 + static_cast<int32_t>(items_.size())) % static_cast<int32_t>(items_.size());
        request_render();
        return true;
    }
    // Arrow Down: 0xff54, Arrow Right: 0xff53
    if (key_symbol == 0xff54 || key_symbol == 0xff53) {
        selected_index_ = (selected_index_ + 1) % static_cast<int32_t>(items_.size());
        request_render();
        return true;
    }
    // Return: 0xff0d, Space: 0x0020
    if (key_symbol == 0xff0d || key_symbol == 0x0020) {
        if (selected_index_ >= 0 && selected_index_ < static_cast<int32_t>(items_.size())) {
            trigger_action(items_[selected_index_].id);
            return true;
        }
    }

    return true;
}

} // namespace ldde::system
