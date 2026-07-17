package net.marcloud.mcp.dwm.desktop;

import java.util.function.Consumer;

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
 * One software entry as a full-width list row (the launcher's List view): a small icon
 * tile on the left, the display name, and an on/off state dot on the right. Clicking the
 * row toggles the backing chip via the supplied callback. Hover eases a subtle
 * surface-variant highlight (retained in {@link ClickState}).
 *
 * <p>Stateless renderer in the immediate-mode sense: all retained state (hover ease, click
 * edge) lives in the {@link net.marcloud.mcp.dwm.compositor.UiStateStore} under this row's
 * {@link WidgetId}. Draws only through {@link DrawContext}, so it renders identically on
 * gl / imgui / skiko.
 */
public final class SoftwareRow implements Component {

    /** MD3-ish list-row height (DIP). */
    public static final float HEIGHT_DP = 44f;
    /** Left/right inner padding (DIP). */
    public static final float PAD_H_DP = 12f;
    /** Icon tile side (DIP). */
    public static final float ICON_DP = 28f;
    private static final int PRIMARY_BUTTON = 1;
    private static final FontHandle FONT = new FontHandle(0L);

    private static final int SECONDARY_BUTTON = 1 << 1;

    private final SoftwareView view;
    private final Consumer<SoftwareView> onToggle;
    private final Consumer<SoftwareView> onPin;
    private final boolean pinned;

    /**
     * @param view     the software to render (name/icon/enabled snapshot)
     * @param onToggle invoked with {@link #view} when the row is left-clicked (toggle the chip)
     */
    public SoftwareRow(SoftwareView view, Consumer<SoftwareView> onToggle) {
        this(view, onToggle, null, false);
    }

    /**
     * @param view     the software to render (name/icon/enabled snapshot)
     * @param onToggle invoked when the row is left-clicked (toggle the chip)
     * @param onPin    invoked when the row is right-clicked (pin/unpin); null = no pin action
     * @param pinned   whether this software is currently pinned (renders a pin marker)
     */
    public SoftwareRow(SoftwareView view, Consumer<SoftwareView> onToggle,
                       Consumer<SoftwareView> onPin, boolean pinned) {
        this.view = view;
        this.onToggle = onToggle;
        this.onPin = onPin;
        this.pinned = pinned;
    }

    /** The software this row renders. */
    public SoftwareView view() {
        return view;
    }

    @Override
    public Size measure(ComponentContext ctx) {
        // Full available width is given by the parent; intrinsic width is icon + name.
        float nameW = ctx.measureText(FONT, view.displayName(),
                ctx.theme().typeSizePx(TypeRole.BODY_MEDIUM)).width();
        return new Size(PAD_H_DP * 2f + ICON_DP + 8f + nameW + 24f, HEIGHT_DP);
    }

    @Override
    public Result render(ComponentContext ctx, float x, float y, float w, float h) {
        MdcTheme theme = ctx.theme();
        DrawContext d = ctx.draw();
        FrameInput in = ctx.input();

        ClickState st = ctx.store().state(ctx.childId("click"), ClickState::new);
        boolean hovered = hit(in.pointerX(), in.pointerY(), x, y, w, h);
        boolean down = (in.buttonMask() & PRIMARY_BUTTON) != 0;
        boolean clicked = st.update(hovered, down);
        if (clicked && onToggle != null) {
            onToggle.accept(view);
        }
        boolean secondaryDown = (in.buttonMask() & SECONDARY_BUTTON) != 0;
        if (st.updateSecondary(hovered, secondaryDown) && onPin != null) {
            onPin.accept(view); // right-click pins/unpins
        }
        renderRow(ctx, d, theme, st, x, y, w, h);
        return new Result(hovered, st.holding(), clicked);
    }

    private void renderRow(ComponentContext ctx, DrawContext d, MdcTheme theme,
                           ClickState st, float x, float y, float w, float h) {
        float radius = theme.corner(ShapeSize.SMALL);

        // Hover highlight: surface-variant fill eased in behind the whole row.
        float hover = st.hoverAlpha();
        if (hover > 0.001f) {
            d.roundedRect(x, y, w, h, radius,
                    DesktopArgb.withAlpha(theme.color(ColorRole.ON_SURFACE), 0.08f * hover));
        }

        // Icon tile: a rounded square placeholder (primary-container fill + name initial)
        // until a real icon set lands. Vertically centered, left-padded.
        float iconX = x + PAD_H_DP;
        float iconY = y + (h - ICON_DP) * 0.5f;
        int tileColor = view.enabled()
                ? theme.color(ColorRole.PRIMARY)
                : theme.color(ColorRole.SURFACE_VARIANT);
        d.roundedRect(iconX, iconY, ICON_DP, ICON_DP, theme.corner(ShapeSize.SMALL), tileColor);
        String initial = view.displayName().isEmpty()
                ? "?" : view.displayName().substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        float initSize = theme.typeSizePx(TypeRole.BODY_MEDIUM);
        int initColor = view.enabled()
                ? theme.color(ColorRole.ON_PRIMARY) : theme.color(ColorRole.ON_SURFACE_VARIANT);
        d.text(FONT, initSize, iconX + ICON_DP * 0.5f - initSize * 0.3f,
                iconY + ICON_DP * 0.5f + initSize * 0.35f, initColor, initial);

        // Name: body text, vertically centered, right of the icon.
        float nameSize = theme.typeSizePx(TypeRole.BODY_MEDIUM);
        float nameX = iconX + ICON_DP + 8f;
        float baseline = y + h * 0.5f + nameSize * 0.35f;
        d.text(FONT, nameSize, nameX, baseline, theme.color(ColorRole.ON_SURFACE), view.displayName());

        // On/off SWITCH on the far right: a pill track + sliding knob, accent when enabled.
        float swX = x + w - PAD_H_DP - ToggleSwitch.WIDTH_DP;
        float swY = y + (h - ToggleSwitch.HEIGHT_DP) * 0.5f;
        ToggleSwitch.draw(d, theme, swX, swY, view.enabled() ? 1f : 0f);

        // Pin marker just left of the switch when pinned (right-click toggles it).
        if (pinned) {
            float pin = 6f;
            float pinX = swX - 10f - pin;
            float pinY = y + (h - pin) * 0.5f;
            d.roundedRect(pinX, pinY, pin, pin, pin * 0.5f, theme.color(ColorRole.SECONDARY));
        }
    }

    private static boolean hit(float px, float py, float x, float y, float w, float h) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }
}
