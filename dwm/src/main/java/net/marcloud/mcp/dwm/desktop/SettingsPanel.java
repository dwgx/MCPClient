package net.marcloud.mcp.dwm.desktop;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.FontHandle;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.component.ComponentContext;
import net.marcloud.mcp.dwm.desktop.theme.ThemeState;
import net.marcloud.mcp.dwm.theme.MdcTheme;
import net.marcloud.mcp.dwm.theme.MdcTheme.ColorRole;
import net.marcloud.mcp.dwm.theme.MdcTheme.ShapeSize;
import net.marcloud.mcp.dwm.theme.MdcTheme.TypeRole;

/**
 * The launcher's settings/theme editor body: a column of controls that mutate the shared
 * {@link ThemeState} live (Codex / Claude-desktop style) — a theme-preset row, an accent
 * color row, and font-scale + panel-opacity steppers. Because every launcher component
 * reads color/type through {@code ComponentContext.theme()} (a {@code DesktopTheme} backed
 * by this same state), a click here recolors and rescales the WHOLE launcher on the very
 * next frame with no other component changes.
 *
 * <p>Draws only through {@link DrawContext} and keeps click-edge state per control in the
 * {@link net.marcloud.mcp.dwm.compositor.UiStateStore} under this panel's id, so it renders
 * and behaves identically on gl / imgui / skiko.
 */
public final class SettingsPanel implements Component {

    private static final FontHandle FONT = new FontHandle(0L);
    private static final int PRIMARY_BUTTON = 1;
    private static final float ROW_GAP = 14f;
    private static final float LABEL_H = 20f;
    private static final float SWATCH = 34f;
    private static final float SWATCH_GAP = 8f;
    private static final float STEP_BTN = 30f;
    private static final float FONT_STEP = 0.05f;
    private static final int OPACITY_STEP = 8;

    private final ThemeState state;
    private final DesktopInputState input; // null in tests: the behavior toggle row is omitted

    public SettingsPanel(ThemeState state) {
        this(state, null);
    }

    /**
     * @param state theme model the color/type controls edit
     * @param input launcher input state, for the behavior toggles (e.g. move-while-open);
     *              null omits that section (tests that only exercise the theme controls)
     */
    public SettingsPanel(ThemeState state, DesktopInputState input) {
        this.state = state == null ? new ThemeState() : state;
        this.input = input;
    }

    @Override
    public Size measure(ComponentContext ctx) {
        return new Size(0f, 0f);
    }

    @Override
    public Result render(ComponentContext ctx, float x, float y, float w, float h) {
        MdcTheme theme = ctx.theme();
        float cy = y;

        // 1) Theme preset row: one preview swatch per preset; the active one gets a ring.
        cy = label(ctx, "Theme", x, cy, w) + 4f;
        cy = presetRow(ctx, theme, x, cy, w) + ROW_GAP;

        // 2) Accent color row: the color-picker choices; active one ringed.
        cy = label(ctx, "Accent", x, cy, w) + 4f;
        cy = accentRow(ctx, theme, x, cy, w) + ROW_GAP;

        // 3) Font scale stepper (-/+ around the live percentage).
        cy = label(ctx, "Text size", x, cy, w) + 4f;
        cy = stepper(ctx, theme, "font", x, cy, w,
                Math.round(state.fontScale() * 100f) + "%",
                () -> state.setFontScale(state.fontScale() - FONT_STEP),
                () -> state.setFontScale(state.fontScale() + FONT_STEP)) + ROW_GAP;

        // 4) Panel opacity stepper (-/+ around the live percentage).
        cy = label(ctx, "Panel opacity", x, cy, w) + 4f;
        cy = stepper(ctx, theme, "opacity", x, cy, w,
                Math.round(state.panelOpacity() / 255f * 100f) + "%",
                () -> state.setPanelOpacity(state.panelOpacity() - OPACITY_STEP),
                () -> state.setPanelOpacity(state.panelOpacity() + OPACITY_STEP)) + ROW_GAP;

        // 5) Behavior: a labeled on/off switch for "move while menu open" (only when wired to
        // the launcher input state; omitted in theme-only tests).
        if (input != null) {
            cy = toggleRow(ctx, theme, "move", "Move while menu open", x, cy, w,
                    input.allowMoveWhileOpen(), input::toggleAllowMoveWhileOpen) + ROW_GAP;
        }
        return Result.idle();
    }

    /**
     * A label + right-aligned {@link ToggleSwitch} that flips a boolean on click. Returns the
     * bottom y. The whole row is the hit target so it is easy to toggle.
     */
    private float toggleRow(ComponentContext ctx, MdcTheme theme, String key, String labelText,
                            float x, float y, float w, boolean on, Runnable onToggle) {
        DrawContext d = ctx.draw();
        float rowH = ToggleSwitch.HEIGHT_DP + 8f;
        boolean clicked = clickBox(ctx, key + "-toggle", x, y, w, rowH);
        if (clicked) {
            onToggle.run();
        }
        float labSize = theme.typeSizePx(TypeRole.BODY_MEDIUM);
        float baseline = y + rowH * 0.5f + labSize * 0.35f;
        d.text(FONT, labSize, x, baseline, theme.color(ColorRole.ON_SURFACE), labelText);
        ToggleSwitch.draw(d, theme, x + w - ToggleSwitch.WIDTH_DP, y + (rowH - ToggleSwitch.HEIGHT_DP) * 0.5f,
                on ? 1f : 0f);
        return y + rowH;
    }

    // ---- rows ---------------------------------------------------------------------------

    private float label(ComponentContext ctx, String text, float x, float y, float w) {
        float size = ctx.theme().typeSizePx(TypeRole.LABEL_LARGE);
        float baseline = y + LABEL_H * 0.5f + size * 0.35f;
        ctx.draw().text(FONT, size, x, baseline,
                ctx.theme().color(ColorRole.ON_SURFACE_VARIANT), text);
        return y + LABEL_H;
    }

    private float presetRow(ComponentContext ctx, MdcTheme theme, float x, float y, float w) {
        DrawContext d = ctx.draw();
        ThemeState.Preset[] presets = ThemeState.Preset.values();
        float cx = x;
        for (ThemeState.Preset p : presets) {
            boolean active = state.preset() == p;
            boolean clicked = clickBox(ctx, "preset-" + p.name(), cx, y, SWATCH, SWATCH);
            if (clicked) {
                state.setPreset(p);
            }
            // Preview: filled swatch in the preset's surface color + a dot in its text color.
            d.roundedRect(cx, y, SWATCH, SWATCH, theme.corner(ShapeSize.SMALL),
                    0xFF000000 | (p.surfaceColor() & 0x00FFFFFF));
            float dot = 8f;
            d.roundedRect(cx + (SWATCH - dot) * 0.5f, y + (SWATCH - dot) * 0.5f, dot, dot,
                    dot * 0.5f, 0xFF000000 | (p.onSurfaceColor() & 0x00FFFFFF));
            if (active) {
                // Rounded selection ring hugging the swatch's SMALL corner (+2 for the 2px
                // outset) — a sharp rectStroke here poked white corners past the rounded swatch.
                d.roundedRectStroke(cx - 2f, y - 2f, SWATCH + 4f, SWATCH + 4f,
                        theme.corner(ShapeSize.SMALL) + 2f, 2f, theme.color(ColorRole.PRIMARY));
            }
            cx += SWATCH + SWATCH_GAP;
        }
        return y + SWATCH;
    }

    private float accentRow(ComponentContext ctx, MdcTheme theme, float x, float y, float w) {
        DrawContext d = ctx.draw();
        float cx = x;
        for (int accent : ThemeState.ACCENTS) {
            boolean active = (state.accent() & 0x00FFFFFF) == (accent & 0x00FFFFFF);
            boolean clicked = clickBox(ctx, "accent-" + Integer.toHexString(accent), cx, y, SWATCH, SWATCH);
            if (clicked) {
                state.setAccent(accent);
            }
            d.roundedRect(cx, y, SWATCH, SWATCH, SWATCH * 0.5f, 0xFF000000 | (accent & 0x00FFFFFF));
            if (active) {
                // The accent swatch is a CIRCLE (radius = SWATCH/2); the ring must be circular
                // too (outset radius = (SWATCH+4)/2), not a sharp square poking white corners.
                d.roundedRectStroke(cx - 2f, y - 2f, SWATCH + 4f, SWATCH + 4f,
                        (SWATCH + 4f) * 0.5f, 2f, theme.color(ColorRole.ON_SURFACE));
            }
            cx += SWATCH + SWATCH_GAP;
        }
        return y + SWATCH;
    }

    private float stepper(ComponentContext ctx, MdcTheme theme, String key, float x, float y, float w,
                          String valueText, Runnable onMinus, Runnable onPlus) {
        DrawContext d = ctx.draw();
        float rowH = STEP_BTN;
        // Minus button on the left.
        if (stepButton(ctx, theme, key + "-minus", "-", x, y, STEP_BTN, rowH)) {
            onMinus.run();
        }
        // Plus button just right of it.
        float plusX = x + STEP_BTN + SWATCH_GAP;
        if (stepButton(ctx, theme, key + "-plus", "+", plusX, y, STEP_BTN, rowH)) {
            onPlus.run();
        }
        // Live value text to the right of the two buttons.
        float valSize = theme.typeSizePx(TypeRole.BODY_MEDIUM);
        float valBaseline = y + rowH * 0.5f + valSize * 0.35f;
        d.text(FONT, valSize, plusX + STEP_BTN + 12f, valBaseline,
                theme.color(ColorRole.ON_SURFACE), valueText);
        return y + rowH;
    }

    // ---- controls -----------------------------------------------------------------------

    /** A labeled square button (surface-variant fill + centered glyph). Returns click edge. */
    private boolean stepButton(ComponentContext ctx, MdcTheme theme, String key, String glyph,
                               float x, float y, float w, float h) {
        DrawContext d = ctx.draw();
        boolean clicked = clickBox(ctx, key, x, y, w, h);
        ClickState st = ctx.store().state(ctx.childId(key), ClickState::new);
        int fill = theme.color(ColorRole.SURFACE_VARIANT);
        d.roundedRect(x, y, w, h, theme.corner(ShapeSize.SMALL), fill);
        float hover = st.hoverAlpha();
        if (hover > 0.001f) {
            d.roundedRect(x, y, w, h, theme.corner(ShapeSize.SMALL),
                    DesktopArgb.withAlpha(theme.color(ColorRole.ON_SURFACE), 0.10f * hover));
        }
        float gs = theme.typeSizePx(TypeRole.TITLE_MEDIUM);
        d.text(FONT, gs, x + w * 0.5f - gs * 0.28f, y + h * 0.5f + gs * 0.35f,
                theme.color(ColorRole.ON_SURFACE), glyph);
        return clicked;
    }

    /**
     * Update the per-control {@link ClickState} for a rectangular hit box and return whether a
     * click completed this frame (press then release while hovering). Also drives the hover
     * ease so controls feel alive — same idiom as {@link SoftwareRow}.
     */
    private boolean clickBox(ComponentContext ctx, String key, float x, float y, float w, float h) {
        FrameInput in = ctx.input();
        ClickState st = ctx.store().state(ctx.childId(key), ClickState::new);
        boolean hovered = in.pointerX() >= x && in.pointerX() < x + w
                && in.pointerY() >= y && in.pointerY() < y + h;
        boolean down = (in.buttonMask() & PRIMARY_BUTTON) != 0;
        return st.update(hovered, down);
    }
}
