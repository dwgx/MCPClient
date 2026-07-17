package net.marcloud.mcp.dwm.desktop;

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
 * The bottom account strip: an avatar circle + account name on the left and a settings gear
 * on the right — the launcher's footer. Phase 1 shows a placeholder avatar (initial on a
 * filled circle) and a static name; the gear on the right is a clickable control that opens
 * the settings/theme view via {@code onSettings}. Draws only through {@link DrawContext},
 * so it renders identically on gl / imgui / skiko.
 */
public final class AccountBar implements Component {

    /** Footer height (DIP). */
    public static final float HEIGHT_DP = 48f;
    private static final float PAD_H_DP = 12f;
    private static final float AVATAR_DP = 28f;
    private static final float GEAR_DP = 24f;
    private static final int PRIMARY_BUTTON = 1;
    private static final FontHandle FONT = new FontHandle(0L);

    private final String accountName;
    private final Runnable onSettings;
    private final boolean settingsActive;
    private final Runnable onEditName;
    private final boolean editing;

    public AccountBar(String accountName) {
        this(accountName, null, false);
    }

    public AccountBar(String accountName, Runnable onSettings, boolean settingsActive) {
        this(accountName, onSettings, settingsActive, null, false);
    }

    /**
     * @param accountName    footer name (blank -> "Guest"); when {@code editing}, this is the
     *                       live edit buffer and a caret is drawn after it
     * @param onSettings     invoked when the gear is clicked (null = non-interactive gear)
     * @param settingsActive true when the settings view is currently showing (gear highlights)
     * @param onEditName     invoked when the name/avatar is left-clicked (enter edit mode);
     *                       null = the name is not editable
     * @param editing        true while the account name is being edited (draw a caret)
     */
    public AccountBar(String accountName, Runnable onSettings, boolean settingsActive,
                      Runnable onEditName, boolean editing) {
        this.accountName = accountName == null || accountName.isBlank() ? "Guest" : accountName;
        this.onSettings = onSettings;
        this.settingsActive = settingsActive;
        this.onEditName = onEditName;
        this.editing = editing;
    }

    @Override
    public Size measure(ComponentContext ctx) {
        return new Size(0f, HEIGHT_DP);
    }

    @Override
    public Result render(ComponentContext ctx, float x, float y, float w, float h) {
        MdcTheme theme = ctx.theme();
        DrawContext d = ctx.draw();

        // Avatar: filled circle (rounded square at full radius) with the name initial.
        float avX = x + PAD_H_DP;
        float avY = y + (h - AVATAR_DP) * 0.5f;
        d.roundedRect(avX, avY, AVATAR_DP, AVATAR_DP, AVATAR_DP * 0.5f,
                theme.color(ColorRole.PRIMARY_CONTAINER));
        String initial = accountName.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        float initSize = theme.typeSizePx(TypeRole.BODY_MEDIUM);
        d.text(FONT, initSize, avX + AVATAR_DP * 0.5f - initSize * 0.3f,
                avY + AVATAR_DP * 0.5f + initSize * 0.35f,
                theme.color(ColorRole.ON_PRIMARY_CONTAINER), initial);

        // Name + secondary line, stacked right of the avatar (Win11 account-row style):
        // name on top, a dimmer role/status line beneath.
        float nameSize = theme.typeSizePx(TypeRole.BODY_MEDIUM);
        float subSize = theme.typeSizePx(TypeRole.LABEL_LARGE) * 0.85f;
        float nameX = avX + AVATAR_DP + 10f;
        float baseline = y + h * 0.5f - subSize * 0.5f + nameSize * 0.30f;
        float subBaseline = y + h * 0.5f + nameSize * 0.5f + subSize * 0.15f;

        // The avatar + name form a clickable region that enters name-edit mode. A left-click
        // (press then release while hovered) fires onEditName; editing draws a subtle
        // highlight + a trailing caret so the user sees the live buffer.
        float regionW = GEAR_DP != 0 ? (x + w - PAD_H_DP - GEAR_DP - 6f) - avX : w;
        if (onEditName != null) {
            ClickState st = ctx.store().state(ctx.childId("name"), ClickState::new);
            FrameInput in = ctx.input();
            boolean hovered = in.pointerX() >= avX && in.pointerX() < avX + regionW
                    && in.pointerY() >= y && in.pointerY() < y + h;
            boolean down = (in.buttonMask() & PRIMARY_BUTTON) != 0;
            if (st.update(hovered, down)) {
                onEditName.run();
            }
            float hover = st.hoverAlpha();
            if ((editing || hover > 0.001f)) {
                float a = editing ? 0.10f : 0.06f * hover;
                d.roundedRect(avX - 4f, y + 4f, regionW + 8f, h - 8f, theme.corner(ShapeSize.SMALL),
                        DesktopArgb.withAlpha(theme.color(ColorRole.ON_SURFACE), a));
            }
        }
        int nameColor = editing ? theme.color(ColorRole.PRIMARY) : theme.color(ColorRole.ON_SURFACE);
        d.text(FONT, nameSize, nameX, baseline, nameColor, accountName);
        if (editing) {
            float caretX = nameX + ctx.measureText(FONT, accountName, nameSize).width() + 2f;
            d.line(caretX, baseline - nameSize, caretX, baseline + nameSize * 0.2f,
                    1.5f, theme.color(ColorRole.PRIMARY));
        } else {
            // Secondary line: a static role/status, dimmer (Win11 shows the account email here).
            d.text(FONT, subSize, nameX, subBaseline,
                    DesktopArgb.scaleAlpha(theme.color(ColorRole.ON_SURFACE_VARIANT), 0.9f),
                    "Local session");
        }

        // Settings gear on the far right: a clickable hit box that opens the theme view.
        float gearX = x + w - PAD_H_DP - GEAR_DP;
        float gearY = y + (h - GEAR_DP) * 0.5f;
        renderGear(ctx, d, theme, gearX, gearY, GEAR_DP);
        return Result.idle();
    }

    private void renderGear(ComponentContext ctx, DrawContext d, MdcTheme theme,
                            float gx, float gy, float size) {
        FrameInput in = ctx.input();
        ClickState st = ctx.store().state(ctx.childId("gear"), ClickState::new);
        boolean hovered = in.pointerX() >= gx && in.pointerX() < gx + size
                && in.pointerY() >= gy && in.pointerY() < gy + size;
        boolean down = (in.buttonMask() & PRIMARY_BUTTON) != 0;
        boolean clicked = st.update(hovered, down);
        if (clicked && onSettings != null) {
            onSettings.run();
        }

        // Hover/active highlight behind the gear.
        float hover = st.hoverAlpha();
        if (settingsActive || hover > 0.001f) {
            float a = settingsActive ? 0.14f : 0.10f * hover;
            d.roundedRect(gx - 3f, gy - 3f, size + 6f, size + 6f, theme.corner(ShapeSize.SMALL),
                    DesktopArgb.withAlpha(theme.color(ColorRole.ON_SURFACE), a));
        }

        // Clean Lucide-style gear glyph; accent when settings is open.
        int col = settingsActive
                ? theme.color(ColorRole.PRIMARY) : theme.color(ColorRole.ON_SURFACE_VARIANT);
        DeskIcons.settings(d, gx, gy, size, col);
    }
}
