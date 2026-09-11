#pragma once

#include <string>
#include <vector>
#include <functional>
#include "ldde/core/types.hpp"
#include "ldde/shell/shm_buffer.hpp"
#include "ldde/shell/theme.hpp"
#include "ldde/shell/design_tokens.hpp"
#include "ldde/display/display_policy.hpp"

namespace ldde::system {

struct VirtualKey {
    std::string normal_label;
    std::string shift_label;
    int32_t evdev_code = 0;
    double width_weight = 1.0;
    core::Rect geometry{0, 0, 0, 0};
    bool is_modifier = false;
    bool is_special = false;
};

class VirtualKeyboard {
public:
    using RenderRequestCallback = std::function<void()>;

    VirtualKeyboard();
    ~VirtualKeyboard() = default;

    void open();
    void close();
    void toggle();
    [[nodiscard]] bool is_open() const noexcept { return is_open_; }

    void on_request_render(RenderRequestCallback cb) { render_callback_ = std::move(cb); }

    void update_layout(const display::DisplayPolicy& policy);
    void render(shell::ShmBuffer& buffer, const shell::ShellTheme& theme, const shell::DesignTokens& tokens);

    bool handle_touch_down(int32_t x, int32_t y);
    bool handle_touch_up(int32_t x, int32_t y);
    void handle_touch_cancel();

    bool handle_pointer_motion(int32_t x, int32_t y);
    bool handle_pointer_button(uint32_t button, uint32_t state, int32_t x, int32_t y);

private:
    void initialize_key_rows();
    void dispatch_key(const VirtualKey& key);
    void request_render();

    bool is_open_ = false;
    core::Rect keyboard_rect_{0, 0, 0, 0};
    std::vector<std::vector<VirtualKey>> key_rows_;

    int32_t pressed_row_ = -1;
    int32_t pressed_col_ = -1;
    int32_t hovered_row_ = -1;
    int32_t hovered_col_ = -1;

    bool shift_active_ = false;
    bool caps_lock_ = false;
    bool ctrl_active_ = false;
    bool alt_active_ = false;

    RenderRequestCallback render_callback_;
};

} // namespace ldde::system
