package net.marcloud.mcp.dwm.qml;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.github.timer_err.qml4j.render.items.core.Flickable;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.core.MouseArea;

/**
 * Walks the live QML tree and publishes one {@link QmlProxyButton} per interactive element.
 *
 * <p>This is the whole of "make our own panel drivable". Without it an agent can operate every
 * vanilla menu in the game and <em>none</em> of ours, because {@code gui_snapshot} reflects
 * {@code GuiScreen.buttonList} and our screen never put anything there. With it, our panel
 * arrives in the same element table as a chest GUI and the same tool calls drive it.
 *
 * <p><b>Coordinates.</b> Three spaces exist in 1.8.9 and mixing them is the classic bug — the
 * reference clients that went wrong are in the tree to this day
 * ({@code ClickGui.java:254-255,341-345,354-355} divides by its own scale in four places and
 * leaks it to {@code super}). The chain here runs in ONE direction only:
 *
 * <pre>
 *   QML logical  --(x uiScale)-->  framebuffer px  --(x W/H ratio)-->  scaled-GUI
 * </pre>
 *
 * <p>and only the forward direction is computed, because only it is needed: vanilla matches a
 * click against the published rectangle, and the proxy then names its own element rather than
 * converting a point back. That removes the reverse conversion and the pixel error it would
 * accumulate at non-integer DPI scales.
 *
 * <p><b>Dispatch is a QML signal, not a coordinate.</b> Activation emits the very same
 * {@code clicked} signal a human click emits, so the scene cannot tell the two apart — which is
 * the point of wanting human-equivalent input in the first place. There is no second code path
 * into the scene, so there is no second thing that can drift.
 */
final class QmlElementBridge {

    /**
     * How deep the walk may go.
     *
     * <p>The tree belongs to qml4j, not to us, and this runs inside {@code initGui} -- a
     * diagnostic that overflows the stack takes the screen down with it, and an overflow raised
     * while unwinding into a catch block is unrecoverable. A real panel nests perhaps a dozen
     * deep, so this is far above anything legitimate and exists only so that a malformed or
     * cyclic scene degrades to a partial element table instead of a dead game.
     */
    private static final int MAX_DEPTH = 64;

    /**
     * One element's published rectangle, in scaled-GUI units.
     *
     * <p><b>A value, not a handle — and that is load-bearing.</b> {@link #equals} and
     * {@link #hashCode} are implemented over every field because {@code republishIfChanged}
     * compares the freshly collected table against the previous one with {@code List.equals} to
     * decide whether anything moved. Without them that comparison degrades to reference identity,
     * two collections of identical rows are never equal, and the "publish only on a difference"
     * rule silently becomes "rebuild buttonList every frame": the cost the one-shot rule existed
     * to avoid, with nothing observable to show for it.
     *
     * <p>{@link #area} compares by identity on purpose. It is the live QML node, so a rebuilt
     * subtree means new nodes — and that IS a change the caller has to see.
     */
    static final class Hit {
        /** The QML {@code objectName} nearest this element, or {@link #path} when it has none. */
        final String id;
        /** Child-index path from the scene root: an identity, not a name. See {@link #id}. */
        final String path;
        final int x;
        final int y;
        final int w;
        final int h;
        final boolean enabled;
        final MouseArea area;

        Hit(String id, String path, int x, int y, int w, int h, boolean enabled, MouseArea area) {
            this.id = id;
            this.path = path;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.enabled = enabled;
            this.area = area;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Hit)) {
                return false;
            }
            Hit o = (Hit) other;
            return x == o.x && y == o.y && w == o.w && h == o.h
                    && enabled == o.enabled && area == o.area
                    && id.equals(o.id) && path.equals(o.path);
        }

        @Override
        public int hashCode() {
            int hash = id.hashCode();
            hash = 31 * hash + path.hashCode();
            hash = 31 * hash + x;
            hash = 31 * hash + y;
            hash = 31 * hash + w;
            hash = 31 * hash + h;
            hash = 31 * hash + (enabled ? 1 : 0);
            hash = 31 * hash + System.identityHashCode(area);
            return hash;
        }
    }

    /**
     * Ambiguous names already reported, so a QML defect costs one line rather than one per frame.
     *
     * <p>Bounded by the number of distinct {@code objectName}s the scene contains, and touched
     * only from {@link #publish}, which runs on the render thread — so a plain set is enough.
     */
    private static final java.util.Set<String> REPORTED_AMBIGUOUS = new java.util.HashSet<>();

    private QmlElementBridge() {
    }

    /**
     * Every interactive element currently in the tree, in scaled-GUI units.
     *
     * @param root    the scene root
     * @param screenW {@code GuiScreen.width} — the scaled-GUI width
     * @param screenH {@code GuiScreen.height}
     * @param frameW  {@code mc.displayWidth} — framebuffer pixels
     * @param frameH  {@code mc.displayHeight}
     * @param uiScale framebuffer pixels per QML logical unit
     */
    static List<Hit> enumerate(Item root, int screenW, int screenH,
                              int frameW, int frameH, float uiScale) {
        List<Hit> out = new ArrayList<>();
        if (root == null || uiScale <= 0f || frameW <= 0 || frameH <= 0) {
            return out;
        }
        double sx = screenW / (double) frameW;
        double sy = screenH / (double) frameH;
        collect(root, 0f, 0f, "", 0, uiScale, sx, sy, out);
        return out;
    }

    private static void collect(Item node, float originX, float originY, String path,
                                int depth, float uiScale, double sx, double sy, List<Hit> out) {
        if (node == null || depth > MAX_DEPTH) {
            return;
        }
        if (!node.isVisible()) {
            // The root is checked too, not just its children: a root that reports invisible has no
            // children worth walking, and skipping it here saves the whole subtree.
            return;
        }
        float ax = originX + node.x.peekFloat();
        float ay = originY + node.y.peekFloat();
        float aw = node.width.peekFloat();
        float ah = node.height.peekFloat();

        if (node instanceof MouseArea) {
            MouseArea area = (MouseArea) node;
            if (aw > 0f && ah > 0f) {
                // Rounded OUTWARD at both edges, so a half-open rectangle never loses the pixel
                // a click landed on. Rounding inward is how a 3-pixel control becomes 2 and
                // stops being clickable near its edge.
                int x0 = (int) Math.floor(ax * uiScale * sx);
                int y0 = (int) Math.floor(ay * uiScale * sy);
                int x1 = (int) Math.ceil((ax + aw) * uiScale * sx);
                int y1 = (int) Math.ceil((ay + ah) * uiScale * sy);
                String name = named(node, path);
                out.add(new Hit(name, path, x0, y0,
                        Math.max(1, x1 - x0), Math.max(1, y1 - y0),
                        !Boolean.FALSE.equals(area.enabled.peek()), area));
            }
        }
        // A Flickable's children live at `origin + contentOffset`, not at `origin`. Without
        // this, every control inside a scroll region publishes the scroll region's own
        // rectangle -- which is how four navigation buttons ended up sharing one rect and
        // whichever was drawn last won every click.
        //
        // The arithmetic is copied from QmlUiSurface.findFlickable, which is the hit-test the
        // human click path already uses. Matching it is not a style choice: vanilla matches an
        // incoming click against these published rectangles, so if the two disagree, the agent's
        // click and a person's click land on different controls.
        float childOriginX = ax;
        float childOriginY = ay;
        if (node instanceof Flickable) {
            childOriginX += ((Flickable) node).contentX.peekFloat();
            childOriginY += ((Flickable) node).contentY.peekFloat();
        }

        List<Item> kids = node.children;
        for (int i = kids.size() - 1; i >= 0; i--) {
            collect(kids.get(i), childOriginX, childOriginY, path + "/" + i,
                    depth + 1, uiScale, sx, sy, out);
        }
    }

    /**
     * How far up the parent chain to look for a name.
     *
     * <p>Bounded, not unbounded: a deep scene would otherwise walk to the root on every element
     * every frame, on the render thread of a game. Measured on the shipped scenes the deepest name
     * resolves in three hops — the settings expander's chevron, area to its slot, slot to the
     * header card, card to the expander — so six is twice what is used and a deeper scene has
     * room before a name starts being missed.
     */
    private static final int NAME_LOOKUP_DEPTH = 6;

    /**
     * The element's name: the nearest {@code objectName} on it or on an ancestor, or the
     * element's own path when neither carries one.
     *
     * <p>The ancestor walk is not a nicety. Live: the panel's navigation items carry
     * {@code objectName: "navHome" / "navKernel" / "navChips" / "navSettings"}, but those names sit
     * on the {@code NavItem} -- an {@code Item} wrapping a {@code MouseArea} -- while what gets
     * PUBLISHED is the {@code MouseArea}. So the agent saw {@code /0/5/0/0/3/4}: a path of child
     * indices that identifies nothing, on six controls it could click but not name. A name an
     * agent cannot read is half a tool.
     *
     * <p>Walking up also does the right thing for a composite: a button that is an icon over a
     * label over a hit area takes the name from whichever of them carries one, which is the
     * convention a human reader would apply.
     *
     * <p><b>What comes back is not always a name, and that is the QML author's to see.</b> A node
     * with no name anywhere above it yields its path. A path is a fallback, not an id: a node's
     * index changes whenever the tree above it is rebuilt, so a path-based id re-points at a
     * different control after a {@code Loader} swap or a layout change, and an agent holding a
     * plan full of {@code "3/0/1"} strings clicks whatever took that slot. {@link #publish} is
     * where that becomes visible rather than silent.
     *
     * <p>{@code objectName} is a {@code Property}, not a method. Reading it as
     * {@code node.objectName()} throws {@link NoSuchMethodError}, which is exactly what led an
     * earlier note to record this API as nonexistent and to justify path-only ids.
     */
    private static String named(Item node, String path) {
        Item current = node;
        for (int up = 0; current != null && up < NAME_LOOKUP_DEPTH; up++) {
            try {
                // Plain instanceof cast, not a pattern match: dwm compiles at -source 8, which is
                // the module's existing level and not a choice made for this file.
                Object v = current.objectName.peek();
                if (v instanceof String && !((String) v).isEmpty()) {
                    return (String) v;
                }
            } catch (Throwable ignored) {
                // No objectName, or a binding not yet evaluated at this point in the frame.
            }
            try {
                Object p = current.parent.peek();
                current = p instanceof Item ? (Item) p : null;
            } catch (Throwable ignored) {
                return path;
            }
        }
        return path;
    }

    /**
     * Install the proxies into a screen's button list.
     *
     * <p>Replaces the list wholesale rather than appending, because vanilla's
     * {@code setWorldAndResolution} clears it and calls {@code initGui} again on every resize —
     * appending would accumulate a duplicate set per resize, and the stale rectangles would let
     * an element from the page you left still answer a click.
     *
     * <p>Disabled elements are published too, with vanilla's own rules applied. {@code
     * gui_snapshot} reports {@code state.enabled}, so an agent can SEE that a control exists and
     * is unavailable — strictly more information than an omission, and an omission is
     * indistinguishable from "not on this page".
     *
     * <p><b>A name is an id only if it is unique, and uniqueness is the QML author's job.</b> The
     * ancestor walk in {@link #named} cannot promise it: two hit areas under one named node both
     * resolve to that node's name, which is live in this scene — the window's two caption buttons
     * are MouseAreas inside a named Item, so before this rule the table had six controls and five
     * names. So a name more than one element claims is discarded wholesale and every holder is
     * published under its structural path, and the collision is reported on stderr rather than
     * absorbed. Absorbing it is what an earlier version did, by appending {@code @x,y}: a place,
     * not a name, and a place that moves. Vanilla re-lays the screen out on every GUI-scale change,
     * so an id carrying a pixel stops matching the control it was recorded for — while the
     * rectangle the coordinates came from is already in the published row.
     */
    static void publish(java.util.List<net.minecraft.client.gui.GuiButton> target, List<Hit> hits) {
        target.clear();
        // Count first, decide second. A name claimed by more than one published element is not a
        // name, and EVERY holder of it is published under its structural path instead -- not just
        // the later ones, because letting the first keep the clean name would make one id mean a
        // different control depending on draw order, which is worse than an ugly id: it is silent.
        java.util.Map<String, Integer> claims = new java.util.HashMap<>();
        for (Hit h : hits) {
            Integer seen = claims.get(h.id);
            claims.put(h.id, Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
        }
        for (Hit h : hits) {
            String id = h.id;
            if (claims.get(id).intValue() > 1) {
                reportAmbiguousName(h);
                id = h.path;
            }
            // The SAME string as the element id and as the visible label. A proxy whose two names
            // disagree hands the agent two identities for one control, and the unique one is not
            // the one on the button.
            QmlProxyButton b = new QmlProxyButton(id, id, h.x, h.y, h.w, h.h, h.area);
            b.enabled = h.enabled;
            b.visible = true;
            target.add(b);
        }
    }

    /**
     * A name the QML author chose has to be unique in the QML, and this is the only thing that
     * says so: once per name per session, naming the path that replaced it so the offending
     * control can be found in the scene.
     */
    private static void reportAmbiguousName(Hit h) {
        if (REPORTED_AMBIGUOUS.add(h.id)) {
            System.err.println("[dwm] QML objectName \"" + h.id + "\" is claimed by more than one "
                    + "published control; published as its path (" + h.path + ") instead. Give each "
                    + "control its own objectName.");
        }
    }
}
