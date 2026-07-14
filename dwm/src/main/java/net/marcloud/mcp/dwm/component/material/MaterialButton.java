package net.marcloud.mcp.dwm.component.material;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.FontHandle;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.component.ComponentContext;
import net.marcloud.mcp.dwm.compositor.WidgetId;
import net.marcloud.mcp.dwm.theme.MdcTheme;
import net.marcloud.mcp.dwm.theme.MdcTheme.ColorRole;
import net.marcloud.mcp.dwm.theme.MdcTheme.ShapeSize;
import net.marcloud.mcp.dwm.theme.MdcTheme.TypeRole;

/**
 * MD3 Button (MDC-parity). Stateless renderer: configuration only on the
 * instance; ripple / state-layer timelines live in
 * {@link net.marcloud.mcp.dwm.compositor.UiStateStore} under child
 * {@link WidgetId}s. All colors, corner radius, and type size come from
 * {@link MdcTheme} — no hardcoded theme values.
 *
 * <p>Variants map to MD3 button styles (Filled first; Tonal / Outlined / Text /
 * Elevated as siblings). Drawing goes exclusively through
 * {@link ComponentContext#draw()}.
 */
public final class MaterialButton implements Component {

    /** MD3 button visual variants. */
    public enum Variant {
        FILLED,
        TONAL,
        OUTLINED,
        TEXT,
        ELEVATED
    }

    /** Primary mouse / touch bit in {@link FrameInput#buttonMask()}. */
    public static final int PRIMARY_BUTTON = 1;

    /** MD3 filled button height (DIP). */
    public static final float HEIGHT_DP = 40f;

    /** Horizontal padding for labeled buttons (DIP). */
    public static final float PAD_H_DP = 24f;

    /** Outline stroke for Outlined variant (DIP). */
    public static final float OUTLINE_THICKNESS_DP = 1f;

    /** Rest elevation for Elevated variant (dp). */
    public static final float ELEVATED_REST_DP = 1f;

    /** Monospace-ish advance used when no backend text measure is on the context. */
    private static final float TEXT_ADVANCE = 0.6f;

    /** Default font handle; backends map 0 to their default face. */
    private static final FontHandle DEFAULT_FONT = new FontHandle(0L);

    private final String label;
    private final Variant variant;
    private final boolean enabled;

    public MaterialButton(String label) {
        this(label, Variant.FILLED, true);
    }

    public MaterialButton(String label, Variant variant) {
        this(label, variant, true);
    }

    public MaterialButton(String label, Variant variant, boolean enabled) {
        this.label = label == null ? "" : label;
        this.variant = variant == null ? Variant.FILLED : variant;
        this.enabled = enabled;
    }

    public String label() {
        return label;
    }

    public Variant variant() {
        return variant;
    }

    public boolean enabled() {
        return enabled;
    }

    @Override
    public Size measure(ComponentContext ctx) {
        MdcTheme theme = ctx.theme();
        float sizePx = theme.typeSizePx(TypeRole.LABEL_LARGE);
        // Measure through the context's backend-consistent metrics, not a per-backend
        // guess, so the pill width is the same on gl / imgui / skiko.
        float textW = ctx.measureText(DEFAULT_FONT, label, sizePx).width();
        float padH = variant == Variant.TEXT ? 12f : PAD_H_DP;
        return new Size(padH * 2f + textW, HEIGHT_DP);
    }

    @Override
    public Result render(ComponentContext ctx, float x, float y, float w, float h) {
        MdcTheme theme = ctx.theme();
        DrawContext dc = ctx.draw();
        FrameInput in = ctx.input();

        // Retained animation — never stored on this instance.
        WidgetId rippleId = ctx.childId("ripple");
        WidgetId layerId = ctx.childId("stateLayer");
        RippleState ripple = ctx.store().state(rippleId, RippleState::new);
        StateLayerState layer = ctx.store().state(layerId, StateLayerState::new);

        if (variant == Variant.ELEVATED) {
            layer.setBaseElevationDp(ELEVATED_REST_DP);
        } else {
            layer.setBaseElevationDp(0f);
        }

        boolean hovered = enabled && hit(in.pointerX(), in.pointerY(), x, y, w, h);
        boolean pointerDown = enabled && (in.buttonMask() & PRIMARY_BUTTON) != 0;
        boolean pressed = hovered && pointerDown;

        // Press / release edges for ripple + click.
        boolean clicked = false;
        if (pressed && !ripple.holding()) {
            float lx = in.pointerX() - x;
            float ly = in.pointerY() - y;
            ripple.press(lx, ly);
        } else if (ripple.holding() && !pointerDown) {
            ripple.release();
            clicked = hovered;
        } else if (ripple.holding() && pointerDown && !hovered) {
            // Dragged off: still holding for ripple, no click until release.
        }

        StateLayerState.Interaction interaction = StateLayerState.Interaction.NONE;
        if (enabled) {
            if (pressed) {
                interaction = StateLayerState.Interaction.PRESSED;
            } else if (hovered) {
                interaction = StateLayerState.Interaction.HOVERED;
            }
        }
        layer.setInteraction(interaction);

        float corner = theme.corner(ShapeSize.FULL);
        // Pill: radius must cover half the short side; theme FULL may already be large.
        float radius = Math.min(corner, Math.min(w, h) * 0.5f);

        Colors colors = colorsFor(theme, variant);
        float contentAlpha = enabled ? 1f : 0.38f;

        // 1) Container (Elevated draws a flat stand-in; true shadow is backend-caps later).
        if (colors.fillContainer) {
            int container = Argb.scaleAlpha(colors.container, contentAlpha);
            dc.roundedRect(x, y, w, h, radius, container);
        }

        // 2) Outline (Outlined).
        if (colors.stroke) {
            int outline = Argb.scaleAlpha(theme.color(ColorRole.OUTLINE), contentAlpha);
            dc.rectStroke(x, y, w, h, OUTLINE_THICKNESS_DP, outline);
        }

        // 3) State layer + ripple, clipped to shape.
        dc.pushClip(x, y, w, h);
        float layerA = layer.layerAlpha();
        if (layerA > 0.001f && enabled) {
            int sl = Argb.withAlpha(colors.onContainer, layerA);
            dc.roundedRect(x, y, w, h, radius, sl);
        }
        if (ripple.visible() && enabled) {
            float maxR = RippleState.coverageRadius(
                    ripple.originX(), ripple.originY(), w, h);
            ripple.setMaxRadius(maxR);
            float rr = ripple.radius();
            if (rr > 0.5f) {
                int rc = Argb.withAlpha(colors.onContainer, ripple.alpha());
                // Circle via equal-radius rounded rect (path-free, all backends).
                dc.roundedRect(
                        x + ripple.originX() - rr,
                        y + ripple.originY() - rr,
                        rr * 2f,
                        rr * 2f,
                        rr,
                        rc);
            }
        }
        dc.popClip();

        // 4) Label (Label Large), centered.
        float sizePx = theme.typeSizePx(TypeRole.LABEL_LARGE);
        float textW = ctx.measureText(DEFAULT_FONT, label, sizePx).width();
        float tx = x + (w - textW) * 0.5f;
        // Baseline approx: vertical center using 0.8 ascent / 0.2 descent (NullBackend).
        float ty = y + (h + sizePx * 0.6f) * 0.5f - sizePx * 0.2f;
        int on = Argb.scaleAlpha(colors.onContainer, contentAlpha);
        dc.text(DEFAULT_FONT, sizePx, tx, ty, on, label);

        return new Result(hovered, pressed, clicked);
    }

    private static boolean hit(float px, float py, float x, float y, float w, float h) {
        return px >= x && py >= y && px < x + w && py < y + h;
    }

    static float approxTextWidth(CharSequence s, float sizePx) {
        int n = s == null ? 0 : s.length();
        return n * sizePx * TEXT_ADVANCE;
    }

    private static Colors colorsFor(MdcTheme theme, Variant variant) {
        return switch (variant) {
            case FILLED -> new Colors(
                    theme.color(ColorRole.PRIMARY),
                    theme.color(ColorRole.ON_PRIMARY),
                    true,
                    false);
            case TONAL -> new Colors(
                    theme.color(ColorRole.PRIMARY_CONTAINER),
                    theme.color(ColorRole.ON_PRIMARY_CONTAINER),
                    true,
                    false);
            case OUTLINED -> new Colors(
                    theme.color(ColorRole.SURFACE),
                    theme.color(ColorRole.PRIMARY),
                    false,
                    true);
            case TEXT -> new Colors(
                    theme.color(ColorRole.SURFACE),
                    theme.color(ColorRole.PRIMARY),
                    false,
                    false);
            case ELEVATED -> new Colors(
                    theme.color(ColorRole.SURFACE),
                    theme.color(ColorRole.PRIMARY),
                    true,
                    false);
        };
    }

    private record Colors(int container, int onContainer, boolean fillContainer, boolean stroke) {}
}
