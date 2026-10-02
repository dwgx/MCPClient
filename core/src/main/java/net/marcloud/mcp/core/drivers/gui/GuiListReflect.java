package net.marcloud.mcp.core.drivers.gui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.resources.ResourcePackListEntry;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.stats.StatBase;
import net.minecraft.stats.StatFileWriter;
import net.minecraft.stats.StatList;
import net.minecraft.util.IChatComponent;

/**
 * Turns the scrollable lists a vanilla screen owns into addressable elements: one
 * {@link GuiElement#KIND_LIST list} surface per {@link GuiSlot}, plus one
 * {@link GuiElement#KIND_ROW row} element per row that is CURRENTLY ON SCREEN.
 *
 * <p><b>Why rows are what was missing.</b> Every vanilla list lives in its own
 * field, never in {@code buttonList}: {@code GuiControls.keyBindingList},
 * {@code GuiScreenResourcePacks.availableResourcePacksList} /
 * {@code selectedResourcePacksList}, {@code GuiVideoSettings.optionsRowList},
 * {@code GuiStats.displaySlot}, {@code ServerSelectionList}. An element extractor
 * that reads {@code buttonList} cannot see any of them, which is why the Controls
 * screen was openable but its key bindings were not rebindable.
 *
 * <p><b>How a row is found — declaration order, not geometry.</b> Lists are
 * discovered by walking the screen's class hierarchy and reading every DECLARED
 * field whose type is assignable to {@link GuiSlot}, in declaration order. The
 * index in that walk is the list's identity ({@code w0}, {@code w1}, ...) and is
 * stable for as long as the screen object lives, which is exactly the lifetime of
 * an {@code (epoch, fingerprint)} reference. A list held in a collection, created
 * lazily or typed as something other than a {@code GuiSlot} is missed — the same
 * documented heuristic limit {@code GuiReflect.extractTextFields} already has.
 *
 * <p><b>The scroll problem, stated and answered.</b> A screen-row id ("row 3") is
 * a stale reference by construction: row 3 at scroll 0 and row 3 at scroll 40 are
 * different controls, and a click aimed at the first would land on the second. So:
 * <ul>
 *   <li>A row id is {@code r{L}:{rowIndex}} and names a row of the list's DATA,
 *       not a screen position. Scrolling therefore does not change what it means:
 *       {@code r0:17} is "Forward" before and after a scroll. This is the whole
 *       answer to the staleness question — the reference simply never names a
 *       pixel.</li>
 *   <li>Only rows that are on screen are emitted, so the set of addressable ids
 *       changes exactly when the scroll changes what the player can see. An
 *       off-screen row is reached by scrolling, then re-snapshotting.</li>
 *   <li>Every row and every list carries the {@code scroll} it was resolved at,
 *       and {@code GuiSnapshotService.fingerprint} folds the scroll of every list
 *       into the snapshot's structural token — so after a scroll, an (epoch,
 *       fingerprint) pair minted before it is REFUSED_STALE rather than acted on.
 *       Scroll is structure for a list, exactly as a button's caption is
 *       structure for a screen.</li>
 *   <li>{@code GuiActions} additionally refuses a row id whose row is not on
 *       screen at the moment of the click, so no path can redirect a row click
 *       onto a neighbouring row.</li>
 * </ul>
 *
 * <p><b>Row geometry is vanilla's own arithmetic, not a re-derivation.</b> The
 * row's top edge is {@code top + 4 - (int)amountScrolled + rowIndex*slotHeight +
 * headerPadding} and its height is {@code slotHeight - 4} — the exact values
 * {@code GuiSlot.drawSelectionBox} uses to place and cull a row, and the exact
 * values {@code GuiListExtended.mouseClicked} passes to
 * {@code IGuiListEntry.mousePressed}. The published x range is the range
 * {@code GuiSlot.getSlotIndexFromScreenCoords} will accept, so the rectangle this
 * class prints IS the region in which vanilla will route a click to that row.
 *
 * <p><b>What a row's click point really is, per family.</b> Vanilla routes a row
 * click through {@code IGuiListEntry.mousePressed}, and the ENTRY decides whether
 * the point was a hit. Where the entry owns real {@link GuiButton} widgets (the
 * Controls list's "change key" / "reset" buttons, a video-settings row's two
 * option controls, a page-button row) each widget is published as its own row
 * element at the widget's LIVE position, because vanilla gives those widgets a
 * position in {@code drawEntry} and nowhere else. Where the entry has no widget
 * object (a resource-pack row, a Controls category header) the row is published
 * at its own centre and {@code widgetPositioned} is reported absent — the row is
 * addressable, and whether vanilla's family-internal hotspot arithmetic accepts
 * that centre is vanilla's answer, reported as NOT_CONFIRMED if it does not.
 *
 * <p><b>Read-only lists.</b> {@code GuiSlot} itself has no {@code mouseClicked}
 * at all: only {@link GuiListExtended} overrides it. The statistics grids
 * ({@code GuiStats.Stats}) are plain {@code GuiSlot}s, so their rows are
 * published READ-ONLY — {@code actions} is empty, {@code state.enabled} is false,
 * and {@code GuiActions} refuses a click on one by name rather than pretending.
 * Their content is still genuinely readable: the row's {@code statsHolder} entry
 * is a real {@code StatCrafting}, so the label and the formatted value are read
 * from it and from the screen's real {@link StatFileWriter}.
 *
 * <p><b>Threading.</b> Reads live game state and MUST run on the game thread.
 *
 * <p><b>Fail-loud-but-degrade.</b> Every read tolerates a missing field or method
 * and records why in the caller's {@code unreadable} sink; one bad list never
 * aborts a snapshot.
 */
public final class GuiListReflect {

    /**
     * The x offset WITHIN a resource-pack row at which vanilla will act on a press.
     *
     * <p>Read out of vanilla, not guessed and not invented per family:
     * {@code ResourcePackListEntry.mousePressed} (1.8.9) opens with
     * {@code if (this.func_148310_d() && p_148278_5_ <= 32)}, where {@code p_148278_5_}
     * is the FIFTH parameter — the row-relative x that
     * {@code GuiListExtended.mouseClicked} computes as {@code mouseX - (left +
     * width/2 - getListWidth()/2 + 2)}. Everything past 32px into a row is inert, and
     * the branch that moves a pack OUT of the selected list is narrower still
     * ({@code p_148278_5_ < 16}), so 8 — the middle of 0..16 — is the one offset that
     * satisfies every branch. A row's geometric centre is not a hotspot on a row
     * wider than 64px, which is exactly why a centre click did nothing.
     */
    public static final int PACK_ICON_REL_X = 8;
    /** Middle of the 32x32 icon vanilla draws at the row's top-left, row-relative. */
    public static final int PACK_ICON_REL_Y = 16;

    /** Id prefix of a list surface. */
    public static final String LIST_ID = "w";
    /** Id prefix of a row. */
    public static final String ROW_ID = "r";
    /** Id prefix of a header-band control. */
    public static final String HEADER_ID = "h";

    /**
     * The row-relative x ranges a statistics grid's three sort targets occupy, as the
     * half-open {@code [from, to)} pairs vanilla tests for.
     *
     * <p>Read out of vanilla, not invented:
     * {@code GuiStats.Stats.func_148132_a} (1.8.9, achievement/GuiStats.java:311)
     * assigns {@code field_148218_l = 0 / 1 / 2} for {@code relX} in
     * {@code [79,115)}, {@code [129,165)} and {@code [179,215)}, where {@code relX} is
     * the first argument {@code GuiSlot.handleMouseInput} passes as
     * {@code this.mouseX - (this.width - this.getListWidth()) / 2} — the list's left
     * edge. These are the only literals in that method, and the only override of
     * {@code func_148132_a} in the whole client, which is what makes them nameable
     * rather than guessed: a list that does not override the hook publishes no header
     * elements at all.
     */
    public static final int[][] SORT_COLUMN_REL_X = {{79, 115}, {129, 165}, {179, 215}};
    /** Provenance of {@link #SORT_COLUMN_REL_X}, published so a reader can check it. */
    public static final String SORT_RULE = "GuiStats.Stats.func_148132_a: relX in "
            + "[79,115)->col0, [129,165)->col1, [179,215)->col2; outside all three it "
            + "sets the column to -1 and does nothing";

    private GuiListReflect() {
    }

    /**
     * One {@link GuiSlot} found on a screen, with the index that names it.
     *
     * @param index     position in the screen's declaration-order walk; the id suffix
     * @param list      the live list object
     * @param fieldName the screen field it was read from, for the message an agent reads
     */
    public record ListRef(int index, GuiSlot list, String fieldName) {

        /** The element id an agent addresses this list by. */
        public String id() {
            return LIST_ID + index;
        }
    }

    // ===== DISCOVERY =====

    /**
     * Every list a screen owns, in declaration order. Null fields (built lazily, or
     * assigned only once the screen has drawn) are skipped, exactly as
     * {@code GuiReflect.extractTextFields} skips a null text field, so the index of
     * a list does not shift as a screen finishes initialising.
     *
     * <p>A list reachable through two fields is published ONCE, under the first
     * field that holds it. {@code GuiStats} keeps four grids AND a
     * {@code displaySlot} that always aliases whichever grid the tab selected, so
     * without this the Statistics screen would advertise five lists for four grids,
     * and two different ids would drive the same scroll — one of them right and the
     * other merely plausible.
     */
    public static List<ListRef> lists(GuiScreen screen, List<String> unreadable) {
        List<ListRef> out = new ArrayList<>();
        if (screen == null) {
            return out;
        }
        for (Class<?> c = screen.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!GuiSlot.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                Object v;
                try {
                    f.setAccessible(true);
                    v = f.get(screen);
                } catch (Throwable t) {
                    unreadable.add(c.getSimpleName() + "." + f.getName() + "(list): "
                            + t.getClass().getSimpleName());
                    continue;
                }
                if (v instanceof GuiSlot slot && !containsIdentical(out, slot)) {
                    out.add(new ListRef(out.size(), slot, f.getName()));
                }
            }
        }
        return out;
    }

    /** Whether {@code out} already holds this exact list object under another field. */
    private static boolean containsIdentical(List<ListRef> out, GuiSlot slot) {
        for (ListRef r : out) {
            if (r.list() == slot) {
                return true;
            }
        }
        return false;
    }

    /**
     * The list a {@code w{L}} id names, or null. The id is parsed, never guessed
     * from geometry — inferring a target list from where a click landed would
     * route a click on one list into the other (the resource-pack screen has two).
     */
    public static ListRef refFor(GuiScreen screen, int listIndex, List<String> unreadable) {
        if (screen == null || listIndex < 0) {
            return null;
        }
        List<ListRef> all = lists(screen, unreadable);
        return listIndex < all.size() ? all.get(listIndex) : null;
    }

    /** The element id of the list, or null when {@code elementId} is not a list id. */
    public static String listIdOf(String elementId) {
        int idx = listIndexOf(elementId);
        return idx < 0 ? null : LIST_ID + idx;
    }

    /** The list index a {@code w{L}} id names, or -1. */
    public static int listIndexOf(String elementId) {
        if (elementId == null || elementId.length() < 2
                || elementId.charAt(0) != LIST_ID.charAt(0)) {
            return -1;
        }
        try {
            int i = Integer.parseInt(elementId.substring(1));
            return i >= 0 ? i : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ===== ROW IDS =====

    /**
     * A parsed row reference.
     *
     * @param listIndex which list
     * @param rowIndex  which row of that list's DATA (not of the screen)
     * @param widget    which of the row's widgets, or -1 for the row itself
     */
    public record RowRef(int listIndex, int rowIndex, int widget) {

        /** The element id an agent addresses this row by. */
        public String id() {
            return widget < 0
                    ? ROW_ID + listIndex + ":" + rowIndex
                    : ROW_ID + listIndex + ":" + rowIndex + "#" + widget;
        }
    }

    /** Parse a row id, or null when it is not one. Never partially parses. */
    public static RowRef rowRefOf(String elementId) {
        if (elementId == null || elementId.length() < 4
                || elementId.charAt(0) != ROW_ID.charAt(0)) {
            return null;
        }
        int colon = elementId.indexOf(':');
        if (colon < 2) {
            return null;
        }
        int list;
        int row;
        int widget = -1;
        try {
            list = Integer.parseInt(elementId.substring(1, colon));
            int hash = elementId.indexOf('#', colon);
            String rowPart = hash < 0 ? elementId.substring(colon + 1) : elementId.substring(colon + 1, hash);
            row = Integer.parseInt(rowPart);
            if (hash >= 0) {
                widget = Integer.parseInt(elementId.substring(hash + 1));
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (list < 0 || row < 0 || widget < -1) {
            return null;
        }
        return new RowRef(list, row, widget);
    }

    /**
     * A parsed header reference.
     *
     * @param listIndex which list
     * @param column    which of the list's header targets, as vanilla numbers them
     */
    public record HeaderRef(int listIndex, int column) {

        /** The element id an agent addresses this header target by. */
        public String id() {
            return HEADER_ID + listIndex + ":" + column;
        }
    }

    /** Parse a header id, or null when it is not one. Never partially parses. */
    public static HeaderRef headerRefOf(String elementId) {
        if (elementId == null || elementId.length() < 4
                || elementId.charAt(0) != HEADER_ID.charAt(0)) {
            return null;
        }
        int colon = elementId.indexOf(':');
        if (colon < 2) {
            return null;
        }
        int list;
        int column;
        try {
            list = Integer.parseInt(elementId.substring(1, colon));
            column = Integer.parseInt(elementId.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        return list >= 0 && column >= 0 ? new HeaderRef(list, column) : null;
    }

    /**
     * Whether this list has header controls of its own, i.e. whether it OVERRIDES
     * {@code func_148132_a} — the hook {@code GuiSlot.handleMouseInput} calls for a
     * click in the band above the first row. Structural, not a class name: the
     * statistics grids are the only override in the client, and a new one would be
     * found automatically but would then need its own named ranges.
     */
    public static boolean hasHeaderControls(GuiSlot list) {
        return overridesMethod(list, "func_148132_a");
    }

    /**
     * Whether {@code target}'s own hierarchy OVERRIDES the hook, as opposed to merely
     * inheriting {@code GuiSlot}'s empty one. Checking only for a declaration anywhere
     * up the chain would say yes to every list in the game, because
     * {@code GuiSlot} declares it as a no-op — which is how the General statistics grid
     * came to publish three sort targets that do nothing.
     */
    private static boolean overridesMethod(Object target, String name) {
        for (Class<?> c = target == null ? Object.class : target.getClass();
             c != null && c != Object.class; c = c.getSuperclass()) {
            if (c == net.minecraft.client.gui.GuiSlot.class) {
                return false; // the base declaration is the empty default
            }
            try {
                c.getDeclaredMethod(name, int.class, int.class);
                return true;
            } catch (NoSuchMethodException e) {
                // keep walking
            }
        }
        return false;
    }

    /**
     * The x origin vanilla compares a header click against.
     *
     * <p>Note what is MISSING here compared with a row's accepted x range:
     * {@code GuiSlot.handleMouseInput} computes {@code (this.width -
     * this.getListWidth()) / 2} with no {@code this.left}, so for a list that has been
     * moved off the left edge vanilla's header band is measured from a different
     * origin than its rows are. The statistics grids sit at left 0, where the two
     * agree; publishing vanilla's own formula (rather than the row one) is what keeps
     * a press correct for whichever grid it is aimed at.
     */
    public static int headerLeftOrigin(GuiSlot list) {
        return (intField(list, "width", 0) - list.getListWidth()) / 2;
    }

    /**
     * The band of y a header click must land in, exclusive at the bottom: vanilla
     * routes to {@code func_148132_a} when
     * {@code k = mouseY - top - headerPadding + amountScrolled - 4 < 0}, inside
     * {@code [top, bottom]}.
     */
    public static int[] headerBandY(GuiSlot list, int scroll) {
        int top = intField(list, "top", 0);
        int bottom = intField(list, "bottom", 0);
        int hi = Math.min(bottom, top + intField(list, "headerPadding", 0) + 4 - scroll);
        return new int[] {top, hi};
    }

    // ===== EXTRACTION =====

    /**
     * Append the list surface and every on-screen row of every list the screen owns.
     * Rows that are not on screen are NOT emitted: an element the player cannot see
     * is not addressable, and inventing ids for off-screen rows would invite a click
     * at a point that lands on a different row.
     */
    public static void extract(GuiScreen screen, List<GuiElement> out, List<String> unreadable) {
        for (ListRef ref : lists(screen, unreadable)) {
            GuiSlot list = ref.list();
            int size = size(list, ref, unreadable);
            int scroll = list.getAmountScrolled();
            List<Integer> visible = visibleRows(list, size, scroll);
            out.add(listElement(ref, size, scroll, visible));
            out.addAll(headerElements(ref, scroll, unreadable));
            for (int row : visible) {
                out.addAll(rowElements(screen, ref, size, scroll, row, unreadable));
            }
        }
    }

    /**
     * The header-band controls a list owns, or none.
     *
     * <p>Published with a real rectangle so an agent does not have to know the numbers
     * in {@link #SORT_COLUMN_REL_X}: each element's x range is its literal range shifted
     * by the origin vanilla measures from, its y range is the band vanilla routes to
     * the header hook, and its click point is the middle of that rectangle.
     */
    public static List<GuiElement> headerElements(ListRef ref, int scroll,
                                                  List<String> unreadable) {
        GuiSlot list = ref.list();
        if (!hasHeaderControls(list)) {
            return List.of();
        }
        if (!list.getClass().getName().startsWith("net.minecraft.client.gui.achievement.")) {
            unreadable.add(list.getClass().getSimpleName() + " overrides func_148132_a but is "
                    + "not a known header family, so its header targets are not published: "
                    + "the x ranges are literals inside that override and cannot be read at "
                    + "runtime. Name them, as GuiListReflect.SORT_COLUMN_REL_X does for the "
                    + "statistics grids");
            return List.of();
        }
        int[] ys = headerBandY(list, scroll);
        if (ys[1] <= ys[0]) {
            return List.of();
        }
        int x0 = headerLeftOrigin(list);
        List<GuiElement> out = new ArrayList<>();
        for (int c = 0; c < SORT_COLUMN_REL_X.length; c++) {
            int from = x0 + SORT_COLUMN_REL_X[c][0];
            int to = x0 + SORT_COLUMN_REL_X[c][1];
            Bounds bounds = new Bounds(from, ys[0], Math.max(0, to - from), ys[1] - ys[0]);
            Point click = new Point(from + (to - from) / 2, (ys[0] + ys[1]) / 2);
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("listIndex", ref.index());
            attrs.put("listField", ref.fieldName());
            attrs.put("listClass", list.getClass().getSimpleName());
            attrs.put("scroll", scroll);
            attrs.put("column", c);
            attrs.put("relXFrom", SORT_COLUMN_REL_X[c][0]);
            attrs.put("relXTo", SORT_COLUMN_REL_X[c][1]);
            attrs.put("sortRule", SORT_RULE);
            attrs.put("delivery", "GuiSlot.handleMouseInput routes a click above the first "
                    + "row to func_148132_a; it is driven by parking the cursor in the "
                    + "header band and delivering one Mouse press event");
            attrs.put("hasPressHandler", Boolean.TRUE);
            out.add(new GuiElement(new HeaderRef(ref.index(), c).id(),
                    GuiElement.KIND_HEADER, GuiElement.ROLE_HEADER, "Sort column " + c, "",
                    bounds, click, new State(true, true, false, false),
                    List.of("click"), attrs));
        }
        return out;
    }

    /**
     * The rows vanilla is currently drawing, in list order.
     *
     * <p>This is {@code GuiSlot.drawSelectionBox}'s own cull test verbatim:
     * {@code k = top + 4 - (int)amountScrolled + row*slotHeight + headerPadding}
     * is drawn iff {@code k <= bottom && k + (slotHeight - 4) >= top}.
     */
    public static List<Integer> visibleRows(GuiSlot list, int size, int scroll) {
        List<Integer> out = new ArrayList<>();
        int top = intField(list, "top", 0);
        int bottom = intField(list, "bottom", 0);
        int slotHeight = list.getSlotHeight();
        int headerPadding = intField(list, "headerPadding", 0);
        int rowHeight = slotHeight - 4;
        for (int i = 0; i < size; i++) {
            int y = top + 4 - scroll + i * slotHeight + headerPadding;
            if (y > bottom || y + rowHeight < top) {
                continue;
            }
            out.add(i);
        }
        return out;
    }

    /**
     * The top edge of a row, in scaled-GUI space: the same value
     * {@code GuiSlot.drawSelectionBox} computes and {@code GuiListExtended.mouseClicked}
     * hands to the entry.
     */
    public static int rowTop(GuiSlot list, int row, int scroll) {
        return intField(list, "top", 0) + 4 - scroll + row * list.getSlotHeight()
                + intField(list, "headerPadding", 0);
    }

    /** The drawn height of a row ({@code slotHeight - 4}, vanilla's own value). */
    public static int rowHeight(GuiSlot list) {
        return list.getSlotHeight() - 4;
    }

    /**
     * The x range in which vanilla will route a click to a row: between the list's
     * left edge and its scroll bar, which is exactly what
     * {@code GuiSlot.getSlotIndexFromScreenCoords} accepts. Publishing that range
     * as the row's rectangle is what makes the published geometry checkable against
     * vanilla's own hit test instead of merely plausible.
     */
    public static int[] acceptedXRange(GuiSlot list) {
        int left = intField(list, "left", 0);
        int width = intField(list, "width", 0);
        int listWidth = list.getListWidth();
        int lo = left + width / 2 - listWidth / 2;
        int hi = left + width / 2 + listWidth / 2;
        int scrollBar = intMethod(list, "getScrollBarX", hi);
        return new int[] {lo, Math.min(hi, scrollBar)};
    }

    /**
     * The list's own rectangle: where vanilla draws it, and the area its wheel
     * branch and its {@code mouseClicked} will accept a pointer in.
     *
     * <p>Published rather than recomputed, because the driver's cursor placement has
     * to agree with the geometry the rows were published from. A helper that read
     * these four fields with a zero fallback would place the cursor at (0,0) — a
     * point outside every list — and vanilla would then decline the interaction for
     * a reason that looks like "this list does not respond".
     */
    public static Bounds listBounds(GuiSlot list) {
        int left = intField(list, "left", 0);
        int right = intField(list, "right", 0);
        int top = intField(list, "top", 0);
        int bottom = intField(list, "bottom", 0);
        return new Bounds(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    /**
     * The point a pointer must occupy for vanilla to consider the list "under the
     * cursor": the middle of its rectangle. Vanilla gates BOTH its wheel branch and
     * {@code GuiListExtended.mouseClicked} on the list's cached {@code mouseX/mouseY},
     * which only {@code drawScreen} ever sets — so a driver that does not place the
     * virtual cursor there is relying on wherever the real mouse happens to be.
     */
    public static Point cursorInside(GuiSlot list) {
        Bounds b = listBounds(list);
        return new Point(b.x() + b.w() / 2, b.y() + b.h() / 2);
    }

    /** The list surface element: the wheel target, and the shape of the whole list. */
    public static GuiElement listElement(ListRef ref, int size, int scroll,
                                         List<Integer> visible) {
        GuiSlot list = ref.list();
        int left = intField(list, "left", 0);
        int right = intField(list, "right", 0);
        int top = intField(list, "top", 0);
        int bottom = intField(list, "bottom", 0);
        Bounds bounds = new Bounds(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
        // The list centre: a point over the list, which is the condition vanilla's
        // own wheel branch tests before it will consume a scroll.
        Point click = new Point((left + right) / 2, (top + bottom) / 2);
        int maxScroll = list.func_148135_f();
        boolean rowsClickable = list instanceof GuiListExtended;

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("listIndex", ref.index());
        attrs.put("listField", ref.fieldName());
        attrs.put("listClass", list.getClass().getSimpleName());
        attrs.put("rowCount", size);
        attrs.put("scroll", scroll);
        attrs.put("maxScroll", maxScroll);
        attrs.put("scrollable", maxScroll > 0);
        attrs.put("slotHeight", list.getSlotHeight());
        attrs.put("firstVisibleRow", visible.isEmpty() ? -1 : visible.get(0));
        attrs.put("lastVisibleRow", visible.isEmpty() ? -1 : visible.get(visible.size() - 1));
        attrs.put("rowsClickable", rowsClickable);

        State state = new State(size > 0, true, false, false);
        return new GuiElement(ref.id(), GuiElement.KIND_LIST, GuiElement.ROLE_LIST,
                list.getClass().getSimpleName(), "", bounds, click, state,
                List.of("scroll"), attrs);
    }

    /**
     * The element(s) for one on-screen row: one per real widget the entry owns, or
     * a single row element when it owns none.
     */
    public static List<GuiElement> rowElements(GuiScreen screen, ListRef ref, int size,
                                               int scroll, int row, List<String> unreadable) {
        GuiSlot list = ref.list();
        Object entry = listEntry(list, row);
        // A GuiKeyBindingList over-reports its size on purpose: it sizes the entry
        // array from the GLOBAL key-binding registry while filling only the ones
        // gameSettings holds, so the tail is null. A null entry is not a row — it has
        // no object, and a click on it would NPE inside vanilla — so it is skipped
        // and the gap is stated rather than published as a clickable row.
        if (list instanceof GuiListExtended && entry == null) {
            unreadable.add(list.getClass().getSimpleName() + " row " + row
                    + ": no entry object (this list over-reports its size), so it is not a row");
            return List.of();
        }
        Content content = readContent(screen, list, size, entry, row, unreadable);
        int[] xs = acceptedXRange(list);
        int y = rowTop(list, row, scroll);
        int h = rowHeight(list);
        // Two independent reasons a row cannot be pressed, and both are real: vanilla
        // gives a plain GuiSlot no mouseClicked at all, and some entries inside a
        // GuiListExtended (the Controls list's category headers) answer false to
        // every press. Publishing either as clickable would be a promise vanilla
        // does not keep.
        boolean clickable = list instanceof GuiListExtended && content.actionable();

        Map<String, Object> base = new LinkedHashMap<>();
        // Whether the LIST has a press handler at all is a property of the list, not of
        // the row, and the driver needs it to tell "this screen cannot press rows" from
        // "this particular row refuses every press".
        base.put("hasPressHandler", list instanceof GuiListExtended);
        base.put("pressHandlerRule", "only GuiListExtended overrides GuiSlot.mouseClicked");
        base.put("listIndex", ref.index());
        base.put("listField", ref.fieldName());
        base.put("listClass", list.getClass().getSimpleName());
        base.put("rowIndex", row);
        base.put("rowCount", size);
        base.put("scroll", scroll);
        base.putAll(content.attributes());

        List<GuiElement> out = new ArrayList<>();
        List<GuiButton> widgets = entryWidgets(entry);
        if (widgets.isEmpty()) {
            Bounds bounds = new Bounds(xs[0], y, Math.max(0, xs[1] - xs[0]), h);
            Point click = new Point((xs[0] + xs[1]) / 2, y + h / 2);
            Map<String, Object> attrs = new LinkedHashMap<>(base);
            if (entry instanceof ResourcePackListEntry) {
                // The row's left edge as vanilla computes it: GuiListExtended.mouseClicked
                // passes mouseX - that edge to the entry, so a click point has to be built
                // FROM the row's left edge, not from the screen origin. Getting this
                // wrong would make the press land or miss for the wrong reason.
                int rowLeft = xs[0] + 2;
                click = new Point(rowLeft + PACK_ICON_REL_X, y + PACK_ICON_REL_Y);
                attrs.put("hotspotRelX", PACK_ICON_REL_X);
                attrs.put("hotspotRelY", PACK_ICON_REL_Y);
                attrs.put("hotspotRule", "vanilla ResourcePackListEntry.mousePressed gates "
                        + "every action on relX <= 32 and moves a selected pack out only "
                        + "for relX < 16, so the icon column is the hotspot");
            }
            State state = new State(clickable, true, content.focused(), false);
            attrs.put("clickable", clickable);
            attrs.put("contentReadable", content.readable());
            out.add(new GuiElement(new RowRef(ref.index(), row, -1).id(),
                    GuiElement.KIND_ROW, GuiElement.ROLE_ROW, content.name(), content.value(),
                    bounds, click, state, clickable ? List.of("click") : List.of(), attrs));
            return out;
        }

        for (int w = 0; w < widgets.size(); w++) {
            GuiButton b = widgets.get(w);
            int bw = intField(b, GuiButton.class, "width", 0);
            int bh = intField(b, GuiButton.class, "height", 0);
            int bx = b.xPosition;
            int by = b.yPosition;
            // Vanilla gives these widgets a position in the entry's drawEntry and
            // nowhere else, so a rect that lands inside this row's band is a real,
            // drawn position; anything else means the row has not been drawn since
            // it was built and the widget's coordinates are still their ctor zeros.
            boolean positioned = by >= y && by + bh <= y + list.getSlotHeight();
            Bounds bounds = positioned
                    ? new Bounds(bx, by, bw, bh)
                    : new Bounds(xs[0], y, Math.max(0, xs[1] - xs[0]), h);
            Point click = positioned
                    ? new Point(bx + bw / 2, by + bh / 2)
                    : new Point((xs[0] + xs[1]) / 2, y + h / 2);
            State state = new State(clickable && b.enabled, true, content.focused(),
                    boolField(b, GuiButton.class, "hovered"));
            Map<String, Object> attrs = new LinkedHashMap<>(base);
            attrs.put("widget", w);
            attrs.put("widgetKind", "button");
            attrs.put("widgetPositioned", positioned);
            attrs.put("buttonId", b.id);
            attrs.put("buttonWidth", bw);
            attrs.put("buttonHeight", bh);
            // Vanilla rewrites a key row's button label inside drawEntry (to the key
            // itself), and drawEntry needs a GL context. Until a frame has run, the
            // label is still the ctor text, so say which of the two a reader is seeing
            // rather than letting a stale caption pass for a live one.
            attrs.put("labelIsDrawnValue", positioned);
            attrs.put("clickable", clickable);
            attrs.put("contentReadable", content.readable());
            // name is the WIDGET's own label (after a draw, the change-key button's
            // label is the key itself); value is the ROW's value, so an agent choosing
            // between two rows of one list compares values, not buttons. A Controls
            // row therefore reads name='LSHIFT' value='LSHIFT' once drawn, and
            // name='key.attack' value='LSHIFT' before it has been drawn.
            String label = b.displayString == null ? "" : b.displayString;
            out.add(new GuiElement(new RowRef(ref.index(), row, w).id(),
                    GuiElement.KIND_ROW, GuiElement.ROLE_ROW, label, content.value(),
                    bounds, click, state, clickable ? List.of("click") : List.of(), attrs));
        }
        return out;
    }

    /**
     * Why a row id resolved to no element. Returns null when the id is not a row id
     * or when the row IS currently addressable, so the caller can fall through to
     * its own "no such element" message. The point is that a row that scrolled out
     * of view gets a message naming the scroll, not a bare NO_ELEMENT: that
     * difference is the difference between a recoverable mistake and an agent that
     * concludes the control does not exist.
     */
    public static String explainMissing(GuiScreen screen, String elementId,
                                        List<String> unreadable) {
        RowRef ref = rowRefOf(elementId);
        if (screen == null || ref == null) {
            return null;
        }
        ListRef target = refFor(screen, ref.listIndex(), unreadable);
        if (target == null) {
            return "'" + elementId + "' names list w" + ref.listIndex()
                    + ", and this screen has no such list (it has "
                    + lists(screen, unreadable).size() + ")";
        }
        GuiSlot list = target.list();
        int size = size(list, target, unreadable);
        int scroll = list.getAmountScrolled();
        List<Integer> visible = visibleRows(list, size, scroll);
        return "'" + elementId + "' is row " + ref.rowIndex() + " of " + target.list().getClass().getSimpleName()
                + ", which has " + size + " rows and is scrolled to " + scroll
                + "; rows " + (visible.isEmpty() ? "none" : visible.get(0) + "-" + visible.get(visible.size() - 1))
                + " are on screen now, so that row is not addressable until you scroll it into view"
                + " (gui_click_element elementId='" + target.id() + "' gesture='scroll')";
    }

    // ===== ENTRY ACCESS =====

    /** The list's row object, or null for a plain {@link GuiSlot} that has none. */
    public static Object listEntry(GuiSlot list, int row) {
        if (!(list instanceof GuiListExtended ext)) {
            return null;
        }
        try {
            return ext.getListEntry(row);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The real {@link GuiButton}s a row entry owns, in declaration order. This is the
     * same discovery {@code GuiReflect.extractTextFields} does for text fields, and
     * it is what makes a Controls row's "change key" and "reset" buttons individually
     * addressable at the positions vanilla actually drew them at.
     */
    public static List<GuiButton> entryWidgets(Object entry) {
        List<GuiButton> out = new ArrayList<>();
        if (entry == null) {
            return out;
        }
        for (Class<?> c = entry.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!GuiButton.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(entry);
                    if (v instanceof GuiButton b) {
                        out.add(b);
                    }
                } catch (Throwable ignored) {
                    // A widget we cannot read is simply not addressable; the row still is.
                }
            }
        }
        return out;
    }

    // ===== ROW CONTENT =====

    /**
     * What a row says, plus the attributes an agent chooses it by.
     *
     * @param actionable whether vanilla's own {@code mousePressed} can do anything
     *                   with a press on this row. A Controls category header is a
     *                   row an agent must be able to READ to know which section the
     *                   bindings below it belong to, and vanilla's
     *                   {@code CategoryEntry.mousePressed} returns false for it, so
     *                   publishing it as clickable would be a promise vanilla does
     *                   not keep.
     */
    private record Content(String name, String value, boolean focused, boolean readable,
                           boolean actionable, Map<String, Object> attributes) {
    }

    private static final Content BLANK = new Content("", "", false, false, true, Map.of());

    /** A row that carries a section heading and that vanilla will not accept a press on. */
    private static Content heading(String label) {
        return new Content(label, "", false, true, false,
                Map.of("rowKind", "category", "actionable", Boolean.FALSE,
                        "actionableRule", "vanilla CategoryEntry.mousePressed returns false"));
    }

    /**
     * Read a row's content from the real vanilla objects behind it.
     *
     * <p>Dispatch is on the entry's own type, never on the entry's class NAME, so a
     * renamed or added family degrades to a blank row instead of being misread as
     * another family's.
     */
    private static Content readContent(GuiScreen screen, GuiSlot list, int size, Object entry,
                                       int row, List<String> unreadable) {
        if (entry instanceof net.minecraft.client.gui.GuiKeyBindingList.CategoryEntry) {
            String label = stringFieldOr(entry, "labelText");
            return label == null ? BLANK : heading(label);
        }
        if (entry instanceof net.minecraft.client.gui.GuiKeyBindingList.KeyEntry) {
            return keyContent(screen, entry);
        }
        if (entry instanceof ResourcePackListEntry pack) {
            return packContent(pack, unreadable);
        }
        if (entry == null) {
            return statContent(screen, list, size, row, unreadable);
        }
        return BLANK;
    }

    /**
     * A Controls row: the binding's own description, the key it is bound to RIGHT NOW
     * through vanilla's own {@code GameSettings.getKeyDisplayString}, whether it is
     * armed for rebinding, and the conflicts vanilla's own
     * {@code KeyEntry.drawEntry} colours red.
     *
     * <p>The conflict list is computed with vanilla's exact predicate — same
     * non-zero code, different binding — because "this key is already taken" is the
     * thing an agent must know before it rebinds, and a list it has to guess at is
     * worse than none.
     */
    private static Content keyContent(GuiScreen screen, Object entry) {
        Object kb = objectFieldOr(entry, "keybinding");
        if (!(kb instanceof KeyBinding key)) {
            return BLANK;
        }
        String desc = stringFieldOr(entry, "keyDesc");
        String shown = GameSettings.getKeyDisplayString(key.getKeyCode());
        int code = key.getKeyCode();
        List<String> conflicts = new ArrayList<>();
        GameSettings settings = gameSettings();
        if (settings != null && settings.keyBindings != null && code != 0) {
            for (KeyBinding other : settings.keyBindings) {
                if (other != null && other != key && other.getKeyCode() == code) {
                    conflicts.add(other.getKeyDescription());
                }
            }
        }
        // Read once: extract() re-runs on every action, and this walks the screen's
        // declared fields reflectively.
        boolean armed = isArmed(screen, key);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("keyDescription", desc);
        attrs.put("keyCode", code);
        attrs.put("keyCodeDefault", key.getKeyCodeDefault());
        attrs.put("defaultKey", GameSettings.getKeyDisplayString(key.getKeyCodeDefault()));
        attrs.put("conflicted", !conflicts.isEmpty());
        attrs.put("conflicts", List.copyOf(conflicts));
        attrs.put("armed", armed);
        attrs.put("conflictRule", "vanilla KeyEntry.drawEntry: any other binding with the same non-zero code");
        return new Content(desc, shown, armed, true, true, attrs);
    }

    /**
     * A resource-pack row: the pack's own title and name (vanilla's two strings, read
     * through the same accessors {@code drawEntry} uses) and whether the screen
     * currently has it selected.
     */
    private static Content packContent(ResourcePackListEntry entry, List<String> unreadable) {
        String title = invokeString(entry, "func_148311_a");
        String name = invokeString(entry, "func_148312_b");
        if (title == null && name == null) {
            unreadable.add(entry.getClass().getSimpleName() + ".func_148311_a/func_148312_b(absent)");
            return BLANK;
        }
        boolean selected = false;
        try {
            Object screen = objectFieldOr(entry, "resourcePacksGUI");
            if (screen instanceof net.minecraft.client.gui.GuiScreenResourcePacks packs) {
                selected = packs.hasResourcePackEntry(entry);
            }
        } catch (Throwable ignored) {
            // selected stays false only if it cannot be read; that is stated below.
        }
        Integer compat = invokeInt(entry, "func_183019_a");
        // func_148310_d is the OTHER half of vanilla's press gate: a pack that returns
        // false is refused at EVERY position. The built-in Default pack is exactly
        // that (ResourcePackListEntryDefault overrides it to false), and vanilla also
        // consults it on each NEIGHBOUR to decide whether a pack can be reordered past
        // it. A row that cannot be pressed must say so, so the refusal can be "this
        // pack cannot be applied here" rather than "wrong position".
        Boolean reorderable = invokeBoolean(entry, "func_148310_d");
        boolean actionable = reorderable == null || reorderable;
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("packTitle", title == null ? "" : title);
        attrs.put("packName", name == null ? "" : name);
        attrs.put("selected", selected);
        attrs.put("compatibility", compat);
        attrs.put("compatibilityRule", "vanilla func_183019_a: <1 needs update, 1 ok, >1 too new");
        attrs.put("reorderable", reorderable);
        attrs.put("reorderableRule", "vanilla func_148310_d: false means this pack is "
                + "refused at every position AND blocks reordering past it "
                + "(ResourcePackListEntryDefault returns false)");
        if (!actionable) {
            attrs.put("rowKind", "fixed");
        }
        return new Content(title == null ? "" : title, selected ? "selected" : "available",
                false, true, actionable, attrs);
    }

    /**
     * A row of a plain {@link GuiSlot} whose rows are statistics — the Items, Blocks
     * and General grids. Vanilla's {@code GuiSlot} has no entry API, so the row
     * objects are found the way vanilla finds them: the list's own row list. For
     * Items and Blocks that is the {@code statsHolder} the grid keeps; for General it
     * is a static on {@link StatList}, matched by SIZE against the grid's own
     * {@code getSize()} rather than by name or position.
     *
     * <p>When a row object is a real {@link StatBase}, the label and the value are
     * real reads, not guesses: the label is what vanilla's own row tooltip shows
     * (the item's localised name, else the stat's own name) and the value is
     * {@code stat.format(writer.readStat(stat))} — the exact call
     * {@code GuiStats.Stats.func_148209_a} makes, through the screen's real
     * {@link StatFileWriter}.
     *
     * <p>The Mobs grid is deliberately NOT read. Its row object is an
     * {@code EntityEggInfo}, from which vanilla composes three separate lines out of
     * a mob id and two separate stats; there is no single string on the row to read,
     * so inventing one would be fabrication. Those rows stay addressable and
     * scrollable, report {@code contentReadable:false}, and say why.
     */
    private static Content statContent(GuiScreen screen, GuiSlot list, int size, int row,
                                       List<String> unreadable) {
        List<?> rows = statRows(list, size);
        if (rows == null || row >= rows.size()) {
            return BLANK;
        }
        Object stat = rows.get(row);
        if (!(stat instanceof StatBase base)) {
            unreadable.add(list.getClass().getSimpleName() + " row " + row
                    + "(" + stat.getClass().getSimpleName()
                    + "): content not readable -- vanilla composes this row's text from a mob id"
                    + " and two separate stats, so there is no row-carried string to read");
            return BLANK;
        }
        String label = statLabel(stat);
        if (label == null) {
            return BLANK;
        }
        String value = statValue(screen, base);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("statId", base.statId);
        attrs.put("item", itemRegistryName(stat));
        attrs.put("valueRule", "GuiStats.Stats.func_148209_a: stat.format(writer.readStat(stat))");
        return new Content(label, value, false, true, false, attrs);
    }

    /**
     * The list of row objects behind a plain {@link GuiSlot}, or null.
     *
     * <p>First choice: a declared {@link List} field on the list itself whose size
     * equals the list's own {@code getSize()} — which is exactly the condition under
     * which such a field IS the row list, and needs no name from any mapping. When
     * the list keeps no rows of its own (vanilla's General grid keeps them in a
     * static), the one public static {@code List} of {@link StatBase} on
     * {@link StatList} whose size equals {@code getSize()} is used, and only if
     * exactly one such field matches — an ambiguous match is treated as no match
     * rather than as a guess.
     */
    private static List<?> statRows(GuiSlot list, int size) {
        for (Class<?> c = list.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(list);
                    if (v instanceof List<?> l && l.size() == size) {
                        return l;
                    }
                } catch (Throwable ignored) {
                    // keep looking
                }
            }
        }
        List<?> only = null;
        for (Field f : StatList.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    || !List.class.isAssignableFrom(f.getType())) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object v = f.get(null);
                if (v instanceof List<?> l && l.size() == size && !l.isEmpty()
                        && l.get(0) instanceof StatBase) {
                    if (only != null) {
                        return null; // ambiguous: refuse rather than guess
                    }
                    only = l;
                }
            } catch (Throwable ignored) {
                // keep looking
            }
        }
        return only;
    }

    /** The row's drawn label: the item's localised name, or the stat's own name. */
    private static String statLabel(Object stat) {
        try {
            if (stat instanceof net.minecraft.stats.StatCrafting crafting) {
                Item item = crafting.func_150959_a();
                if (item != null) {
                    GuiStack view = GuiStack.of(new ItemStack(item));
                    if (!view.displayName().isEmpty()) {
                        return view.displayName();
                    }
                }
            }
            IChatComponent name = ((StatBase) stat).getStatName();
            return name == null ? null : name.getUnformattedText();
        } catch (Throwable t) {
            return null;
        }
    }

    /** The row's drawn value, read through the screen's real {@link StatFileWriter}. */
    private static String statValue(GuiScreen screen, StatBase stat) {
        try {
            StatFileWriter writer = statWriter(screen);
            return writer == null ? "" : stat.format(writer.readStat(stat));
        } catch (Throwable t) {
            return "";
        }
    }

    private static StatFileWriter statWriter(GuiScreen screen) {
        for (Class<?> c = screen == null ? null : screen.getClass();
             c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!StatFileWriter.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(screen);
                    if (v instanceof StatFileWriter w) {
                        return w;
                    }
                } catch (Throwable ignored) {
                    // keep looking
                }
            }
        }
        return null;
    }

    private static String itemRegistryName(Object stat) {
        try {
            if (stat instanceof net.minecraft.stats.StatCrafting crafting) {
                Item item = crafting.func_150959_a();
                return item == null ? "" : GuiStack.of(new ItemStack(item)).registryName();
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return "";
    }

    /**
     * Whether a binding is the one the screen is currently waiting for a key press
     * for. Discovered structurally — any {@link KeyBinding} field on the screen that
     * currently holds this exact binding — so it works for {@code GuiControls} and
     * for any screen that arms one, without naming either.
     */
    private static boolean isArmed(GuiScreen screen, KeyBinding key) {
        for (Class<?> c = screen == null ? null : screen.getClass();
             c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!KeyBinding.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    if (f.get(screen) == key) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // keep looking
                }
            }
        }
        return false;
    }

    private static GameSettings gameSettings() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return mc == null ? null : mc.gameSettings;
        } catch (Throwable t) {
            return null;
        }
    }

    // ===== REFLECTION PRIMITIVES (fail-loud-but-degrade) =====

    /** The list's row count, via its own {@code getSize()}. -1 when unreadable. */
    public static int size(GuiSlot list, ListRef ref, List<String> unreadable) {
        int n = intMethod(list, "getSize", -1);
        if (n < 0) {
            unreadable.add(list.getClass().getSimpleName() + ".getSize(absent)");
        }
        return Math.max(0, n);
    }

    private static int intField(Object target, String name, int fallback) {
        return intField(target, target == null ? Object.class : target.getClass(), name, fallback);
    }

    private static int intField(Object target, Class<?> hint, String name, int fallback) {
        for (Class<?> c = hint; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(target);
                return v instanceof Number n ? n.intValue() : fallback;
            } catch (NoSuchFieldException e) {
                // walk up
            } catch (Throwable t) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean boolField(Object target, Class<?> hint, String name) {
        for (Class<?> c = hint; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(target);
                return v instanceof Boolean b && b;
            } catch (NoSuchFieldException e) {
                // walk up
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    private static int intMethod(Object target, String name, int fallback) {
        for (Class<?> c = target == null ? Object.class : target.getClass();
             c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                Object v = m.invoke(target);
                return v instanceof Number n ? n.intValue() : fallback;
            } catch (NoSuchMethodException e) {
                // walk up
            } catch (Throwable t) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Object objectFieldOr(Object target, String name) {
        for (Class<?> c = target == null ? Object.class : target.getClass();
             c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                // walk up
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static String stringFieldOr(Object target, String name) {
        Object v = objectFieldOr(target, name);
        return v instanceof String s ? s : null;
    }

    private static String invokeString(Object target, String method) {
        Object v = invoke(target, method);
        return v instanceof String s ? s : null;
    }

    private static Integer invokeInt(Object target, String method) {
        Object v = invoke(target, method);
        return v instanceof Number n ? n.intValue() : null;
    }

    private static Boolean invokeBoolean(Object target, String method) {
        Object v = invoke(target, method);
        return v instanceof Boolean b ? b : null;
    }

    private static Object invoke(Object target, String method) {
        for (Class<?> c = target == null ? Object.class : target.getClass();
             c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(method);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (NoSuchMethodException e) {
                // walk up
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
