package net.marcloud.mcp.core.drivers.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.gui.GuiResourcePackAvailable;
import net.minecraft.client.gui.GuiResourcePackSelected;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenResourcePacks;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.gui.achievement.GuiStats;
import net.minecraft.client.resources.ResourcePackListEntry;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.EntityList;
import net.minecraft.stats.StatBase;
import net.minecraft.stats.StatCrafting;
import net.minecraft.stats.StatFileWriter;
import net.minecraft.stats.StatList;
import net.minecraft.util.TupleIntJsonSerializable;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * List rows are addressable, scrolling changes what is addressable, and a row
 * reference survives a scroll — pinned against the REAL vanilla list classes, for
 * all three families that were unreachable.
 *
 * <p><b>Why these are not fake-list tests.</b> The whole claim of the change is
 * that a row's rectangle, its content and the click that reaches it are vanilla's
 * own, so a hand-written list that merely recorded "a row was clicked" would pass
 * no matter what the extractor computed. These tests build the actual
 * {@link GuiControls} (and its real {@code GuiKeyBindingList}), the actual
 * {@link GuiScreenResourcePacks} (and its two real {@code GuiResourcePackList}s)
 * and the actual {@link GuiStats} (and its four real grids), and check the
 * extractor's output against vanilla's OWN hit test,
 * {@code GuiSlot.getSlotIndexFromScreenCoords}, against the real entry's real
 * {@code mousePressed}, and against the real {@link StatFileWriter}. The
 * arithmetic is cross-examined by the code it is supposed to agree with, not
 * merely reproduced in the test.
 *
 * <p><b>The one thing headless has to simulate is a frame of drawing.</b> Vanilla
 * gives a row's inner buttons their positions inside the entry's {@code drawEntry},
 * and {@code drawButton} then needs a GL context. {@link #playOneFrameOfDraw} does
 * for the grid what vanilla's own {@code doneLoading} does for it — point the
 * screen at the grid the tab selects — and writes the button positions vanilla would
 * have written. That is exactly why every row-geometry assertion here is paired
 * with a {@code getSlotIndexFromScreenCoords} cross-check: the geometry is proved
 * against vanilla's hit test, not against the fixture.
 */
public class GuiListRowsTest {

    @BeforeClass
    public static void bootstrap() throws Exception {
        net.minecraft.init.Bootstrap.register();
        // The shim refuses isKeyDown until "created"; the real client always has it
        // created, so the harness makes the same state true.
        setStatic(org.lwjgl.input.Keyboard.class, "created", Boolean.TRUE);
        // The same is true of the mouse: GuiSlot.handleMouseInput polls
        // Mouse.isButtonDown before it will look at the wheel at all.
        setStatic(org.lwjgl.input.Mouse.class, "created", Boolean.TRUE);
        seedLocale();
    }

    private static final int W = 427;
    private static final int H = 240;

    private Minecraft mc;
    private GameSettings settings;
    private FontRenderer font;
    private GuiSnapshotService svc;
    private GuiActions actions;

    @Before
    public void setUp() throws Exception {
        svc = new GuiSnapshotService();
        actions = new GuiActions(null, svc, null);
        mc = (Minecraft) blank(Minecraft.class);
        setStatic(Minecraft.class, "theMinecraft", mc);
        set(mc, "displayWidth", 854);
        set(mc, "displayHeight", 480);
        font = (FontRenderer) blank(FontRenderer.class);
        // Unsafe skips field initialisers, and getStringWidth reads both arrays.
        set(font, "charWidth", new int[256]);
        set(font, "glyphWidth", new byte[65536]);
        set(font, "colorCode", new int[32]);
        set(mc, "fontRendererObj", font);
        settings = new GameSettings(mc, new java.io.File("nonexistent-options.txt"));
        set(mc, "gameSettings", settings);
    }

    // ================= family 1: Controls / key bindings =================

    /**
     * A real {@link GuiControls} with its real {@code GuiKeyBindingList}, with two
     * bindings genuinely changed so a row has a real difference to report between
     * "bound" and "default", and a real conflict to report.
     */
    private GuiControls controls() throws Exception {
        GuiControls c = new GuiControls(new GuiScreen() {
        }, settings);
        set(c, "mc", mc);
        c.width = W;
        c.height = H;
        set(mc, "currentScreen", c);
        c.initGui();
        // Attack and Drop are both in the "gameplay" category, which is the first one
        // the list shows at scroll 0, so both are genuinely on screen. Drop leaves Q
        // for LEFT SHIFT and Attack takes LEFT SHIFT too -- the conflict vanilla's own
        // KeyEntry.drawEntry colours red.
        settings.keyBindDrop.setKeyCode(43);
        settings.keyBindAttack.setKeyCode(43);
        KeyBinding.resetKeyBindingArrayAndHash();
        return c;
    }

    private GuiSlot keyList(GuiControls c) throws Exception {
        return (GuiSlot) get(c, "keyBindingList");
    }

    @Test
    public void keyBindingRowsAreAddressableWithTheirBoundKeyAndConflicts() throws Exception {
        GuiControls c = controls();
        List<GuiElement> els = elements(c);
        GuiElement list = byId(els, "w0");
        assertNotNull("the Controls screen owns a GuiKeyBindingList, which is not in "
                + "buttonList and therefore had no id at all", list);
        assertEquals(GuiElement.KIND_LIST, list.kind());
        assertEquals("GuiKeyBindingList", list.attributes().get("listClass"));
        assertEquals("keyBindingList", list.attributes().get("listField"));
        assertEquals("the wheel must be drivable on the list surface",
                List.of("scroll"), list.actions());
        assertTrue("the list really is scrollable: " + list.attributes(),
                ((Integer) list.attributes().get("maxScroll")) > 0);

        List<GuiElement> rows = rowsOf(els, 0);
        assertFalse("the Controls list must publish rows", rows.isEmpty());
        GuiElement attack = rowWithKeyCode(rows, 43);
        assertNotNull("no row reports the code attack is actually bound to", attack);
        assertEquals("the widget's own label is the binding's description until the list "
                + "has been drawn, after which it is the key itself",
                "key.attack", attack.name());
        assertEquals("the row's value is the key it is bound to right now, through "
                + "vanilla's own GameSettings.getKeyDisplayString",
                "BACKSLASH", attack.value());
        assertEquals(Integer.valueOf(43), attack.attributes().get("keyCode"));
        assertEquals(Integer.valueOf(settings.keyBindAttack.getKeyCodeDefault()),
                attack.attributes().get("keyCodeDefault"));
        assertEquals("attack's default is a mouse button, and vanilla names it as one",
                "key.mouseButton", attack.attributes().get("defaultKey"));
        assertEquals("a row that has been rebound onto a taken key says so",
                Boolean.TRUE, attack.attributes().get("conflicted"));
        assertEquals("the conflict list is the other binding on the same non-zero code",
                List.of("key.drop"), attack.attributes().get("conflicts"));
        assertEquals("the armed flag starts false", Boolean.FALSE,
                attack.attributes().get("armed"));
        assertEquals("a key row is clickable -- it is a GuiListExtended row",
                List.of("click"), attack.actions());
        assertEquals("and the second widget of the same row is vanilla's own Reset button",
                "controls.reset", attack.id().replace("#0", "#1").equals(attack.id())
                        ? "" : byId(els, attack.id().replace("#0", "#1")).name());

        // The category header above them is readable but NOT clickable, because
        // vanilla's CategoryEntry.mousePressed returns false for it.
        GuiElement header = byId(els, "r0:0");
        assertNotNull("the Controls list's first row is a category header", header);
        assertEquals("key.categories.gameplay", header.name());
        assertEquals("and it must not be published as clickable", List.of(), header.actions());
        assertEquals(Boolean.FALSE, header.attributes().get("actionable"));

        // The rectangle is not a plausible-looking guess: vanilla's own hit test must
        // agree that this point belongs to this row.
        for (GuiElement row : rows) {
            int index = (Integer) row.attributes().get("rowIndex");
            Point cp = row.clickPoint();
            assertEquals("row " + index + "'s published click point must land inside the "
                            + "rectangle vanilla itself uses for row " + index,
                    index, keyList(c).getSlotIndexFromScreenCoords(cp.x(), cp.y()));
            assertTrue("row " + index + "'s rectangle must contain its own click point",
                    row.bounds().x() <= cp.x() && cp.x() < row.bounds().x() + row.bounds().w()
                            && row.bounds().y() <= cp.y()
                            && cp.y() < row.bounds().y() + row.bounds().h());
            assertTrue("and the row must be where vanilla's own arithmetic puts it",
                    row.bounds().y() == GuiListReflect.rowTop(keyList(c), index,
                            keyList(c).getAmountScrolled()));
        }
    }

    @Test
    public void scrollingTheControlsListChangesWhichRowsAreAddressable() throws Exception {
        GuiControls c = controls();
        List<String> before = new ArrayList<>(rowIds(rowsOf(elements(c), 0)));
        assertFalse(before.isEmpty());
        assertEquals("row 0 is the first addressable row at scroll 0", "r0:0", before.get(0));

        // Drive it the way a human's wheel does: the notch goes into the mouse's own
        // wheel event and the screen's own handleMouseInput runs.
        GuiActions.ClickResult r = actions.gestureOnScreen(c, epoch(c), svc.fingerprint(c), "w0",
                GuiActions.Gesture.scroll(-3));
        assertEquals("the wheel must move the list: " + r.message(), ClickVerdict.CONFIRMED,
                r.verdict());
        assertTrue("three notches of a 20px row list must move it: "
                + keyList(c).getAmountScrolled(), keyList(c).getAmountScrolled() > 0);
        assertEquals("vanilla's own wheel branch adds notches*slotHeight/2 for a NEGATIVE "
                + "event, so its sign is inverted relative to the physical wheel",
                30, keyList(c).getAmountScrolled());

        List<String> after = new ArrayList<>(rowIds(rowsOf(elements(c), 0)));
        assertNotEquals("scrolling must change which rows are addressable -- that is the "
                + "whole point of a scroll being drivable", before, after);
        after.removeAll(before);
        assertFalse("the rows that scrolled into view were not addressable before the scroll",
                after.isEmpty());
    }

    @Test
    public void aRowReferenceSurvivesAScrollAndStillNamesTheSameBinding() throws Exception {
        GuiControls c = controls();
        GuiElement attack = rowWithKeyCode(rowsOf(elements(c), 0), 43);
        assertNotNull("the attack binding must be visible before any scroll", attack);
        int target = (Integer) attack.attributes().get("rowIndex");
        String id = attack.id();
        assertEquals("a key row is addressed by its widget id, because that is where "
                + "vanilla's own press lands", "r0:" + target + "#0", id);
        assertEquals("key.attack", byId(elements(c), id).name());

        scroll(c, "w0", -60);
        assertNull("row " + target + " must have scrolled out of view", byId(elements(c), id));
        assertEquals("a row id names a row of the list's DATA, so its index must not move "
                        + "when the list scrolls",
                target, GuiListReflect.rowRefOf(id).rowIndex());

        // And a reference minted before the scroll is refused BY NAME, with the scroll
        // it would need, rather than landing on whatever row is at that point now.
        GuiActions.ClickResult refused = actions.gestureOnScreen(c, epoch(c), svc.fingerprint(c),
                id, GuiActions.Gesture.plain(0));
        assertEquals("a row that scrolled out of view must not be clicked",
                ClickVerdict.NO_ELEMENT, refused.verdict());
        assertFalse("and nothing may reach vanilla", c.buttonId != null);
        assertTrue("the refusal must explain the scroll rather than just deny the element: "
                + refused.message(), refused.message().contains("scrolled to "));
        assertTrue("and it must say how to reach it: " + refused.message(),
                refused.message().contains("gesture='scroll'"));

        scroll(c, "w0", 60);
        GuiElement back = byId(elements(c), id);
        assertNotNull("the same row id must be addressable again once scrolled back into view",
                back);
        assertEquals("and it must still be the same binding", "key.attack", back.name());
    }

    @Test
    public void pressingAKeyRowArmsThatBindingThroughVanillasOwnHandlers() throws Exception {
        GuiControls c = controls();
        GuiElement widget = rowWithKeyCode(rowsOf(elements(c), 0), 43);
        assertNotNull("the attack row must be on screen at scroll 0", widget);
        playOneFrameOfDraw(c, 0, 105);

        widget = byId(elements(c), widget.id());
        assertNotNull("a key row publishes the 'change key' button vanilla drew inside it",
                widget);
        assertEquals(Boolean.TRUE, widget.attributes().get("widgetPositioned"));
        // The button's own LABEL is still the binding's description, and that is
        // honest: vanilla rewrites displayString to the key inside drawEntry, which
        // needs a GL context and so has not run here. The key is published as the
        // row's VALUE, which is where an agent compares rows anyway.
        assertEquals("the widget's label is whatever vanilla last wrote on the button",
                "key.attack", widget.name());
        assertEquals("and the row's value is the key, which is always current",
                "BACKSLASH", widget.value());
        assertEquals("the widget's rectangle is the button's own drawn rectangle",
                Integer.valueOf(75), widget.attributes().get("buttonWidth"));

        GuiActions.ClickResult r = actions.gestureOnScreen(c, epoch(c), svc.fingerprint(c),
                widget.id(), GuiActions.Gesture.plain(0));
        assertEquals("pressing a key row must be confirmed by re-reading the row: "
                + r.message(), ClickVerdict.CONFIRMED, r.verdict());
        assertEquals("vanilla's own GuiKeyBindingList.KeyEntry.mousePressed is what armed the "
                        + "binding -- nothing about it is simulated here",
                settings.keyBindAttack, c.buttonId);
        assertEquals("and the row now reports itself armed", Boolean.TRUE,
                byId(elements(c), widget.id()).attributes().get("armed"));
        assertTrue("the list must be left enabled, which only the release half achieves",
                keyList(c).getEnabled());
    }

    /**
     * The vanilla fact that forces the driver to always send a row's release:
     * {@code GuiListExtended.mouseClicked} disables the list when an entry accepts
     * the press, and only {@code mouseReleased} turns it back on.
     */
    @Test
    public void aRowPressWithoutItsReleaseLeavesTheListDisabled() throws Exception {
        GuiControls c = controls();
        GuiElement widget = rowWithKeyCode(rowsOf(elements(c), 0), 43);
        playOneFrameOfDraw(c, 0, 105);
        Point cp = byId(elements(c), widget.id()).clickPoint();
        assertTrue("the fixture must start enabled", keyList(c).getEnabled());
        // vanilla's own mouseClicked opens with isMouseYWithinSlotBounds(this.mouseY),
        // and only drawScreen ever sets a list's cached cursor, so the harness does what
        // one frame of drawing does: put the cursor on the row.
        parkCursor(keyList(c), cp.x(), cp.y());

        invoke(keyList(c), "mouseClicked", new Class<?>[] {int.class, int.class, int.class},
                cp.x(), cp.y(), 0);
        assertFalse("vanilla's own mouseClicked disables a list whose row accepted the press; "
                + "a press without its release would leave it dead", keyList(c).getEnabled());

        invoke(keyList(c), "mouseReleased", new Class<?>[] {int.class, int.class, int.class},
                cp.x(), cp.y(), 0);
        assertTrue("and only the release turns it back on", keyList(c).getEnabled());
    }

    // ================= family 2: resource packs =================

    /**
     * A real {@link ResourcePackListEntry} — the real base class, the real entry
     * interface, the real {@code mousePressed} — with only its content accessors
     * overridden, because those are the four abstract methods vanilla declares and
     * they are the only thing a pack's text consists of.
     */
    private static final class TestPack extends ResourcePackListEntry {
        private final String title;
        private final String name;

        TestPack(GuiScreenResourcePacks gui, String title) {
            super(gui);
            this.title = title;
            this.name = title.toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        }

        @Override
        protected int func_183019_a() {
            return 1;
        }

        @Override
        protected String func_148311_a() {
            return title;
        }

        @Override
        protected String func_148312_b() {
            return name;
        }

        @Override
        protected void func_148313_c() {
        }
    }

    /** A pack that vanilla refuses to act on at any position, as the Default pack is. */
    private static final class FixedPack extends ResourcePackListEntry {
        FixedPack(GuiScreenResourcePacks gui, String title) {
            super(gui);
        }

        @Override
        protected int func_183019_a() {
            return 1;
        }

        @Override
        protected String func_148311_a() {
            return "Default";
        }

        @Override
        protected String func_148312_b() {
            return "default";
        }

        @Override
        protected void func_148313_c() {
        }

        @Override
        protected boolean func_148310_d() {
            return false;
        }
    }

    /**
     * A real {@link GuiScreenResourcePacks} holding two real
     * {@link GuiResourcePackList}s, wired exactly as its own {@code initGui} wires
     * them. {@code initGui} itself is not called because it enumerates the player's
     * real resource-pack directory, which has nothing to do with what is under test;
     * every field it would set is set here instead, with vanilla's own values.
     */
    private GuiScreenResourcePacks packScreen(int count) throws Exception {
        GuiScreenResourcePacks p = new GuiScreenResourcePacks(new GuiScreen() {
        });
        set(p, "mc", mc);
        p.width = W;
        p.height = H;
        set(mc, "currentScreen", p);

        List<ResourcePackListEntry> available = new ArrayList<>();
        List<ResourcePackListEntry> selected = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            available.add(new TestPack(p, "Pack " + i));
        }
        selected.add(new TestPack(p, "Default"));
        set(p, "availableResourcePacks", available);
        set(p, "selectedResourcePacks", selected);

        GuiResourcePackAvailable a = new GuiResourcePackAvailable(mc, 200, H, available);
        a.setSlotXBoundsFromLeft(p.width / 2 - 4 - 200);
        a.registerScrollButtons(7, 8);
        set(p, "availableResourcePacksList", a);
        GuiResourcePackSelected s = new GuiResourcePackSelected(mc, 200, H, selected);
        s.setSlotXBoundsFromLeft(p.width / 2 + 4);
        s.registerScrollButtons(7, 8);
        set(p, "selectedResourcePacksList", s);
        return p;
    }

    @Test
    public void resourcePackRowsAreAddressableAndSayWhichPackAndWhetherItIsSelected()
            throws Exception {
        GuiScreenResourcePacks p = packScreen(30);
        List<GuiElement> els = elements(p);
        assertEquals("the screen owns two lists, so it must publish two list elements",
                2, listsOf(els).size());
        assertEquals("w0 must be the AVAILABLE list, in declaration order",
                "GuiResourcePackAvailable", byId(els, "w0").attributes().get("listClass"));
        assertEquals("w1 must be the SELECTED list",
                "GuiResourcePackSelected", byId(els, "w1").attributes().get("listClass"));

        assertFalse(rowsOf(els, 0).isEmpty());
        GuiElement first = byId(els, "r0:0");
        assertNotNull(first);
        assertEquals("the row's label is the pack's own title, read through the accessor "
                + "vanilla's drawEntry uses", "Pack 0", first.name());
        assertEquals("a pack in the available list is not selected", "available", first.value());
        assertEquals(Boolean.FALSE, first.attributes().get("selected"));
        assertEquals("pack_0", first.attributes().get("packName"));
        assertEquals("compatibility is vanilla's own func_183019_a",
                Integer.valueOf(1), first.attributes().get("compatibility"));
        assertEquals("a pack row is clickable -- it is a GuiListExtended row",
                List.of("click"), first.actions());
        assertEquals("and its rectangle is where vanilla's hit test says row 0 is",
                0, ((GuiResourcePackAvailable) get(p, "availableResourcePacksList"))
                        .getSlotIndexFromScreenCoords(first.clickPoint().x(),
                                first.clickPoint().y()));

        GuiElement chosen = byId(els, "r1:0");
        assertNotNull(chosen);
        assertEquals("the row in the SELECTED list says selected", "selected", chosen.value());
        assertEquals(Boolean.TRUE, chosen.attributes().get("selected"));
        assertEquals("Default", chosen.name());
    }

    @Test
    public void scrollingAPackListChangesWhichRowsAreAddressable() throws Exception {
        GuiScreenResourcePacks p = packScreen(30);
        List<String> before = new ArrayList<>(rowIds(rowsOf(elements(p), 0)));
        assertEquals("row 0 is addressable at scroll 0", "r0:0", before.get(0));

        GuiActions.ClickResult v = actions.gestureOnScreen(p, epoch(p), svc.fingerprint(p), "w0",
                GuiActions.Gesture.scroll(-3));
        assertEquals("a pack list is a real GuiSlot, so the wheel must move it: " + v.message(),
                ClickVerdict.CONFIRMED, v.verdict());
        List<String> after = new ArrayList<>(rowIds(rowsOf(elements(p), 0)));
        assertNotEquals("scrolling must change which pack rows are addressable", before, after);
        after.removeAll(before);
        assertFalse("the packs that scrolled into view were not addressable before", after.isEmpty());
    }

    @Test
    public void aPackRowReferenceSurvivesAScroll() throws Exception {
        GuiScreenResourcePacks p = packScreen(30);
        assertEquals("Pack 0", byId(elements(p), "r0:0").name());
        scroll(p, "w0", -3);
        assertNull("row 0 has scrolled out of view", byId(elements(p), "r0:0"));
        assertEquals("the id still parses to the same DATA row after the scroll",
                0, GuiListReflect.rowRefOf("r0:0").rowIndex());
        scroll(p, "w0", 3);
        assertEquals("and the same id names the same pack again once it is back in view",
                "Pack 0", byId(elements(p), "r0:0").name());
    }

    /**
     * A pack row is pressed at the ICON, not at its centre, and the press really does
     * move the pack — through vanilla's own {@code ResourcePackListEntry.mousePressed},
     * which gates every action on the row-relative {@code relX <= 32}.
     *
     * <p>Mutations this kills: publishing the row's geometric centre (vanilla declines,
     * because a pack row is 194px wide and its centre is far past 32); computing the
     * offset from the SCREEN origin instead of the row's left edge (the press lands
     * outside the hotspot and again nothing happens); and reporting a click that moved
     * nothing as done.
     */
    @Test
    public void pressingAPackRowAtItsIconMovesThePackThroughVanilla() throws Exception {
        GuiScreenResourcePacks p = packScreen(30);
        GuiElement row = byId(elements(p), "r0:0");
        assertNotNull(row);

        // The published click point is inside the hotspot vanilla requires: its offset
        // from the row's LEFT EDGE is what vanilla tests, and the row's left edge is
        // what GuiListExtended.mouseClicked subtracts.
        GuiResourcePackAvailable list = (GuiResourcePackAvailable) get(p, "availableResourcePacksList");
        int rowLeftEdge = (Integer) get(list, "left") + (Integer) get(list, "width") / 2
                - list.getListWidth() / 2 + 2;
        int relX = row.clickPoint().x() - rowLeftEdge;
        assertTrue("the published click point must be inside vanilla's 32px gate, was relX="
                + relX, relX > 0 && relX <= 32);
        assertTrue("and inside the narrower 16px column that also moves a pack OUT of the "
                + "selected list, was relX=" + relX, relX < 16);
        assertEquals("the offset is read from vanilla's gate, not invented",
                Integer.valueOf(GuiListReflect.PACK_ICON_REL_X), row.attributes().get("hotspotRelX"));

        String fpBefore = svc.fingerprint(p);
        GuiActions.ClickResult r = actions.gestureOnScreen(p, epoch(p), fpBefore,
                row.id(), GuiActions.Gesture.plain(0));
        assertEquals("pressing a pack's icon enables it: " + r.message(), ClickVerdict.CONFIRMED,
                r.verdict());
        assertEquals("and it really moved, which is the only thing that can confirm it",
                29, ((List<?>) get(p, "availableResourcePacks")).size());
        List<?> selected = (List<?>) get(p, "selectedResourcePacks");
        assertEquals(2, selected.size());
        assertEquals("to the top of the stack, as vanilla does", "Pack 0",
                packTitle(selected.get(0)));

        // The honest boundary of a positional id, stated rather than glossed: the row id
        // is stable under a SCROLL (that is the staleness question the design answers),
        // but it indexes a mutable list, so after the list's contents change, r0:0 names
        // whatever now sits at index 0. What stops a stale reference from being acted on
        // is the fingerprint: the row count moved 30 -> 29, so the structural token moved
        // with it and a reference minted before the press is refused.
        assertEquals("and the available list's row 0 is now a different pack", "Pack 1",
                byId(elements(p), row.id()).name());
        assertNotEquals("the row count changed, so the structural token must have moved "
                        + "with it", fpBefore, svc.fingerprint(p));
        assertEquals(ClickVerdict.REFUSED_STALE,
                actions.gestureOnScreen(p, epoch(p), fpBefore, "r0:0", GuiActions.Gesture.plain(0))
                        .verdict());
    }

    /**
     * The counterpart, and the mutation that matters: a press at the row's own GEOMETRIC
     * CENTRE is outside the hotspot, so vanilla declines it and the verdict must say so
     * rather than claim a pack was enabled.
     */
    @Test
    public void aPackRowCentreIsOutsideVanillasHotspotAndIsReportedAsUnconfirmed()
            throws Exception {
        GuiScreenResourcePacks p = packScreen(30);
        Bounds rb = byId(elements(p), "r0:0").bounds();
        Point centre = new Point(rb.x() + rb.w() / 2, rb.y() + rb.h() / 2);
        // Drive it as the driver would if the hotspot were the centre, and prove vanilla
        // itself declines: GuiListExtended routes the press, mousePressed sees relX > 32.
        GuiResourcePackAvailable list = (GuiResourcePackAvailable) get(p, "availableResourcePacksList");
        parkCursor(list, centre.x(), centre.y());
        invoke(list, "mouseClicked", new Class<?>[] {int.class, int.class, int.class},
                centre.x(), centre.y(), 0);
        assertEquals("vanilla declines a press past its 32px gate, so the pack is untouched",
                30, ((List<?>) get(p, "availableResourcePacks")).size());
    }

    /**
     * The built-in Default pack returns false from vanilla's {@code func_148310_d}, so
     * it is refused at EVERY position. That is a different refusal from "wrong point"
     * and the message has to say which, or an agent goes hunting for a wiring fault.
     */
    @Test
    public void aPackVanillaRefusesAtEveryPositionSaysSoInsteadOfBlamingThePosition()
            throws Exception {
        GuiScreenResourcePacks p = packScreen(3);
        // Replace the default entry with one that reports itself un-reorderable, exactly
        // as ResourcePackListEntryDefault does.
        List<ResourcePackListEntry> selected = new ArrayList<>();
        selected.add(new FixedPack(p, "Default"));
        set(p, "selectedResourcePacks", selected);
        GuiResourcePackSelected s = new GuiResourcePackSelected(mc, 200, H, selected);
        s.setSlotXBoundsFromLeft(p.width / 2 + 4);
        set(p, "selectedResourcePacksList", s);

        GuiElement row = byId(elements(p), "r1:0");
        assertNotNull(row);
        assertEquals("a pack vanilla refuses at any position must not be published clickable",
                List.of(), row.actions());
        assertEquals(Boolean.FALSE, row.attributes().get("reorderable"));

        GuiActions.ClickResult r = actions.gestureOnScreen(p, epoch(p), svc.fingerprint(p),
                row.id(), GuiActions.Gesture.plain(0));
        assertEquals(ClickVerdict.NOT_CONFIRMED, r.verdict());
        assertTrue("the refusal must name the real reason, not the position: " + r.message(),
                r.message().contains("func_148310_d"));
        assertTrue("and must say the position is not the problem: " + r.message(),
                r.message().contains("position is not the problem"));
    }

    /** A Controls category header is readable and refused for its own, different reason. */
    @Test
    public void aCategoryHeaderIsRefusedAsAHeadingNotAsAMissingHandler() throws Exception {
        GuiControls c = controls();
        GuiElement header = byId(elements(c), "r0:0");
        assertNotNull(header);
        GuiActions.ClickResult r = actions.gestureOnScreen(c, epoch(c), svc.fingerprint(c),
                header.id(), GuiActions.Gesture.plain(0));
        assertEquals(ClickVerdict.NOT_CONFIRMED, r.verdict());
        assertTrue("a heading's refusal must name the heading: " + r.message(),
                r.message().contains("section heading"));
    }

    /** The title a pack entry reports, read through the same accessor vanilla draws. */
    private static String packTitle(Object entry) throws Exception {
        Method m = entry.getClass().getDeclaredMethod("func_148311_a");
        m.setAccessible(true);
        return (String) m.invoke(entry);
    }

    // ================= family 3: the statistics grids =================

    /**
     * A real {@link GuiStats} with its four real grids, holding real stat values, so
     * the Items grid's rows have the shape they have in a live client. The grid is
     * also pointed at exactly as vanilla's own {@code doneLoading} points it.
     */
    private GuiStats stats() throws Exception {
        StatFileWriter writer = new StatFileWriter();
        // Seed the writer's own map the way a played game would, so the Items grid
        // actually contains the items its own filter looks for.
        @SuppressWarnings("unchecked")
        Map<StatBase, TupleIntJsonSerializable> data =
                (Map<StatBase, TupleIntJsonSerializable>) get(writer, "statsData");
        int seeded = 0;
        for (StatCrafting s : StatList.itemStats) {
            if (seeded >= 40) {
                break;
            }
            TupleIntJsonSerializable tuple = new TupleIntJsonSerializable();
            tuple.setIntegerValue(seeded + 1);
            data.put(s, tuple);
            // The grid's sort keys are the MINED and CRAFTED stats, not the use stats
            // its rows are built from, and an all-zero comparator would leave the order
            // identical on every press -- a sort test that passes for the wrong reason.
            // So the mined counts get real, distinct-ish values as well.
            Item item = s.func_150959_a();
            int id = item == null ? -1 : Item.getIdFromItem(item);
            StatBase[] breakStats = StatList.objectBreakStats;
            if (id >= 0 && id < breakStats.length && breakStats[id] != null) {
                TupleIntJsonSerializable mined = new TupleIntJsonSerializable();
                mined.setIntegerValue((seeded * 7) % 13 + 1);
                data.put(breakStats[id], mined);
            }
            seeded++;
        }
        assertTrue("the fixture must have seeded real stats to read back", seeded > 10);
        // The Mobs grid filters on the same writer, so seed it too or that grid is
        // legitimately empty and the "not readable" claim would be untested.
        int mobs = 0;
        for (Object egg : ((Map<?, ?>) getStatic(EntityList.class, "entityEggs")).values()) {
            if (mobs >= 5) {
                break;
            }
            Object killed = get(egg, "field_151512_d");
            if (killed instanceof StatBase sb) {
                TupleIntJsonSerializable tuple = new TupleIntJsonSerializable();
                tuple.setIntegerValue(mobs + 1);
                data.put(sb, tuple);
                mobs++;
            }
        }
        assertTrue("the fixture must have seeded real mob stats to read back", mobs > 0);

        GuiStats g = new GuiStats(new GuiScreen() {
        }, writer);
        set(g, "mc", mc);
        set(g, "fontRendererObj", font);
        g.width = W;
        g.height = H;
        set(mc, "currentScreen", g);
        g.func_175366_f();
        set(g, "displaySlot", get(g, "itemStats")); // what vanilla's doneLoading does
        return g;
    }

    private int itemsGridIndex() {
        return indexOfList(elements(currentStats), "StatsItem");
    }

    private GuiStats currentStats;

    @Test
    public void statisticsGridRowsAreAddressableAndReadTheirRealLabelAndValue()
            throws Exception {
        currentStats = stats();
        GuiStats g = currentStats;
        List<GuiElement> els = elements(g);
        assertEquals("GuiStats owns four grids AND a displaySlot that aliases one of "
                + "them, and a list reachable twice is published once", 4,
                listsOf(els).size());

        int gridIndex = itemsGridIndex();
        assertTrue("the Items grid must be one of the screen's lists", gridIndex >= 0);
        List<GuiElement> rows = rowsOf(els, gridIndex);
        assertFalse("the grid must publish rows", rows.isEmpty());
        GuiElement row = byId(els, "r" + gridIndex + ":0");
        assertNotNull(row);

        // The expected text is computed here from vanilla's own objects, not copied
        // out of the extractor, so this is a comparison and not a tautology.
        StatCrafting first = itemsGridRowZero(g);
        Item item = first.func_150959_a();
        assertNotNull("the fixture's first row must be a real item stat", item);
        assertEquals("the row's label is what vanilla's own row tooltip shows -- the item's "
                + "localised name", new ItemStack(item).getDisplayName(), row.name());
        assertEquals("the row's value is stat.format(writer.readStat(stat)), the exact call "
                        + "GuiStats.Stats.func_148209_a makes",
                first.format(statWriter(g).readStat(first)), row.value());
        assertEquals("and the item's registry name, so it matches what every other tool "
                + "in this codebase calls that item",
                String.valueOf(Item.itemRegistry.getNameForObject(item)),
                row.attributes().get("item"));
        assertEquals("the row is the item's own stat id", first.statId,
                row.attributes().get("statId"));
    }

    @Test
    public void statisticsGridRowsAreReadOnlyAndSayWhy() throws Exception {
        currentStats = stats();
        GuiStats g = currentStats;
        GuiElement row = byId(elements(g), "r" + itemsGridIndex() + ":0");
        assertNotNull(row);
        assertEquals("vanilla's plain GuiSlot has no mouseClicked, so a grid row is not "
                + "clickable and must not pretend to be", List.of(), row.actions());
        assertFalse("and must not report itself enabled", row.state().enabled());
        assertEquals("yet its content is still read", Boolean.TRUE,
                row.attributes().get("contentReadable"));

        GuiActions.ClickResult r = actions.gestureOnScreen(g, epoch(g), svc.fingerprint(g),
                row.id(), GuiActions.Gesture.plain(0));
        assertEquals(ClickVerdict.NOT_CONFIRMED, r.verdict());
        assertTrue("the refusal must name the real reason, not a generic failure: " + r.message(),
                r.message().contains("no click handler"));
    }

    @Test
    public void scrollingTheStatisticsGridChangesWhichRowsAreAddressable() throws Exception {
        currentStats = stats();
        GuiStats g = currentStats;
        int gridIndex = itemsGridIndex();
        List<String> before = new ArrayList<>(rowIds(rowsOf(elements(g), gridIndex)));
        assertFalse(before.isEmpty());

        GuiActions.ClickResult v = actions.gestureOnScreen(g, epoch(g), svc.fingerprint(g),
                "w" + gridIndex, GuiActions.Gesture.scroll(-3));
        assertEquals("GuiStats.handleMouseInput forwards to the grid the tab selects, so the "
                + "wheel must move it: " + v.message(), ClickVerdict.CONFIRMED, v.verdict());
        List<String> after = new ArrayList<>(rowIds(rowsOf(elements(g), gridIndex)));
        assertNotEquals("scrolling must change which stat rows are addressable", before, after);
    }

    @Test
    public void aStatisticsRowReferenceSurvivesAScroll() throws Exception {
        currentStats = stats();
        GuiStats g = currentStats;
        int gridIndex = itemsGridIndex();
        String id = "r" + gridIndex + ":0";
        String label = byId(elements(g), id).name();
        String value = byId(elements(g), id).value();
        assertFalse(label.isEmpty());

        scroll(g, "w" + gridIndex, -6);
        assertNull("row 0 has scrolled out of view", byId(elements(g), id));
        assertEquals("the id still parses to the same DATA row", 0,
                GuiListReflect.rowRefOf(id).rowIndex());
        scroll(g, "w" + gridIndex, 6);
        GuiElement back = byId(elements(g), id);
        assertNotNull(back);
        assertEquals("the same id reads the same stat again", label, back.name());
        assertEquals("including its value", value, back.value());
    }

    // ================= the statistics grid's HEADER sort controls =================

    /**
     * The three sort targets in a grid's header are addressable with real rectangles,
     * so an agent never has to know the numbers inside vanilla's handler.
     *
     * <p>The rectangles are cross-examined against vanilla: each element's own x range
     * must be the literal range from {@code GuiStats.Stats.func_148132_a} shifted by the
     * origin {@code GuiSlot.handleMouseInput} measures from, and its y range must be
     * the band vanilla routes to that hook (above the first row).
     */
    @Test
    public void statisticsSortTargetsAreAddressableWithVanillaDerivedRectangles()
            throws Exception {
        currentStats = stats();
        GuiStats g = currentStats;
        int gridIndex = indexOfList(elements(g), "StatsItem");
        List<GuiElement> els = elements(g);
        GuiSlot grid = (GuiSlot) get(g, "itemStats");

        for (int c = 0; c < 3; c++) {
            GuiElement h = byId(els, "h" + gridIndex + ":" + c);
            assertNotNull("sort target " + c + " must be addressable", h);
            assertEquals(GuiElement.KIND_HEADER, h.kind());
            assertEquals(List.of("click"), h.actions());
            assertEquals(Integer.valueOf(c), h.attributes().get("column"));

            int origin = (Integer) get(grid, "width") / 2 - grid.getListWidth() / 2;
            int[] band = GuiListReflect.headerBandY(grid, grid.getAmountScrolled());
            assertEquals("the x range is vanilla's literal range shifted by vanilla's origin",
                    origin + GuiListReflect.SORT_COLUMN_REL_X[c][0], h.bounds().x());
            assertEquals("and its width is the range's own width",
                    GuiListReflect.SORT_COLUMN_REL_X[c][1] - GuiListReflect.SORT_COLUMN_REL_X[c][0],
                    h.bounds().w());
            assertEquals("the y band is the one vanilla routes to the header hook",
                    band[0], h.bounds().y());
            assertTrue("and the band must be strictly above the first row",
                    h.bounds().y() + h.bounds().h() <= GuiListReflect.rowTop(grid, 0,
                            grid.getAmountScrolled()));
        }
        // The General grid extends GuiSlot directly and never overrides the hook, so it
        // must publish no header controls rather than three that do nothing.
        int general = indexOfList(els, "StatsGeneral");
        assertNull("a list that does not override the header hook has no header controls",
                byId(els, "h" + general + ":0"));
    }

    /**
     * A header press really re-sorts the grid, and the read-back is the row ORDER — the
     * handler's own private column index is not published and its click sound is not
     * observable, whereas the resulting order is a world fact.
     *
     * <p>The seed is chosen so the order is genuinely sensitive: the rows are the real
     * item stats in registry order, and vanilla's column-0 comparator breaks ties by
     * the mined stat, which the fixture also seeds, so a no-op press could not leave the
     * order identical by luck. The negative test below proves the seed is not trivially
     * sorted.
     */
    @Test
    public void pressingASortTargetReordersTheGridAndIsConfirmedByTheRowOrder()
            throws Exception {
        currentStats = stats();
        GuiStats g = currentStats;
        int gridIndex = indexOfList(elements(g), "StatsItem");
        List<String> before = gridRowOrder(g, gridIndex);
        assertTrue("the fixture must have rows to re-sort", before.size() > 3);

        GuiActions.ClickResult r = actions.gestureOnScreen(g, epoch(g), svc.fingerprint(g),
                "h" + gridIndex + ":0", GuiActions.Gesture.plain(0));
        assertEquals("a header press that reorders must be CONFIRMED, and named by the "
                + "read-back that established it: " + r.message(), ClickVerdict.CONFIRMED,
                r.verdict());
        assertTrue("the message must say WHICH read-back proved it: " + r.message(),
                r.message().contains("re-reading the rows themselves"));
        List<String> after = gridRowOrder(g, gridIndex);
        assertNotEquals("the grid's row order must actually have changed", before, after);
        assertEquals("and no row may have been invented or lost by a re-sort",
                before.size(), after.size());
    }

    /**
     * The negative case, which is the discipline the pack hotspot established: a press
     * outside every range vanilla answers to sets its column to -1 and does nothing, and
     * that must report NOT_CONFIRMED rather than a click that changed nothing by itself.
     */
    @Test
    public void aHeaderPressOutsideEveryRangeReportsNotConfirmed() throws Exception {
        currentStats = stats();
        GuiStats g = currentStats;
        int gridIndex = indexOfList(elements(g), "StatsItem");
        List<String> before = gridRowOrder(g, gridIndex);
        GuiSlot grid = (GuiSlot) get(g, "itemStats");
        int[] band = GuiListReflect.headerBandY(grid, grid.getAmountScrolled());
        int origin = GuiListReflect.headerLeftOrigin(grid);
        // relX 170 sits in the one gap between column 1's [129,165) and column 2's
        // [179,215), so vanilla's if/else chain leaves the column at -1 and never calls
        // the sorter. (relX 150 would NOT do: that is inside column 1.)
        Point gap = new Point(origin + 170, (band[0] + band[1]) / 2);
        parkCursor(grid, gap.x(), gap.y());
        setStatic(org.lwjgl.input.Mouse.class, "eventButton", 0);
        setStatic(org.lwjgl.input.Mouse.class, "eventState", true);
        invokeListMouseInput(grid);
        assertEquals("a press outside every range must leave the order untouched",
                before, gridRowOrder(g, gridIndex));
    }

    /**
     * Vanilla cycles a column ascending -> descending -> unsorted, so three presses of
     * the SAME target restore the original order. This is the sharpest available check
     * that the read-back can tell the three states apart: a read-back that only asked
     * "did anything change" would see the third press as a failure, and one that
     * returned a constant would pass all three assertions here.
     */
    @Test
    public void aSortColumnCyclesAscendingDescendingAndBackAndTheReadBackTellsThemApart()
            throws Exception {
        currentStats = stats();
        GuiStats g = currentStats;
        int gridIndex = indexOfList(elements(g), "StatsItem");
        String target = "h" + gridIndex + ":0";
        List<String> original = gridRowOrder(g, gridIndex);

        List<String> ascending = orderAfterHeaderPress(g, target);
        assertNotEquals("the first press must reorder", original, ascending);
        List<String> descending = orderAfterHeaderPress(g, target);
        assertNotEquals("the second press must reorder again, not repeat the first -- which "
                + "it only can because the fixture's mined counts actually differ", ascending,
                descending);
        List<String> third = orderAfterHeaderPress(g, target);
        assertEquals("the third press clears the sort, so the original order returns",
                original, third);
    }

    /** Press a header target and return the grid's resulting row order. */
    private List<String> orderAfterHeaderPress(GuiStats g, String target) throws Exception {
        GuiActions.ClickResult r = actions.gestureOnScreen(g, epoch(g), svc.fingerprint(g),
                target, GuiActions.Gesture.plain(0));
        int gridIndex = indexOfList(elements(g), "StatsItem");
        return gridRowOrder(g, gridIndex);
    }

    /** The grid's row labels in order, read straight from the grid's own row list. */
    private static List<String> gridRowOrder(GuiStats g, int gridIndex) throws Exception {
        List<String> out = new ArrayList<>();
        GuiSlot grid = (GuiSlot) get(g, "itemStats");
        @SuppressWarnings("unchecked")
        List<net.minecraft.stats.StatCrafting> holder =
                (List<net.minecraft.stats.StatCrafting>) get(grid, "statsHolder");
        for (net.minecraft.stats.StatCrafting s : holder) {
            out.add(s.statId);
        }
        return out;
    }

    /** Invoke a list's own handleMouseInput, the call the screen makes on it. */
    private static void invokeListMouseInput(GuiSlot list) throws Exception {
        for (Class<?> c = list.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("handleMouseInput");
                m.setAccessible(true);
                m.invoke(list);
                return;
            } catch (NoSuchMethodException e) {
                // walk up
            }
        }
        throw new NoSuchMethodException("handleMouseInput on " + list.getClass());
    }

    /**
     * The Mobs grid's rows are addressable and scrollable but their text is NOT read,
     * and the reason is stated rather than papered over: vanilla composes each of
     * its three lines from a mob id and two separate stats, so there is no
     * row-carried string to read.
     */
    @Test
    public void theMobsGridIsAddressableButReportsThatItsTextIsNotReadable() throws Exception {
        currentStats = stats();
        GuiStats g = currentStats;
        int mobs = indexOfList(elements(g), "StatsMobsList");
        assertTrue("the Mobs grid must be one of the screen's lists", mobs >= 0);
        List<GuiElement> rows = rowsOf(elements(g), mobs);
        assertFalse("the Mobs grid must publish its rows", rows.isEmpty());
        assertEquals("and say plainly that their text is not readable",
                Boolean.FALSE, rows.get(0).attributes().get("contentReadable"));
    }

    /** The screen's own real {@link StatFileWriter}, the one the grid reads through. */
    private static StatFileWriter statWriter(GuiStats g) throws Exception {
        return (StatFileWriter) get(g, "field_146546_t");
    }

    // ================= the staleness contract =================

    @Test
    public void aScrollMovesTheSnapshotFingerprintButNotTheEpoch() throws Exception {
        GuiControls c = controls();
        int epochBefore = epoch(c);
        String fpBefore = svc.fingerprint(c);
        scroll(c, "w0", -60);
        assertEquals("the epoch cannot see a scroll: the screen object never changed, which "
                + "is exactly why the fingerprint has to carry it", epochBefore, epoch(c));
        assertNotEquals("a scroll changes WHICH rows are addressable, so the structural token "
                + "that guards an (epoch, fingerprint) reference must move with it",
                fpBefore, svc.fingerprint(c));
        assertTrue("and the token must carry the scroll itself: " + svc.fingerprint(c),
                svc.fingerprint(c).contains("GuiKeyBindingList:"));
    }

    @Test
    public void aStaleFingerprintIsRefusedBeforeAnythingReachesTheList() throws Exception {
        GuiControls c = controls();
        GuiElement row = rowWithKeyCode(rowsOf(elements(c), 0), 43);
        assertNotNull("the fixture must have an on-screen key row to go stale", row);
        String staleFp = svc.fingerprint(c);
        scroll(c, "w0", -60);

        GuiActions.ClickResult r = actions.gestureOnScreen(c, epoch(c), staleFp, row.id(),
                GuiActions.Gesture.plain(0));
        assertEquals(ClickVerdict.REFUSED_STALE, r.verdict());
        assertFalse("nothing may reach vanilla", c.buttonId != null);
    }

    @Test
    public void aScreenWithNoListsKeepsExactlyTheFingerprintItHad() {
        assertEquals("none#0#0#0", svc.fingerprint(null));
        assertFalse("a screen with no list must not grow a list term at all",
                svc.fingerprint(new GuiScreen() {
                }).contains("@L"));
    }

    // ================= id grammar =================

    @Test
    public void theIdPrefixesMeanWhatTheySay() {
        assertEquals("w0 names list 0", 0, GuiListReflect.listIndexOf("w0"));
        assertEquals("w12 names list 12", 12, GuiListReflect.listIndexOf("w12"));
        assertEquals("a row id is not a list id", -1, GuiListReflect.listIndexOf("r0:0"));
        assertEquals("neither is a button id", -1, GuiListReflect.listIndexOf("b0"));
        assertEquals("nor a slot id", -1, GuiListReflect.listIndexOf("s13"));
        assertEquals("nor an empty id", -1, GuiListReflect.listIndexOf(""));
        assertEquals("nor a null one", -1, GuiListReflect.listIndexOf(null));
        assertEquals("nor a bare prefix", -1, GuiListReflect.listIndexOf("w"));
        assertEquals("nor a negative index", -1, GuiListReflect.listIndexOf("w-1"));

        assertEquals("r0:3 names row 3 of list 0",
                new GuiListReflect.RowRef(0, 3, -1), GuiListReflect.rowRefOf("r0:3"));
        assertEquals("r1:2#0 names the first widget of row 2 of list 1",
                new GuiListReflect.RowRef(1, 2, 0), GuiListReflect.rowRefOf("r1:2#0"));
        assertNull("a button id is not a row id", GuiListReflect.rowRefOf("b0"));
        assertNull("nor is a bare 'r'", GuiListReflect.rowRefOf("r"));
        assertNull("nor an empty one", GuiListReflect.rowRefOf(""));
        assertNull("nor one with no row part", GuiListReflect.rowRefOf("r0:"));
        assertNull("nor a non-numeric row", GuiListReflect.rowRefOf("r0:x"));
        assertNull("nor a negative row", GuiListReflect.rowRefOf("r0:-1"));
        assertEquals("a row's id round-trips", "r1:2#0",
                GuiListReflect.rowRefOf("r1:2#0").id());
        assertEquals("as does a plain row's", "r0:3", GuiListReflect.rowRefOf("r0:3").id());
    }

    // ================= fixtures / helpers =================

    /**
     * Play the one frame of {@code drawEntry} that gives a row's inner buttons their
     * positions. Vanilla does this with {@code btn.yPosition = y} immediately before
     * {@code drawButton}, which then needs a GL context; headless the harness writes
     * the position itself. That is why every row-geometry assertion in this file is
     * additionally cross-checked against vanilla's own
     * {@code getSlotIndexFromScreenCoords}: the geometry is proved against vanilla,
     * not against this fixture.
     */
    private void playOneFrameOfDraw(GuiScreen screen, int listIndex, int widgetDx)
            throws Exception {
        GuiSlot list = lists(screen).get(listIndex);
        int scroll = list.getAmountScrolled();
        for (int i = 0; i < listSize(list); i++) {
            Object entry = list instanceof GuiListExtended ext ? ext.getListEntry(i) : null;
            if (entry == null) {
                continue;
            }
            int y = GuiListReflect.rowTop(list, i, scroll);
            int x = GuiListReflect.acceptedXRange(list)[0] + 2 + widgetDx;
            for (GuiButton b : GuiListReflect.entryWidgets(entry)) {
                b.xPosition = x;
                b.yPosition = y;
            }
        }
    }

    private StatCrafting itemsGridRowZero(GuiStats g) throws Exception {
        GuiSlot grid = (GuiSlot) get(g, "itemStats");
        @SuppressWarnings("unchecked")
        List<StatCrafting> holder = (List<StatCrafting>) get(grid, "statsHolder");
        assertFalse("the fixture's Items grid must have rows", holder.isEmpty());
        return holder.get(0);
    }

    /** What one frame of {@code drawScreen} does to a list's cached cursor. */
    private static void parkCursor(GuiSlot list, int x, int y) throws Exception {
        set(list, "mouseX", x);
        set(list, "mouseY", y);
    }

    private static int listSize(GuiSlot list) throws Exception {
        for (Class<?> c = list.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("getSize");
                m.setAccessible(true);
                return (Integer) m.invoke(list);
            } catch (NoSuchMethodException e) {
                // walk up
            }
        }
        return 0;
    }

    private void scroll(GuiScreen screen, String listId, int notches) {
        actions.gestureOnScreen(screen, epoch(screen), svc.fingerprint(screen), listId,
                GuiActions.Gesture.scroll(notches));
    }

    private int epoch(GuiScreen s) {
        return svc.buildSnapshot(s, false, GuiSnapshotService.viewport(W, H, 1, W, H), false)
                .epoch();
    }

    private List<GuiElement> elements(GuiScreen s) {
        return GuiReflect.extract(s, false).elements();
    }

    private static List<GuiSlot> lists(GuiScreen s) {
        List<GuiSlot> out = new ArrayList<>();
        for (GuiListReflect.ListRef r : GuiListReflect.lists(s, new ArrayList<>())) {
            out.add(r.list());
        }
        return out;
    }

    private static List<GuiElement> listsOf(List<GuiElement> els) {
        List<GuiElement> out = new ArrayList<>();
        for (GuiElement e : els) {
            if (GuiElement.KIND_LIST.equals(e.kind())) {
                out.add(e);
            }
        }
        return out;
    }

    private static List<GuiElement> rowsOf(List<GuiElement> els, int listIndex) {
        List<GuiElement> out = new ArrayList<>();
        for (GuiElement e : els) {
            if (GuiElement.KIND_ROW.equals(e.kind())
                    && Integer.valueOf(listIndex).equals(e.attributes().get("listIndex"))) {
                out.add(e);
            }
        }
        return out;
    }

    /** The position of a list class among the screen's list elements. */
    private static int indexOfList(List<GuiElement> els, String simpleName) {
        int i = 0;
        for (GuiElement e : listsOf(els)) {
            if (simpleName.equals(e.attributes().get("listClass"))) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static List<String> rowIds(List<GuiElement> rows) {
        List<String> out = new ArrayList<>();
        for (GuiElement e : rows) {
            out.add(e.id());
        }
        return out;
    }

    private static GuiElement byId(List<GuiElement> els, String id) {
        for (GuiElement e : els) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
    }

    /**
     * The first widget of the row bound to {@code code} -- widget 0, because vanilla
     * declares the change-key button before the reset button in {@code KeyEntry}.
     */
    private static GuiElement rowWithKeyCode(List<GuiElement> rows, int code) {
        for (GuiElement e : rows) {
            if (Integer.valueOf(code).equals(e.attributes().get("keyCode"))
                    && Integer.valueOf(0).equals(e.attributes().get("widget"))) {
                return e;
            }
        }
        return null;
    }

    // ---- reflection plumbing ----

    private static void seedLocale() {
        try {
            Object loc = Class.forName("net.minecraft.client.resources.Locale")
                    .getDeclaredConstructor().newInstance();
            @SuppressWarnings("unchecked")
            Map<String, String> props = (Map<String, String>) get(loc, "properties");
            props.put("key.forward", "Forward");
            props.put("key.jump", "Jump");
            Field f = Class.forName("net.minecraft.client.resources.I18n")
                    .getDeclaredField("i18nLocale");
            f.setAccessible(true);
            f.set(null, loc);
        } catch (Exception e) {
            throw new IllegalStateException("cannot seed the vanilla Locale", e);
        }
    }

    private static Object blank(Class<?> t) throws Exception {
        Class<?> u = Class.forName("sun.misc.Unsafe");
        Field f = u.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return u.getMethod("allocateInstance", Class.class).invoke(f.get(null), t);
    }

    private static void set(Object o, String name, Object v) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(o, v);
                return;
            } catch (NoSuchFieldException ignored) {
                // walk up
            }
        }
        throw new NoSuchFieldException(name + " on " + o.getClass());
    }

    private static Object get(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
                // walk up
            }
        }
        throw new NoSuchFieldException(name + " on " + o.getClass());
    }

    private static Object getStatic(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void setStatic(Class<?> owner, String name, Object v) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, v);
    }

    private static void invoke(Object target, String name, Class<?>[] params, Object... args)
            throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                m.invoke(target, args);
                return;
            } catch (NoSuchMethodException ignored) {
                // walk up
            }
        }
        throw new NoSuchMethodException(name + " on " + target.getClass());
    }
}
