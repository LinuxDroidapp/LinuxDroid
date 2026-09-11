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

struct PowerMenuItem {
    std::string id;
    std::string label;
    std::string subtitle;
    core::Rect geometry{0, 0, 0, 0};
};

class PowerMenu {
public:
    using ShutdownCallback = std::function<void()>;
    using RenderRequestCallback = std::function<void()>;

    PowerMenu();
    ~PowerMenu() = default;

    void open();
    void close();
    void toggle();
    [[nodiscard]] bool is_open() const noexcept { return is_open_; }

    void on_shutdown_requested(ShutdownCallback cb) { shutdown_callback_ = std::move(cb); }
    void on_request_render(RenderRequestCallback cb) { render_callback_ = std::move(cb); }

    void update_layout(const display::DisplayPolicy& policy);
    void render(shell::ShmBuffer& buffer, const shell::ShellTheme& theme, const shell::DesignTokens& tokens);

    bool handle_touch_down(int32_t x, int32_t y);
    bool handle_touch_up(int32_t x, int32_t y);
    void handle_touch_cancel();

    bool handle_pointer_motion(int32_t x, int32_t y);
    bool handle_pointer_button(uint32_t button, uint32_t state, int32_t x, int32_t y);

    bool handle_key(uint32_t key_symbol);

private:
    void trigger_action(const std::string& id);
    void request_render();

    bool is_open_ = false;
    core::Rect modal_rect_{0, 0, 0, 0};
    std::vector<PowerMenuItem> items_;
    int32_t hovered_index_ = -1;
    int32_t pressed_index_ = -1;
    int32_t selected_index_ = 0;

    ShutdownCallback shutdown_callback_;
    RenderRequestCallback render_callback_;
};

} // namespace ldde::system
