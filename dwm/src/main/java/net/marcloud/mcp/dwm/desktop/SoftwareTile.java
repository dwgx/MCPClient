package net.marcloud.mcp.dwm.desktop;

import java.util.function.Consumer;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.FontHandle;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.component.ComponentContext;
import net.marcloud.mcp.dwm.theme.MdcTheme;
import net.marcloud.mcp.dwm.theme.MdcTheme.ColorRole;
import net.marcloud.mcp.dwm.theme.MdcTheme.ShapeSize;
import net.marcloud.mcp.dwm.theme.MdcTheme.TypeRole;

/**
 * One software as a Pinned/grid tile: a large icon square with the name below (the
 * launcher's grid view). Clicking toggles the backing chip via the callback. Hover eases a
 * highlight behind the tile (retained in {@link ClickState}). Draws only through
 * {@link DrawContext} — identical on gl / imgui / skiko.
 */
public final class SoftwareTile implements Component {

    /** Tile total width (DIP). */
    public static final float WIDTH_DP = 76f;
    /** Tile total height (DIP). */
    public static final float HEIGHT_DP = 72f;
    /** Icon square side (DIP). */
    public static final float ICON_DP = 36f;
    private static final int PRIMARY_BUTTON = 1;
    private static final int SECONDARY_BUTTON = 1 << 1;
    private static final FontHandle FONT = new FontHandle(0L);

    private final SoftwareView view;
    private final Consumer<SoftwareView> onToggle;
    private final Consumer<SoftwareView> onPin;
    private final boolean pinned;

    public SoftwareTile(SoftwareView view, Consumer<SoftwareView> onToggle) {
        this(view, onToggle, null, false);
    }

    /**
     * @param view     the software to render
     * @param onToggle invoked on left-click (toggle the chip)
     * @param onPin    invoked on right-click (pin/unpin); null = no pin action
     * @param pinned   whether this software is currently pinned (renders a pin marker)
     */
    public SoftwareTile(SoftwareView view, Consumer<SoftwareView> onToggle,
                        Consumer<SoftwareView> onPin, boolean pinned) {
        this.view = view;
        this.onToggle = onToggle;
        this.onPin = onPin;
        this.pinned = pinned;
    }

    public SoftwareView view() {
        return view;
    }

    @Override
    public Size measure(ComponentContext ctx) {
        return new Size(WIDTH_DP, HEIGHT_DP);
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

        // Hover highlight behind the whole tile.
        float hover = st.hoverAlpha();
        if (hover > 0.001f) {
            d.roundedRect(x, y, w, h, theme.corner(ShapeSize.MEDIUM),
                    DesktopArgb.withAlpha(theme.color(ColorRole.ON_SURFACE), 0.08f * hover));
        }

        // Pin marker in the top-right corner when pinned (right-click toggles it).
        if (pinned) {
            float pin = 6f;
            d.roundedRect(x + w - pin - 6f, y + 6f, pin, pin, pin * 0.5f,
                    theme.color(ColorRole.SECONDARY));
        }

        // Icon square, horizontally centered near the top.
        float iconX = x + (w - ICON_DP) * 0.5f;
        float iconY = y + 8f;
        int tile = view.enabled() ? theme.color(ColorRole.PRIMARY) : theme.color(ColorRole.SURFACE_VARIANT);
        d.roundedRect(iconX, iconY, ICON_DP, ICON_DP, theme.corner(ShapeSize.SMALL), tile);
        String initial = view.displayName().isEmpty()
                ? "?" : view.displayName().substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        float initSize = theme.typeSizePx(TypeRole.TITLE_MEDIUM);
        int initColor = view.enabled()
                ? theme.color(ColorRole.ON_PRIMARY) : theme.color(ColorRole.ON_SURFACE_VARIANT);
        d.text(FONT, initSize, iconX + ICON_DP * 0.5f - initSize * 0.3f,
                iconY + ICON_DP * 0.5f + initSize * 0.35f, initColor, initial);

        // Name centered below the icon, ELLIPSIZED to the tile width so long chip ids
        // ("CoordinatesHudChip") shrink to "Coordinat…" instead of overflowing/colliding
        // with the neighbouring tile.
        float nameSize = theme.typeSizePx(TypeRole.LABEL_LARGE);
        float avail = w - 4f; // small side padding
        String label = ellipsize(ctx, view.displayName(), nameSize, avail);
        float nameW = ctx.measureText(FONT, label, nameSize).width();
        float nameX = x + (w - nameW) * 0.5f;
        float nameY = iconY + ICON_DP + nameSize + 4f;
        d.text(FONT, nameSize, nameX, nameY, theme.color(ColorRole.ON_SURFACE), label);
        return new Result(hovered, st.holding(), clicked);
    }

    /**
     * Trim {@code text} with a trailing ellipsis so it fits within {@code maxWidth} at
     * {@code sizePx}, measured through the active backend's real metrics. Returns the input
     * unchanged when it already fits; never returns null.
     */
    private static String ellipsize(ComponentContext ctx, String text, float sizePx, float maxWidth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (ctx.measureText(FONT, text, sizePx).width() <= maxWidth) {
            return text;
        }
        String ell = "…"; // …
        // Shrink from the end until the text + ellipsis fits.
        for (int len = text.length() - 1; len > 0; len--) {
            String candidate = text.substring(0, len) + ell;
            if (ctx.measureText(FONT, candidate, sizePx).width() <= maxWidth) {
                return candidate;
            }
        }
        return ell;
    }

    private static boolean hit(float px, float py, float x, float y, float w, float h) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }
}
