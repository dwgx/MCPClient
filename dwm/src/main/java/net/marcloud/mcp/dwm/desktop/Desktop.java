package net.marcloud.mcp.dwm.desktop;

import java.util.List;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.component.ComponentContext;
import net.marcloud.mcp.dwm.theme.MdcTheme;
import net.marcloud.mcp.dwm.theme.MdcTheme.ColorRole;
import net.marcloud.mcp.dwm.theme.MdcTheme.ShapeSize;

/**
 * The Desktop launcher root — a Win11-start-menu-style software launcher rendered on the
 * DWM component stack (the "integrated graphics" that draws the board's chips as
 * software). Layout, top to bottom: a search bar, a Pinned tile grid, an "All" section of
 * software rows/tiles grouped by category, and a bottom account bar. Everything draws only
 * through {@link DrawContext}, so the SAME launcher renders on gl / imgui / skiko.
 *
 * <p>Phase 2 (live): fed a {@link SoftwareCatalog} backed by the board {@link ChipBridge},
 * so the software list is the real chip roster read fresh each frame and clicking a
 * row/tile toggles the real board chip. When board is offline the roster is empty and the
 * launcher shows a hint instead of a list. (A fixed-catalog constructor with a null bridge
 * still exists for tests / headless, falling back to the {@link FakeSoftware} model.)
 *
 * <p>This is the replacement overlay root for {@code KernelStatePanel}; the kernel-state
 * view becomes one built-in software inside the launcher in a later phase.
 */
public final class Desktop implements Component {

    private static final float MARGIN_DP = 16f;
    private static final float GAP_DP = 8f;
    /** Fraction of screen height the panel occupies (center-bottom float, not full height). */
    private static final float PANEL_H_FRAC = 0.82f;

    private final SoftwareCatalog catalog;
    private final net.marcloud.mcp.dwm.desktop.theme.ThemeState themeState;
    private final ChipBridge bridge;
    /**
     * The launcher's open state as of the last rendered frame — published so the backend
     * entry point (which reflects MC) can grab/ungrab the mouse cursor on open/close
     * transitions WITHOUT dwm importing any MC type. Volatile: written on the render thread,
     * read by the same thread's frame sink right after.
     */
    private volatile boolean lastOpen;
    /** Published each frame: whether move-while-open is allowed (backend skips the freezing
     *  GuiScreen when true so the player can still walk). */
    private volatile boolean lastAllowMove;

    /** Fixed-catalog launcher (tests): no live bridge; toggles hit the fake model. */
    public Desktop(SoftwareCatalog catalog,
                   net.marcloud.mcp.dwm.desktop.theme.ThemeState themeState) {
        this(catalog, themeState, null);
    }

    /**
     * Live launcher: {@code bridge} routes a row/tile click to the real board chip toggle.
     * A null bridge (tests / fixed catalog) falls back to flipping the {@link FakeSoftware}
     * model so the click path is still observable headless.
     */
    public Desktop(SoftwareCatalog catalog,
                   net.marcloud.mcp.dwm.desktop.theme.ThemeState themeState,
                   ChipBridge bridge) {
        this.catalog = catalog;
        this.themeState = themeState == null
                ? new net.marcloud.mcp.dwm.desktop.theme.ThemeState() : themeState;
        this.bridge = bridge;
    }

    /** The catalog this launcher renders (source of software + query/pin/layout state). */
    public SoftwareCatalog catalog() {
        return catalog;
    }

    /**
     * Whether the launcher was open as of the last rendered frame. The backend entry point
     * polls this each frame to grab/ungrab the OS mouse cursor via MC on open/close
     * transitions (dwm itself never touches an MC type — that stays in the reflective
     * backend layer).
     */
    public boolean isLauncherOpen() {
        return lastOpen;
    }

    /**
     * Whether move-while-open is enabled as of the last frame. When true the backend must NOT
     * attach the game-pausing modal GuiScreen (the player keeps walking); it still frees the
     * cursor + freezes the camera look so the mouse drives the launcher, not the view.
     */
    public boolean isMoveWhileOpen() {
        return lastAllowMove;
    }

    /** The live theme state a settings panel edits to recolor the launcher. */
    public net.marcloud.mcp.dwm.desktop.theme.ThemeState themeState() {
        return themeState;
    }

    @Override
    public Size measure(ComponentContext ctx) {
        // The launcher fills the surface it is given; no intrinsic size.
        return new Size(0f, 0f);
    }

    @Override
    public Result render(ComponentContext ctx, float x, float y, float w, float h) {
        MdcTheme theme = ctx.theme();
        DrawContext d = ctx.draw();

        // Process keyboard edges: RShift toggles open, typing edits the search query while
        // open. State is retained in the store keyed under this launcher's id.
        DesktopInputState in = ctx.store().state(ctx.childId("input"), DesktopInputState::new);
        in.update(ctx.input().keyEvents());
        catalog.setQuery(in.query());
        lastOpen = in.isOpen(); // publish for the backend's mouse-focus management
        lastAllowMove = in.allowMoveWhileOpen();
        if (!in.isOpen()) {
            return Result.idle(); // launcher hidden — draw nothing (RShift to show)
        }
        boolean settings = in.isSettings();

        // Shell: a floating launcher panel, CENTERED horizontally and sitting toward the
        // BOTTOM (Win11 start-menu style) — not a full-height left dock. Fixed launcher
        // width, ~82% height, with equal side margins and a bottom gap so it floats.
        float panelW = clamp(w * 0.62f, 420f, 640f);
        float panelH = h * PANEL_H_FRAC;
        float panelX = x + (w - panelW) * 0.5f;
        // Center-bottom float: put the panel toward the bottom with a small gap under it,
        // and a larger gap above, so it reads as a start-menu popup rather than a full dock.
        float freeV = h - panelH;
        float panelY = y + Math.max(8f, freeV * 0.72f);
        float radius = theme.corner(ShapeSize.EXTRA_LARGE);

        // Acrylic base: a translucent surface fill (skiko can add a true blur pass later; gl
        // and imgui approximate with this translucent dark layer — honest degradation).
        // NOTE: no rectStroke border — a SHARP rectangular stroke over the ROUNDED panel left
        // white L-shaped bits poking past each rounded corner ("边缘白色没扣干净"). The rounded
        // fill alone defines the panel edge cleanly; DrawContext has no rounded-rect stroke.
        int panelAlpha = theme instanceof net.marcloud.mcp.dwm.desktop.theme.DesktopTheme dt
                ? dt.panelAlpha() : 0xE6;
        int base = DesktopArgb.withAlpha(theme.color(ColorRole.SURFACE), panelAlpha / 255f);
        d.roundedRect(panelX, panelY, panelW, panelH, radius, base);

        float innerX = panelX + MARGIN_DP;
        float innerW = panelW - MARGIN_DP * 2f;
        float cursorY = panelY + MARGIN_DP;
        float footerTop = panelY + panelH - AccountBar.HEIGHT_DP;

        if (settings) {
            // Settings/theme view: a title, then the theme editor body, in place of the list.
            cursorY = place(ctx, new SectionHeader("Settings", "ESC to close"),
                    innerX, cursorY, innerW, SectionHeader.HEIGHT_DP) + GAP_DP * 2f;
            place(ctx, new SettingsPanel(themeState, in), innerX, cursorY, innerW,
                    footerTop - cursorY - GAP_DP);
        } else {
            // 1) Search bar.
            cursorY = place(ctx, new SearchBar(catalog.query(), "Search for apps, settings…"),
                    innerX, cursorY, innerW, SearchBar.HEIGHT_DP) + GAP_DP * 2f;

            // 2) Pinned section (tiles), only when there are pinned apps.
            List<SoftwareView> pinnedViews = catalog.pinnedViews();
            if (!pinnedViews.isEmpty()) {
                cursorY = place(ctx, new SectionHeader("Pinned", ""), innerX, cursorY, innerW,
                        SectionHeader.HEIGHT_DP) + GAP_DP;
                cursorY = renderTileGrid(ctx, pinnedViews, "pin", innerX, cursorY, innerW) + GAP_DP * 2f;
            }

            // 3) All section header + software rows grouped by category (reserve footer space).
            // The "View:" action label toggles LIST<->GRID for the All section.
            cursorY = place(ctx, new SectionHeader("All",
                            catalog.layout() == SoftwareCatalog.Layout.LIST ? "View: List" : "View: Grid",
                            catalog::toggleLayout),
                    innerX, cursorY, innerW, SectionHeader.HEIGHT_DP) + GAP_DP;
            renderAll(ctx, innerX, cursorY, innerW, footerTop - cursorY - GAP_DP);
        }

        // 4) Account bar pinned to the panel bottom; the gear toggles the settings view and
        // the name/avatar is click-to-edit. While editing, show the live buffer + a caret.
        String footerName = in.isEditingName() ? in.nameBuffer() : in.accountName();
        place(ctx, new AccountBar(footerName, in::toggleSettings, settings,
                        in::beginNameEdit, in.isEditingName()),
                panelX, footerTop, panelW, AccountBar.HEIGHT_DP);
        return Result.idle();
    }

    // Helpers appended below.
    private float place(ComponentContext ctx, Component c, float x, float y, float w, float h) {
        ctx.pushId(c.getClass().getSimpleName());
        try {
            c.render(ctx, x, y, w, h);
        } finally {
            ctx.popId();
        }
        return y + h;
    }

    private float renderTileGrid(ComponentContext ctx, List<SoftwareView> views, String scope,
                                 float x, float y, float w) {
        float tileW = SoftwareTile.WIDTH_DP;
        float tileH = SoftwareTile.HEIGHT_DP;
        int perRow = Math.max(1, (int) ((w + GAP_DP) / (tileW + GAP_DP)));
        float cy = y;
        for (int i = 0; i < views.size(); i++) {
            SoftwareView v = views.get(i);
            int col = i % perRow;
            if (col == 0 && i > 0) {
                cy += tileH + GAP_DP;
            }
            float cx = x + col * (tileW + GAP_DP);
            ctx.pushId(scope + "-" + v.chipId());
            try {
                new SoftwareTile(v, this::onToggle, this::onPin, catalog.isPinned(v.chipId()))
                        .render(ctx, cx, cy, tileW, tileH);
            } finally {
                ctx.popId();
            }
        }
        return cy + tileH;
    }

    private void renderAll(ComponentContext ctx, float x, float y, float w, float maxH) {
        DrawContext d = ctx.draw();
        // Clip the scrollable "All" region so overflowing rows do not paint past it.
        d.pushClip(x, y, w, Math.max(0f, maxH));
        try {
            // Empty roster: show a hint rather than a blank region. Distinguish "board
            // offline" (bridge not published) from "no matches for the query".
            if (catalog.filtered().isEmpty()) {
                String hint = bridge != null && !bridge.isOnline()
                        ? "No software — board offline"
                        : (catalog.query().isBlank() ? "No software installed" : "No matches");
                MdcTheme theme = ctx.theme();
                float size = theme.typeSizePx(MdcTheme.TypeRole.BODY_MEDIUM);
                d.text(new net.marcloud.mcp.dwm.backend.FontHandle(0L), size, x, y + size + 4f,
                        theme.color(ColorRole.ON_SURFACE_VARIANT), hint);
                return;
            }

            // Scroll: retained offset keyed under this region. Apply the wheel only when the
            // pointer is over the region so scrolling elsewhere never moves this list. Content
            // is drawn at (cy - offset); the clip above hides the overflow. No translate
            // primitive exists, so the offset is folded into each child's y directly.
            ScrollState scroll = ctx.store().state(ctx.childId("scroll"), ScrollState::new);
            float contentH = contentHeight(w);
            boolean pointerOver = ctx.input().pointerX() >= x && ctx.input().pointerX() < x + w
                    && ctx.input().pointerY() >= y && ctx.input().pointerY() < y + maxH;
            float wheel = pointerOver ? ctx.input().scrollY() : 0f;
            float offset = scroll.apply(wheel, contentH, maxH);

            float cy = y - offset;
            if (catalog.layout() == SoftwareCatalog.Layout.GRID) {
                renderTileGrid(ctx, catalog.filtered(), "all", x, cy, w);
                return;
            }
            // LIST: group headers + rows per category, each culled when fully outside the band.
            for (var group : catalog.grouped().entrySet()) {
                if (visible(cy, SectionHeader.HEIGHT_DP, y, maxH)) {
                    ctx.pushId("grp-" + group.getKey());
                    try {
                        new SectionHeader(group.getKey(), "").render(ctx, x, cy, w, SectionHeader.HEIGHT_DP);
                    } finally {
                        ctx.popId();
                    }
                }
                cy += SectionHeader.HEIGHT_DP;
                for (SoftwareView v : group.getValue()) {
                    if (visible(cy, SoftwareRow.HEIGHT_DP, y, maxH)) {
                        ctx.pushId("row-" + v.chipId());
                        try {
                            new SoftwareRow(v, this::onToggle, this::onPin, catalog.isPinned(v.chipId()))
                                    .render(ctx, x, cy, w, SoftwareRow.HEIGHT_DP);
                        } finally {
                            ctx.popId();
                        }
                    }
                    cy += SoftwareRow.HEIGHT_DP;
                }
            }
        } finally {
            d.popClip();
        }
    }

    /**
     * Total height of the "All" content for scroll clamping. GRID must use the REAL per-row
     * tile count (derived from the available width exactly as {@link #renderTileGrid} does),
     * not a one-per-row upper bound — otherwise a grid that fully fits still reports a huge
     * height and the user can scroll it entirely off-screen (review F1).
     */
    private float contentHeight(float w) {
        if (catalog.layout() == SoftwareCatalog.Layout.GRID) {
            int n = catalog.filtered().size();
            if (n == 0) {
                return 0f;
            }
            int perRow = Math.max(1, (int) ((w + GAP_DP) / (SoftwareTile.WIDTH_DP + GAP_DP)));
            int rows = (n + perRow - 1) / perRow;
            return rows * (SoftwareTile.HEIGHT_DP + GAP_DP);
        }
        float h = 0f;
        for (var group : catalog.grouped().entrySet()) {
            h += SectionHeader.HEIGHT_DP + group.getValue().size() * SoftwareRow.HEIGHT_DP;
        }
        return h;
    }

    /** True if a child of height {@code childH} at {@code cy} intersects the [y, y+maxH] band. */
    private static boolean visible(float cy, float childH, float y, float maxH) {
        return cy + childH > y && cy < y + maxH;
    }

    /**
     * Click handler for a software row/tile. Phase 1 flips the fake model's enabled flag so
     * the click path is visibly proven; phase 2 replaces this with a reflective
     * {@code chip.toggle()} through the board bridge.
     */
    private void onToggle(SoftwareView v) {
        if (bridge != null) {
            bridge.toggle(v.chipId()); // phase 2: toggle the real board chip
        } else {
            FakeSoftware.toggle(v.chipId()); // fixed-catalog fallback (tests/headless)
        }
    }

    /** Right-click handler: pin/unpin the software in the catalog (UI-only state). */
    private void onPin(SoftwareView v) {
        catalog.togglePin(v.chipId());
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
