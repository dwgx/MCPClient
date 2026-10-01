package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.core.MouseArea;

import org.junit.Test;

/**
 * A published element must be NAMED, and the name must be one an agent can read.
 *
 * <p>Found on a live client, twice, and the two are the same defect seen from opposite ends.
 *
 * <p><b>Unnameable.</b> The navigation items carry {@code objectName: "navHome" / "navKernel" /
 * "navChips" / "navSettings"}, but those names sit on the {@code NavItem} — an {@code Item}
 * wrapping a {@code MouseArea} — while what gets PUBLISHED is the {@code MouseArea}. So
 * {@code gui_snapshot} listed clickable controls named {@code /0/5/0/0/3/4}: a path of child
 * indices that identifies nothing. A control an agent can act on but not name is half a tool, and
 * the missing half is the half that lets it choose.
 *
 * <p><b>Ambiguous.</b> The ancestor walk that fixed the above cannot promise uniqueness, because
 * two hit areas under one named node both resolve to that node's name. Live, the window's two
 * caption buttons are anonymous {@code MouseArea}s inside a named {@code Item}: the table had six
 * controls and five names, and the agent could not tell close from maximize.
 *
 * <p><b>Why this is a real test and not a source scan.</b> The previous version of this file
 * asserted that {@code NavigationView.qml} contains four specific strings. That is a proxy: it
 * cannot fail when the name is on the wrong node, when the walk stops at the node itself, or when
 * a second control claims the same name — and all three were the live bugs. Here the QML is
 * <em>loaded</em> and the published table enumerated and inspected, so the property being pinned
 * is the one an agent sees.
 *
 * <p><b>What is and is not reachable headless.</b> Loading a QML document and walking its item
 * tree needs no GLFW window: qml4j compiles, instantiates and lays out without a surface, which
 * is what lets a page be enumerated here at all. What genuinely needs a live window is the
 * COMPOSED shell — {@code Shell.qml} swapping pages through its {@code Loader} — because the swap
 * is driven from a render frame. That half is pinned by {@code ShippedShellNamesEveryControlIT},
 * which self-skips without a display. Between them every control the panel publishes is checked
 * on a real scene; nothing here is asserted against source text.
 */
public final class QmlElementNamesAreReadableTest {

    /** Every document that contributes controls to the panel. */
    private static final String[] SHIPPED_SCENES = {
        "dwm/FluentWindow.qml",
        "dwm/NavigationView.qml",
        "dwm/pages/PageSettings.qml",
        "dwm/pages/PageChips.qml",
        "dwm/pages/PageHome.qml",
        "dwm/pages/PageKernel.qml",
    };

    /** Two chips, so the Repeater's delegate produces more than one control to name. */
    private static final String CHIP_ALPHA = "alpha";
    private static final String CHIP_BETA = "beta";

    /**
     * The tag each hand-built area was made with, so a test can ask about one specific area.
     *
     * <p>A Java-side handle, keyed by identity: {@code QObject} exposes no such handle, and
     * {@code objectName} cannot be used for this because it is the very thing under test.
     */
    private static final Map<MouseArea, String> AREA_TAGS = new IdentityHashMap<>();

    // ===== the shipped scenes =========================================================

    /**
     * Every control each shipped document publishes is named, readably, and uniquely.
     *
     * <p>Uniqueness is asserted on the PUBLISHED ids rather than on the QML's {@code objectName}
     * declarations, because the two are not the same thing: the ancestor walk means one
     * {@code objectName} can resolve onto several published controls, which is exactly how the
     * caption buttons collided.
     */
    @Test
    public void everyControlAShippedScenePublishesIsNamedReadablyAndUniquely() {
        for (String scene : SHIPPED_SCENES) {
            for (QmlElementBridge.Hit hit : enumerateScene(scene)) {
                String where = scene + " publishes " + describe(hit);
                assertFalse(where + " is named by its index path, which identifies nothing and "
                        + "re-points at a different control after any Loader swap or layout "
                        + "change", isPath(hit.id));
                assertFalse(where + " has no name at all", hit.id.isEmpty());
            }
            assertUniqueIds(scene, enumerateScene(scene));
        }
    }

    /**
     * The naming is not vacuous: the tables above are populated and carry the expected controls.
     *
     * <p>Without this, a scene that failed to load — or a walk that stopped finding anything —
     * would satisfy "every published control is named" by publishing nothing. So each scene is
     * required to publish specific controls, by name.
     */
    @Test
    public void theExpectedControlsAreActuallyPublishedUnderThoseNames() {
        assertContains("dwm/FluentWindow.qml", "windowMaximize", "windowClose");
        assertContains("dwm/NavigationView.qml",
            "navHome", "navKernel", "navChips", "navSettings");
        assertContains("dwm/pages/PageChips.qml", "chip-" + CHIP_ALPHA, "chip-" + CHIP_BETA);
        // The fullbright card AND the switch inside it: the card's own hit area is published
        // disabled, and an agent that sees a whole-card region in the table has to be able to say
        // which card it belongs to.
        assertContains("dwm/pages/PageSettings.qml",
            "settingsFullbrightCard", "settingsFullbright",
            "settingsGammaCard", "settingsGamma",
            "settingsNameCard", "settingsName",
            "settingsAdvanced", "settingsAdvancedChevron",
            "fxMaster", "fxMasterToggle",
            "fxDetails", "fxDetailsChevron");
    }

    /**
     * A card's name and the control inside it are two different names.
     *
     * <p>Distinct because they are distinct things to an agent: one is a region of the card, the
     * other is the switch. Collapsing them — by naming the card after its control, or by leaving
     * the card anonymous so the control's name bubbles up — makes the table say there is one
     * control where there are two, and which of the two a click reaches becomes a matter of
     * z-order.
     */
    @Test
    public void aCardsNameIsNotItsControlsName() {
        Map<String, QmlElementBridge.Hit> byId = byId(enumerateScene("dwm/pages/PageSettings.qml"));
        QmlElementBridge.Hit card = byId.get("settingsFullbrightCard");
        QmlElementBridge.Hit toggle = byId.get("settingsFullbright");
        assertNotNull("the fullbright card must be published", card);
        assertNotNull("the fullbright switch must be published", toggle);
        assertTrue("the card's published region and the switch's must be different rectangles: "
                        + "identical ones mean the card's own area is live and shadowing the "
                        + "control it holds",
            card.x != toggle.x || card.y != toggle.y);
    }

    /**
     * An expander's three hit areas are three names.
     *
     * <p>The live ambiguity in its purest form: the expander's own header target, the header
     * card's, and the chevron's all sit inside the one expander, so all three resolve to its
     * name. Two of them are the same rectangle and one is a 32px corner of it, so an agent
     * reading a table with three rows called {@code settingsAdvanced} learns nothing it can act
     * on.
     */
    @Test
    public void anExpandersThreeHitAreasAreThreeNames() {
        Map<String, QmlElementBridge.Hit> byId = byId(enumerateScene("dwm/pages/PageSettings.qml"));
        assertNotNull("the expander's own header target", byId.get("settingsAdvanced"));
        assertNotNull("the header card's inert area", byId.get("settingsAdvancedHeader"));
        assertNotNull("the chevron's area", byId.get("settingsAdvancedChevron"));
    }

    // ===== the mechanism, independent of any QML =====================================

    /**
     * The name is taken from an ANCESTOR when the node itself has none.
     *
     * <p>Pinned on a hand-built tree rather than on a scene, so it cannot be satisfied by the QML
     * happening to name the {@code MouseArea} directly. The live bug was exactly that: the name
     * was one level up, on the wrapper, and a walk that stopped at the node would have passed a
     * test that only checked "objectName is read".
     */
    @Test
    public void aNameOnAnAncestorIsUsedWhenTheNodeItselfHasNone() {
        Item root = item(null);
        Item wrapper = item("navHome");
        adopt(root, wrapper);
        adopt(wrapper, area("hit", 0, 0, 10, 10));

        assertEquals("the published id must be the ancestor's objectName, not the node's index "
                + "path", "navHome", only(bridge(root)).id);
    }

    /**
     * A name on the node itself wins over one further up.
     *
     * <p>The nearest name is the specific one. A control that names itself inside a named
     * container is saying which of the container's several controls it is, and reading past it to
     * the container would throw that away.
     */
    @Test
    public void theNearestNameWins() {
        Item root = item(null);
        Item container = item("card");
        MouseArea own = area("hit", 0, 0, 10, 10);
        own.objectName.set("settingsFullbright");
        adopt(root, container);
        adopt(container, own);

        assertEquals("the node's own objectName is the nearest and must win",
            "settingsFullbright", only(bridge(root)).id);
    }

    /**
     * An empty {@code objectName} is not a name, and the walk continues past it.
     *
     * <p>Every QML {@code Item} has the property, unset. Treating "present but empty" as a hit
     * would end the walk at the first anonymous wrapper — which is every wrapper — and put the
     * published table back to index paths.
     */
    @Test
    public void anEmptyNameIsSkippedRatherThanAccepted() {
        Item root = item(null);
        Item outer = item("navSettings");
        Item inner = item("");
        adopt(root, outer);
        adopt(outer, inner);
        adopt(inner, area("hit", 0, 0, 10, 10));

        assertEquals("an empty objectName must not end the walk", "navSettings",
            only(bridge(root)).id);
    }

    /**
     * Two controls claiming one name are BOTH published under their paths, and the paths differ.
     *
     * <p>The live shape: the window's two caption buttons, anonymous {@code MouseArea}s inside a
     * named {@code Item}, so both resolved to {@code window}. Two rows both called {@code window}
     * is the ambiguity; the rule under test is that neither keeps it. Letting the first keep the
     * clean name would make one id mean a different control depending on draw order, which is
     * worse than an ugly id because nothing reports it.
     *
     * <p>Asserted on the PUBLISHED ids rather than on {@link QmlElementBridge.Hit#id}, because
     * uniqueness is not a property of one control: it is a property of a table, so it can only be
     * decided where the whole table is in hand. That is {@code publish}, which is also the only
     * place that can act on the answer.
     */
    @Test
    public void aNameClaimedTwiceIsReplacedOnEveryHolderByDistinctPaths() {
        Item root = item(null);
        Item named = item("window");
        adopt(root, named);
        adopt(named, area("maxHit", 0, 0, 40, 20));
        adopt(named, area("closeHit", 40, 0, 40, 20));

        List<QmlElementBridge.Hit> hits = bridge(root);
        assertEquals("both caption buttons are collected", 2, hits.size());
        assertEquals("and both do resolve to the one name, which is what makes it ambiguous",
            "window", hits.get(0).id);
        assertEquals("window", hits.get(1).id);

        List<net.minecraft.client.gui.GuiButton> published = publish(hits);
        assertEquals("both are published", 2, published.size());

        Set<String> replacements = new TreeSet<>();
        for (net.minecraft.client.gui.GuiButton button : published) {
            String id = ((QmlProxyButton) button).elementId();
            assertFalse("no holder of an ambiguous name may keep it, or the agent still cannot "
                    + "tell the two apart: " + id, "window".equals(id));
            assertTrue("the two holders must be told apart, and a path is what does that: " + id,
                isPath(id));
            replacements.add(id);
        }
        assertEquals("the two replacements must be distinct", 2, replacements.size());
    }

    /**
     * A name claimed once is published as itself — the rule must not degrade readable names into
     * paths.
     *
     * <p>The mirror of the case above, and the one that would be easy to break while fixing that
     * one: replace every id with its path and uniqueness is trivially satisfied.
     */
    @Test
    public void aNameClaimedOnceIsPublishedAsItself() {
        QmlProxyButton proxy = (QmlProxyButton) publish(bridge(singleControl("navHome"))).get(0);
        assertEquals("a uniquely-claimed name is the id, verbatim and not a path",
            "navHome", proxy.elementId());
    }

    /**
     * The walk is bounded, and the bound reaches the depths the scenes actually use.
     *
     * <p>Unbounded, this is a root-ward traversal per element per frame, on the render thread of
     * a game. The bound also constrains the QML: a name buried deeper than it is not found, and
     * the control silently falls back to a path. So both ends are asserted — that a name just
     * inside the bound is found, and that one just outside it is not.
     *
     * <p>Deepest name in the shipped scenes, for what the bound has to cover: the settings
     * expander's chevron, three hops up — area to its slot, slot to the header card, card to the
     * expander.
     */
    @Test
    public void theAncestorWalkIsBoundedAndFindsANameJustInsideTheBound() {
        int bound = nameLookupDepth();
        assertTrue("the lookup must be bounded, or every element walks to the root every frame",
            bound > 0);
        assertTrue("and the bound must exceed the deepest name in the shipped scenes (the expander"
                + " chevron's, three hops up) or those controls silently lose their names",
            bound > 3);

        // The walk performs NAME_LOOKUP_DEPTH iterations and the first examines the node itself,
        // so a name `h` hops up is found exactly when h < bound. Built to put the name at
        // h = bound - 1, the deepest reachable.
        Item root = item(null);
        Item holder = item("deepestHolder");
        adopt(root, holder);
        Item deepest = holder;
        for (int hops = 1; hops < bound - 1; hops++) {
            Item next = item(null);
            adopt(deepest, next);
            deepest = next;
        }
        adopt(deepest, area("inside", 0, 0, 10, 10));
        assertEquals("a name one hop inside the bound must be found, or a deeply nested control "
                + "silently loses its name", "deepestHolder", idOf(root, "inside"));

        // One level deeper, and it must be reported as a path rather than found.
        Item deeper = item(null);
        adopt(deepest, deeper);
        adopt(deeper, area("outside", 0, 0, 10, 10));
        assertTrue("a name one level past the bound must NOT be found -- that is the point of the"
                + " bound, and the fallback has to be a path rather than an unbounded walk",
            isPath(idOf(root, "outside")));
    }

    // ===== the difference check republishIfChanged depends on ==========================

    /**
     * An unchanged tree compares equal to itself, re-collected.
     *
     * <p>This is what "republish only on a difference" rests on. {@code List.equals} asks each
     * element whether it equals the element in the same position, so without {@code Hit.equals}
     * two collections of identical rows are never equal — the difference check degenerates into
     * "rebuild buttonList sixty times a second", and because the result is still correct, nothing
     * observes the cost.
     */
    @Test
    public void twoCollectionsOfAnUnchangedTreeAreEqual() {
        Item root = singleControl("navHome");

        List<QmlElementBridge.Hit> first = bridge(root);
        List<QmlElementBridge.Hit> second = bridge(root);
        assertTrue("re-collecting an unchanged tree must compare equal, or the table is rebuilt "
                + "every frame: " + describeAll(first) + " vs " + describeAll(second),
            first.equals(second));
    }

    /**
     * A control that moved is a difference, and a control that changed state is a difference.
     *
     * <p>Both halves, because the alternative failure is worse than republishing: an equality that
     * ignores the rectangle leaves a stale row in {@code buttonList} and the click lands on
     * whatever moved into the old one.
     */
    @Test
    public void aMovedOrRestatedControlIsNotEqual() {
        Item root = item(null);
        Item named = item("navHome");
        MouseArea hit = area("hit", 3, 4, 10, 10);
        adopt(root, named);
        adopt(named, hit);

        List<QmlElementBridge.Hit> before = bridge(root);
        hit.x.set(30.0);
        assertFalse("a control that moved is a difference", before.equals(bridge(root)));

        List<QmlElementBridge.Hit> afterMove = bridge(root);
        hit.enabled.set(Boolean.FALSE);
        assertFalse("a control that changed state is a difference, or gui_snapshot keeps reporting "
                + "the old state", afterMove.equals(bridge(root)));
    }

    /**
     * A rebuilt scene is a difference, even when every row looks identical.
     *
     * <p>This is what a resize is, and it is why {@code Hit} compares the live QML node by
     * identity rather than only its fields. Vanilla's {@code setWorldAndResolution} clears
     * {@code buttonList} and re-loads the scene, so a freshly opened panel can present a table that
     * matches the previous one name for name and pixel for pixel. An equality built from the
     * published fields alone would call that "unchanged" and leave the new scene with no elements
     * at all — a panel that has been resized into invisibility, with no error to explain it.
     *
     * <p>It is also what replaced the one-shot latch: there is no flag to reset any more, so this
     * is the whole of the resize guarantee.
     */
    @Test
    public void aRebuiltSceneIsNotEqualToTheOneBefore() {
        List<QmlElementBridge.Hit> before = bridge(singleControl("navHome"));
        List<QmlElementBridge.Hit> afterReload = bridge(singleControl("navHome"));

        assertEquals("the two tables must be indistinguishable row for row, or this proves "
                + "nothing about identity", describeAll(before), describeAll(afterReload));
        assertFalse("a re-loaded scene must count as a change, or a resize leaves the fresh scene "
                + "unpublished and the panel has no elements until something else moves",
            before.equals(afterReload));
    }

    /**
     * The published id and the visible label are the same string.
     *
     * <p>They were not, and the two disagreed in the one case that mattered: the disambiguation
     * rewrote the label while the element id kept the ambiguous name, so a caller dispatching on
     * the id got a value the table itself had already rejected.
     */
    @Test
    public void theProxyCarriesOneNameAsBothItsIdAndItsLabel() {
        // Built ambiguous on purpose. With a uniquely-claimed name the id and the label are the
        // same string whether or not the two are wired together, so a test using a clean name
        // cannot fail here — and the split only ever shows up where the label was rewritten.
        Item root = item(null);
        Item named = item("window");
        adopt(root, named);
        adopt(named, area("maxHit", 0, 0, 40, 20));
        adopt(named, area("closeHit", 40, 0, 40, 20));

        List<net.minecraft.client.gui.GuiButton> published = publish(bridge(root));
        assertEquals(2, published.size());
        for (net.minecraft.client.gui.GuiButton button : published) {
            QmlProxyButton proxy = (QmlProxyButton) button;
            assertEquals("the element id and the label must be the same string, or the agent is "
                    + "given two names for one control and the id is the ambiguous one the table "
                    + "already rejected", proxy.elementId(), proxy.displayString);
        }
    }

    // ===== harness =====================================================================

    /** A {@code Dwm} context with a fixed roster, so the chip page's Repeater has rows. */
    public static final class StubDwm {
        public List<Map<String, String>> chips() {
            List<Map<String, String>> out = new ArrayList<>();
            out.add(chip(CHIP_ALPHA, "Alpha", "true"));
            out.add(chip(CHIP_BETA, "Beta", "false"));
            return out;
        }

        public boolean hasChips() {
            return true;
        }

        public boolean hasKernel() {
            return false;
        }

        public List<Map<String, String>> kernelRows() {
            return new ArrayList<>();
        }

        public String attachment() {
            return "Test roster.";
        }

        public boolean toggleChip(String id) {
            return true;
        }

        private static Map<String, String> chip(String id, String name, String enabled) {
            Map<String, String> chip = new LinkedHashMap<>();
            chip.put("id", id);
            chip.put("name", name);
            chip.put("category", "test");
            chip.put("enabled", enabled);
            return chip;
        }
    }

    /** A loaded scene, with the same contexts the shipped surface registers. */
    static QmlView load(String scene) {
        String dir = scene.contains("/pages/") ? "dwm/pages" : "dwm";
        String source = ClasspathResources.readText(scene);
        assertNotNull(scene + " must be on the classpath", source);
        QmlView view = QmlView.withStockTypes(new QmlEngine())
                .resources(new ClasspathResources(dir))
                .context(DwmContext.NAME, new StubDwm())
                .context(WindowCommands.NAME, new WindowCommands(null));
        view.setClipboard(new GlfwClipboard());
        Item root = view.load(source, dir);
        assertNotNull(scene + " must load", root);
        view.tickAnimations(0L);
        return view;
    }

    private static List<QmlElementBridge.Hit> enumerateScene(String scene) {
        try {
            return bridge(load(scene).root());
        } catch (RuntimeException e) {
            throw new AssertionError(scene + " must load and lay out with no display", e);
        }
    }

    private static void assertContains(String scene, String... expected) {
        Set<String> ids = new LinkedHashSet<>();
        for (QmlElementBridge.Hit hit : enumerateScene(scene)) {
            ids.add(hit.id);
        }
        for (String name : expected) {
            assertTrue(scene + " must publish a control named " + name + "; it published "
                + new TreeSet<>(ids), ids.contains(name));
        }
    }

    private static void assertUniqueIds(String scene, List<QmlElementBridge.Hit> hits) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> published = new TreeSet<>();
        for (QmlElementBridge.Hit hit : hits) {
            published.add(hit.id);
            assertTrue(scene + " publishes two controls both named " + hit.id + " (" + describe(hit)
                    + " and another); an agent cannot choose between two rows with one name",
                seen.add(hit.id));
        }
    }

    private static Map<String, QmlElementBridge.Hit> byId(List<QmlElementBridge.Hit> hits) {
        Map<String, QmlElementBridge.Hit> byId = new LinkedHashMap<>();
        for (QmlElementBridge.Hit hit : hits) {
            byId.put(hit.id, hit);
        }
        return byId;
    }

    /**
     * The published table for a hand-built tree.
     *
     * <p>Scaled-GUI units are irrelevant here, so the framebuffer and the screen are the same
     * extent and the scale is 1: the numbers stay the node's own.
     */
    private static List<QmlElementBridge.Hit> bridge(Item root) {
        return QmlElementBridge.enumerate(root, 200, 200, 200, 200, 1.0F);
    }

    private static String describeAll(List<QmlElementBridge.Hit> hits) {
        StringBuilder sb = new StringBuilder("[");
        for (QmlElementBridge.Hit hit : hits) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(describe(hit));
        }
        return sb.append(']').toString();
    }

    private static List<net.minecraft.client.gui.GuiButton> publish(List<QmlElementBridge.Hit> hits) {
        List<net.minecraft.client.gui.GuiButton> target = new ArrayList<>();
        QmlElementBridge.publish(target, hits);
        return target;
    }

    /** A root holding one named item with one hit area. */
    private static Item singleControl(String objectName) {
        Item root = item(null);
        Item named = item(objectName);
        adopt(root, named);
        adopt(named, area("hit", 0, 0, 10, 10));
        return root;
    }

    private static Item item(String objectName) {
        Item node = new Item();
        if (objectName != null) {
            node.objectName.set(objectName);
        }
        node.width.set(200.0);
        node.height.set(200.0);
        return node;
    }

    private static MouseArea area(String tag, double x, double y, double w, double h) {
        MouseArea area = new MouseArea();
        area.x.set(x);
        area.y.set(y);
        area.width.set(w);
        area.height.set(h);
        AREA_TAGS.put(area, tag);
        return area;
    }

    /** Parent link plus child list, which is what qml4j's own component construction does. */
    private static void adopt(Item parent, Item child) {
        child.parent.set(parent);
        parent.children.add(child);
    }

    private static QmlElementBridge.Hit only(List<QmlElementBridge.Hit> hits) {
        assertEquals("expected exactly one published control, got " + hits, 1, hits.size());
        return hits.get(0);
    }

    /** The id published for the area carrying the given tag. */
    private static String idOf(Item root, String tag) {
        for (QmlElementBridge.Hit hit : bridge(root)) {
            if (tag.equals(AREA_TAGS.get(hit.area))) {
                return hit.id;
            }
        }
        fail("no published control carries the tag " + tag);
        return null;
    }

    /** A child-index path, which is what a published id must never be. */
    static boolean isPath(String id) {
        return id.startsWith("/") || id.matches("\\d+(/\\d+)+");
    }

    static String describe(QmlElementBridge.Hit hit) {
        return hit.id + " at {" + hit.x + "," + hit.y + " " + hit.w + "x" + hit.h + "}";
    }

    /**
     * The bridge's ancestor-walk bound, read from the compiled field.
     *
     * <p>Reflected rather than duplicated, so lowering the constant without thinking about the
     * QML fails here instead of quietly narrowing what the walk can find.
     */
    private static int nameLookupDepth() {
        try {
            java.lang.reflect.Field f = QmlElementBridge.class.getDeclaredField("NAME_LOOKUP_DEPTH");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("QmlElementBridge must declare NAME_LOOKUP_DEPTH", e);
        }
    }
}
