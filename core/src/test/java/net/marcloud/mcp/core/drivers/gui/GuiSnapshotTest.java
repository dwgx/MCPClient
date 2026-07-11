package net.marcloud.mcp.core.drivers.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;

import org.junit.Test;

/**
 * Headless tests for the GUI foundation layer (data model + reflection + service).
 * These run with NO live game: they build synthetic {@link GuiScreen} / {@link
 * GuiContainer} subclasses whose {@code buttonList} / slots are populated by hand,
 * then assert that {@link GuiReflect} / {@link GuiSnapshotService} extract the
 * correct labels, bounds and click-points from the REAL vanilla client classes
 * (on the test classpath via the reactor).
 *
 * <p>The reads never touch the {@code Minecraft} singleton, so they are safe off
 * the game thread; {@link GuiSnapshotService#buildSnapshot} is the pure seam used
 * instead of {@link GuiSnapshotService#snapshot} (which would marshal via
 * {@code GameBridge}).
 */
public class GuiSnapshotTest {

    // ---- synthetic fixtures -------------------------------------------------

    /** A plain screen exposing a hook to inject buttons into the protected list. */
    private static final class FakeScreen extends GuiScreen {
        void addButton(GuiButton b) {
            this.buttonList.add(b);
        }

        void addLabel(net.minecraft.client.gui.GuiLabel l) {
            this.labelList.add(l);
        }
    }

    /** A screen that also declares a GuiTextField field (discovered reflectively). */
    private static final class FakeTextScreen extends GuiScreen {
        // package/private declared field of GuiTextField type — the exact shape
        // GuiReflect's heuristic scan is meant to find.
        private GuiTextField search =
                new GuiTextField(0, null, 40, 60, 120, 20);

        void addButton(GuiButton b) {
            this.buttonList.add(b);
        }
    }

    /** A minimal concrete GuiContainer over a hand-built Container of Slots. */
    private static final class FakeContainer extends GuiContainer {
        FakeContainer(Container c) {
            super(c);
        }

        @Override
        protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
            // no-op: never rendered in a headless test
        }

        void addButton(GuiButton b) {
            this.buttonList.add(b);
        }

        void setGui(int left, int top) {
            this.guiLeft = left;
            this.guiTop = top;
        }
    }

    private static Container containerWith(Slot... slots) {
        Container c = new Container() {
            @Override
            public boolean canInteractWith(net.minecraft.entity.player.EntityPlayer playerIn) {
                return true;
            }
        };
        for (Slot s : slots) {
            c.inventorySlots.add(s);
        }
        c.windowId = 7;
        return c;
    }

    private static GuiElement byId(List<GuiElement> els, String id) {
        return els.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
    }

    // ---- tests --------------------------------------------------------------

    /**
     * Proves buttons are extracted with correct id/label/bounds and that the
     * click-point is the geometric center (xPosition+w/2, yPosition+h/2), reading
     * the PROTECTED width/height fields reflectively.
     */
    @Test
    public void buttonsExtractLabelBoundsAndCenterClickPoint() {
        FakeScreen screen = new FakeScreen();
        screen.width = 854;
        screen.height = 480;
        // GuiButton(id, x, y, width, height, text)
        screen.addButton(new GuiButton(100, 10, 20, 200, 20, "Play"));
        screen.addButton(new GuiButton(101, 10, 50, 200, 20, "Quit"));

        GuiReflect.Extraction ex = GuiReflect.extract(screen, false);
        assertTrue("no unreadable fields expected: " + ex.unreadable(),
                ex.unreadable().isEmpty());
        assertEquals(2, ex.elements().size());

        GuiElement b0 = byId(ex.elements(), "b0");
        assertNotNull(b0);
        assertEquals(GuiElement.KIND_BUTTON, b0.kind());
        assertEquals(GuiElement.ROLE_PUSHBUTTON, b0.role());
        assertEquals("Play", b0.name());
        // bounds read from protected width/height
        assertEquals(10, b0.bounds().x());
        assertEquals(20, b0.bounds().y());
        assertEquals(200, b0.bounds().w());
        assertEquals(20, b0.bounds().h());
        // click-point is the center
        assertEquals(10 + 200 / 2, b0.clickPoint().x());
        assertEquals(20 + 20 / 2, b0.clickPoint().y());
        assertEquals(100, b0.attributes().get("buttonId"));
        assertTrue(b0.actions().contains("click"));
        assertTrue(b0.state().enabled());
        assertTrue(b0.state().visible());

        GuiElement b1 = byId(ex.elements(), "b1");
        assertEquals("Quit", b1.name());
        assertEquals(101, b1.attributes().get("buttonId"));
    }

    /**
     * Proves the {@code onlyInteractable} filter drops invisible/disabled buttons
     * while keeping the enabled+visible ones.
     */
    @Test
    public void onlyInteractableFiltersHiddenAndDisabledButtons() {
        FakeScreen screen = new FakeScreen();
        GuiButton visible = new GuiButton(1, 0, 0, 100, 20, "OK");
        GuiButton hidden = new GuiButton(2, 0, 30, 100, 20, "Hidden");
        hidden.visible = false;
        GuiButton disabled = new GuiButton(3, 0, 60, 100, 20, "Disabled");
        disabled.enabled = false;
        screen.addButton(visible);
        screen.addButton(hidden);
        screen.addButton(disabled);

        assertEquals(3, GuiReflect.extract(screen, false).elements().size());
        List<GuiElement> shown = GuiReflect.extract(screen, true).elements();
        assertEquals(1, shown.size());
        assertEquals("OK", shown.get(0).name());
    }

    /**
     * Proves a private declared GuiTextField is discovered by the hierarchy scan
     * and its text/bounds are read, including the PRIVATE final width/height.
     */
    @Test
    public void textFieldDiscoveredWithTextAndPrivateBounds() {
        FakeTextScreen screen = new FakeTextScreen();
        // set text through the real API so getText() returns it
        setText(screen);

        GuiReflect.Extraction ex = GuiReflect.extract(screen, false);
        assertTrue("unreadable: " + ex.unreadable(), ex.unreadable().isEmpty());

        GuiElement t0 = byId(ex.elements(), "t0");
        assertNotNull("text field should be discovered", t0);
        assertEquals(GuiElement.KIND_TEXTFIELD, t0.kind());
        assertEquals(GuiElement.ROLE_EDIT, t0.role());
        assertEquals("hello", t0.value());
        // bounds: x=40,y=60 public ; w=120,h=20 private final read reflectively
        assertEquals(40, t0.bounds().x());
        assertEquals(60, t0.bounds().y());
        assertEquals(120, t0.bounds().w());
        assertEquals(20, t0.bounds().h());
        assertEquals(40 + 120 / 2, t0.clickPoint().x());
        assertEquals(60 + 20 / 2, t0.clickPoint().y());
        assertTrue(t0.actions().contains("setText"));
    }

    private static void setText(FakeTextScreen screen) {
        try {
            java.lang.reflect.Field f = FakeTextScreen.class.getDeclaredField("search");
            f.setAccessible(true);
            GuiTextField tf = (GuiTextField) f.get(screen);
            tf.setText("hello");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Proves container slots are extracted: slot ids are slotNumber-based, the
     * click-point is anchored to guiLeft/guiTop + display position + 8 (slot
     * center), windowId propagates, and an EMPTY slot ({@code getStack()==null},
     * the 1.8.9 behavior) reports hasStack=false with no item attributes.
     */
    @Test
    public void slotsExtractWithGuiOffsetAnchoredClickPointAndEmptyStack() {
        // empty 2-slot inventory: getStackInSlot -> null for every index
        InventoryBasic inv = new InventoryBasic("chest", false, 2);
        Slot slot0 = new Slot(inv, 0, 5, 7);
        Slot slot1 = new Slot(inv, 1, 25, 7);
        // slotNumber is set by addSlotToContainer in real code; set it directly here.
        slot0.slotNumber = 0;
        slot1.slotNumber = 1;
        Container container = containerWith(slot0, slot1);
        FakeContainer screen = new FakeContainer(container);
        screen.width = 854;
        screen.height = 480;
        screen.setGui(100, 40);

        GuiReflect.Extraction ex = GuiReflect.extract(screen, false);
        assertTrue("unreadable: " + ex.unreadable(), ex.unreadable().isEmpty());
        // two slots, no buttons
        assertEquals(2, ex.elements().stream()
                .filter(e -> e.kind().equals(GuiElement.KIND_SLOT)).count());

        GuiElement s0 = byId(ex.elements(), "s0");
        assertNotNull(s0);
        assertEquals(GuiElement.KIND_SLOT, s0.kind());
        assertEquals(GuiElement.ROLE_CELL, s0.role());
        // bounds anchored at guiLeft+xDisplay, guiTop+yDisplay, 16x16
        assertEquals(100 + 5, s0.bounds().x());
        assertEquals(40 + 7, s0.bounds().y());
        assertEquals(16, s0.bounds().w());
        assertEquals(16, s0.bounds().h());
        // click-point is slot center (+8)
        assertEquals(100 + 5 + 8, s0.clickPoint().x());
        assertEquals(40 + 7 + 8, s0.clickPoint().y());
        // attributes
        assertEquals(0, s0.attributes().get("slotNumber"));
        assertEquals(7, s0.attributes().get("windowId"));
        assertEquals(Boolean.FALSE, s0.attributes().get("hasStack"));
        // empty slot carries no item id / count
        assertNull(s0.attributes().get("itemId"));
        assertNull(s0.attributes().get("count"));

        GuiElement s1 = byId(ex.elements(), "s1");
        assertEquals(100 + 25 + 8, s1.clickPoint().x());
    }

    /**
     * Proves the full snapshot builder wires screen name, container flag,
     * viewport, elements and fingerprint together, and that {@code toMap}/{@code
     * toJson} produce a non-vacuous serialized view.
     */
    @Test
    public void buildSnapshotAssemblesModelAndJson() {
        FakeScreen screen = new FakeScreen();
        screen.width = 320;
        screen.height = 240;
        screen.addButton(new GuiButton(1, 0, 0, 100, 20, "Go"));

        GuiSnapshotService svc = new GuiSnapshotService();
        Viewport vp = GuiSnapshotService.viewport(320, 240, 2, 640, 480);
        GuiSnapshot snap = svc.buildSnapshot(screen, false, vp, false);

        assertEquals("FakeScreen", snap.screen());
        assertFalse(snap.isContainer());
        assertEquals(1, snap.elements().size());
        assertEquals(1L, snap.countKind(GuiElement.KIND_BUTTON));
        assertEquals(320, snap.viewport().width());
        assertEquals(2, snap.viewport().scaleFactor());
        // fingerprint = simpleName#buttons#slots
        assertEquals("FakeScreen#1#0", snap.fingerprint());
        assertTrue(snap.unreadable().isEmpty());

        String json = snap.toJson();
        assertTrue(json.contains("\"screen\""));
        assertTrue(json.contains("FakeScreen"));
        assertTrue(json.contains("\"elements\""));
        assertTrue(json.contains("\"clickPoint\""));
        assertTrue(json.contains("Go"));
    }

    /**
     * Proves the epoch is bumped only when the open-screen IDENTITY changes, and
     * that a null screen is its own distinct identity.
     */
    @Test
    public void epochBumpsOnScreenIdentityChangeOnly() {
        GuiSnapshotService svc = new GuiSnapshotService();
        FakeScreen a = new FakeScreen();
        FakeScreen b = new FakeScreen();
        Viewport vp = GuiSnapshotService.viewport(320, 240, 1, 320, 240);

        int e1 = svc.buildSnapshot(a, false, vp, false).epoch();
        // same identity -> no bump
        int e2 = svc.buildSnapshot(a, false, vp, false).epoch();
        assertEquals(e1, e2);
        // new identity -> bump
        int e3 = svc.buildSnapshot(b, false, vp, false).epoch();
        assertTrue(e3 > e2);
        // null (no screen) is a distinct identity -> bump again
        int e4 = svc.buildSnapshot(null, false, vp, false).epoch();
        assertTrue(e4 > e3);
    }

    /**
     * Proves the stale-epoch guard: validateAgainst returns true only when BOTH
     * the epoch and the structural fingerprint match, and false once the screen's
     * structure changes (a button added) under a captured (epoch, fingerprint).
     */
    @Test
    public void validateGuardsAgainstStaleEpochAndStructuralDrift() {
        GuiSnapshotService svc = new GuiSnapshotService();
        FakeScreen screen = new FakeScreen();
        screen.addButton(new GuiButton(1, 0, 0, 100, 20, "A"));
        Viewport vp = GuiSnapshotService.viewport(320, 240, 1, 320, 240);

        GuiSnapshot snap = svc.buildSnapshot(screen, false, vp, false);
        int epoch = snap.epoch();
        String fp = snap.fingerprint();

        // same screen, same structure -> valid
        assertTrue(svc.validateAgainst(screen, epoch, fp));

        // structure drifts (button count changes) -> fingerprint mismatch
        screen.addButton(new GuiButton(2, 0, 30, 100, 20, "B"));
        assertFalse(svc.validateAgainst(screen, epoch, fp));

        // fingerprint now reflects the new structure; with the CURRENT epoch it
        // validates again (same screen identity kept the epoch stable)...
        String fpNow = svc.fingerprint(screen);
        assertTrue(svc.validateAgainst(screen, epoch, fpNow));
        // ...but a stale/wrong epoch is rejected even with a matching fingerprint.
        assertFalse(svc.validateAgainst(screen, epoch + 999, fpNow));
    }

    /**
     * Proves labels are skipped in interactable mode but emitted (as non-enabled
     * TEXT role elements) otherwise — exercising the obfuscated GuiLabel field
     * reads without any unreadable-field drift.
     */
    @Test
    public void labelsEmittedOnlyWhenNotInteractableOnly() {
        FakeScreen screen = new FakeScreen();
        // GuiLabel(fontRenderer, id, x, y, width, height, colour)
        net.minecraft.client.gui.GuiLabel label =
                new net.minecraft.client.gui.GuiLabel(null, 5, 12, 34, 80, 16, 0xFFFFFF);
        screen.addLabel(label);

        // interactable-only: labels dropped
        GuiReflect.Extraction interactable = GuiReflect.extract(screen, true);
        assertEquals(0, interactable.elements().stream()
                .filter(e -> e.kind().equals(GuiElement.KIND_LABEL)).count());

        // full: label present with read bounds, no drift
        GuiReflect.Extraction full = GuiReflect.extract(screen, false);
        assertTrue("unreadable: " + full.unreadable(), full.unreadable().isEmpty());
        GuiElement l0 = byId(full.elements(), "l0");
        assertNotNull(l0);
        assertEquals(GuiElement.KIND_LABEL, l0.kind());
        assertEquals(GuiElement.ROLE_TEXT, l0.role());
        assertEquals(12, l0.bounds().x());
        assertEquals(34, l0.bounds().y());
        assertEquals(80, l0.bounds().w());
        assertEquals(16, l0.bounds().h());
        assertFalse(l0.state().enabled());
    }
}
