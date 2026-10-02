package net.marcloud.mcp.core.drivers.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import org.junit.Test;

/**
 * Headless tests for the CLICK side: does clicking element N actually fire element
 * N, does the reference survive the change it is supposed to survive, and does the
 * verdict ever claim more than was observed.
 *
 * <p>The screens here are synthetic because vanilla's {@code GuiScreen.mouseClicked}
 * and {@code GuiContainer.mouseClicked} both dereference {@code this.mc} — the real
 * ones are exercised by {@code GuiClickLiveIT}. What is NOT synthetic is the thing
 * under test: the click point is computed by {@link GuiReflect} from the real
 * {@link GuiButton}/{@link Slot} geometry, and the fakes re-hit-test the coordinates
 * they are handed against that same geometry, so a wrong point or a wrong id
 * resolves to a different element and fails here.
 */
public class GuiClickVerdictTest {

    /** The item registry Items resolves against; without it every ItemStack is empty. */
    @org.junit.BeforeClass
    public static void bootstrapRegistry() {
        net.minecraft.init.Bootstrap.register();
    }

    /**
     * A screen whose click handler does the same hit test vanilla does — bounds, not
     * a hardcoded id — so "clicking b1 fired button 101" is a statement about the
     * coordinates this codebase produced, not about the fake's cooperation.
     */
    private static class ButtonScreen extends GuiScreen {
        int firedButtonId = -1;
        int lastX;
        int lastY;
        int lastButton;
        int clicks;
        /** What the screen does when a button is hit; defaults to nothing. */
        java.util.function.Consumer<GuiButton> onAction = b -> {
        };

        void add(GuiButton b) {
            buttonList.add(b);
        }

        private void actionPerformed0(GuiButton b) {
            onAction.accept(b);
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
            clicks++;
            lastX = mouseX;
            lastY = mouseY;
            lastButton = mouseButton;
            // Hit test every control, then act: acting inside the loop would mutate
            // buttonList while iterating it.
            GuiButton hit = null;
            for (GuiButton b : buttonList) {
                if (mouseX >= b.xPosition && mouseY >= b.yPosition
                        && mouseX < b.xPosition + widthOf(b) && mouseY < b.yPosition + heightOf(b)) {
                    hit = b;
                }
            }
            if (hit != null) {
                firedButtonId = hit.id;
                actionPerformed0(hit);
            }
        }
    }

    /**
     * A container screen whose click handler moves the clicked slot's stack onto the
     * cursor, which is what vanilla's {@code Container.slotClick} does client-side
     * before the server answers.
     */
    private static final class BoxScreen extends GuiContainer {
        ItemStack cursor;

        void add(GuiButton b) {
            buttonList.add(b);
        }

        int lastSlot = -1;
        int clicks;

        BoxScreen(Container c) {
            super(c);
        }

        @Override
        protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        }

        void place(int left, int top) {
            this.guiLeft = left;
            this.guiTop = top;
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
            clicks++;
            for (Slot s : inventorySlots.inventorySlots) {
                int x = guiLeft + s.xDisplayPosition;
                int y = guiTop + s.yDisplayPosition;
                if (mouseX >= x && mouseY >= y && mouseX < x + 16 && mouseY < y + 16) {
                    lastSlot = s.slotNumber;
                    if (mouseButton == 0) {
                        if (cursor == null) {
                            cursor = s.getStack();
                            s.putStack(null);
                        } else {
                            s.putStack(cursor);
                            cursor = null;
                        }
                    }
                }
            }
        }
    }

    private static int widthOf(GuiButton b) {
        return intField(b, "width");
    }

    private static int heightOf(GuiButton b) {
        return intField(b, "height");
    }

    private static int intField(Object target, String name) {
        try {
            java.lang.reflect.Field f = GuiButton.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static BoxScreen boxScreen(InventoryBasic inv, int slotCount) {
        Container c = new Container() {
            @Override
            public boolean canInteractWith(EntityPlayer playerIn) {
                return true;
            }
        };
        for (int i = 0; i < slotCount; i++) {
            Slot s = new Slot(inv, i, 8 + i * 18, 18);
            s.slotNumber = i;
            c.inventorySlots.add(s);
        }
        c.windowId = 7;
        BoxScreen screen = new BoxScreen(c);
        screen.width = 427;
        screen.height = 240;
        screen.place(100, 60);
        return screen;
    }

    // ---- tests --------------------------------------------------------------

    /**
     * Clicking button N fires THAT button, at its live centre, with the button the
     * caller asked for. The handler re-hit-tests the coordinates against the real
     * button geometry, so an off-by-one in the click point lands on a different
     * button (or none) and fails.
     */
    @Test
    public void clickingButtonNFiresThatButton() {
        ButtonScreen screen = new ButtonScreen();
        screen.width = 854;
        screen.height = 480;
        screen.add(new GuiButton(100, 10, 20, 200, 20, "Play"));
        screen.add(new GuiButton(101, 10, 50, 200, 20, "Quit"));
        screen.add(new GuiButton(102, 10, 80, 200, 20, "Options"));

        GuiSnapshotService svc = new GuiSnapshotService();
        GuiActions actions = new GuiActions(null, svc, null);
        GuiSnapshot snap = svc.buildSnapshot(screen, false,
                GuiSnapshotService.viewport(854, 480, 1, 854, 480), false);

        // b0 is the button at y=20, b1 at y=50, b2 at y=80; each is 200x20.
        for (String[] want : new String[][] {
                {"b0", "100", "20"}, {"b1", "101", "50"}, {"b2", "102", "80"}}) {
            screen.firedButtonId = -1;
            GuiActions.ClickResult r = actions.clickOnScreen(screen, snap.epoch(), snap.fingerprint(),
                    want[0], 0);
            assertTrue(want[0] + ": " + r.message(), r.ok());
            assertEquals(want[0] + " must fire the button it addresses",
                    Integer.parseInt(want[1]), screen.firedButtonId);
            // the delivered point is that button's live centre, not a guess
            assertEquals(10 + 200 / 2, screen.lastX);
            assertEquals(Integer.parseInt(want[2]) + 20 / 2, screen.lastY);
        }
        assertEquals("every button was clicked exactly once", 3, screen.clicks);
    }

    /**
     * The right button reaches the screen as button 1, so a right-click is not
     * silently a left-click.
     */
    @Test
    public void rightClickReachesTheScreenAsButtonOne() {
        ButtonScreen screen = new ButtonScreen();
        screen.add(new GuiButton(1, 0, 0, 100, 20, "Split"));
        GuiSnapshotService svc = new GuiSnapshotService();
        GuiActions actions = new GuiActions(null, svc, null);
        GuiSnapshot snap = svc.buildSnapshot(screen, false,
                GuiSnapshotService.viewport(320, 240, 1, 320, 240), false);

        assertTrue(actions.clickOnScreen(screen, snap.epoch(), snap.fingerprint(), "b0", 1).ok());
        assertEquals(1, screen.lastButton);
    }

    /**
     * Clicking slot N moves the item that WAS in slot N, and the verdict says so
     * because the slot reads differently afterwards. This is the property that a
     * "the click was sent" report would fake: the re-read is the only evidence.
     */
    @Test
    public void clickingSlotNMovesTheItemThatWasInSlotN() {
        InventoryBasic inv = new InventoryBasic("Loot Chest", true, 3);
        inv.setInventorySlotContents(0, new ItemStack(Items.diamond, 7));
        inv.setInventorySlotContents(2, new ItemStack(Items.coal, 64));
        BoxScreen screen = boxScreen(inv, 3);

        GuiSnapshotService svc = new GuiSnapshotService();
        GuiActions actions = new GuiActions(null, svc, null);
        GuiSnapshot snap = svc.buildSnapshot(screen, false,
                GuiSnapshotService.viewport(427, 240, 1, 427, 240), false);

        GuiActions.ClickResult r = actions.clickOnScreen(screen, snap.epoch(), snap.fingerprint(),
                "s2", 0);

        assertTrue(r.message(), r.ok());
        assertEquals(2, screen.lastSlot);
        assertNotNull("the item must be on the cursor", screen.cursor);
        assertEquals(Items.coal, screen.cursor.getItem());
        assertEquals(64, screen.cursor.stackSize);
        assertEquals("slot 2 must be empty now", null, inv.getStackInSlot(2));
        assertEquals("slot 0 must be untouched: a click on s2 moves s2",
                Items.diamond, inv.getStackInSlot(0).getItem());
        assertEquals(ClickVerdict.CONFIRMED, r.verdict());
        assertTrue("a confirmed click says how it was confirmed", r.message().contains("re-reading"));
    }

    /**
     * A click on a slot whose contents the server did not change must NOT be reported
     * as done. This is the test that kills "it was sent": the handler ran, the slot
     * reads the same, so the verdict is NOT_CONFIRMED and the message says so.
     */
    @Test
    public void aSlotClickThatChangesNothingIsNotReportedAsDone() {
        InventoryBasic inv = new InventoryBasic("Loot Chest", true, 3);
        inv.setInventorySlotContents(1, new ItemStack(Items.gold_ingot, 2));
        BoxScreen screen = boxScreen(inv, 3);

        GuiSnapshotService svc = new GuiSnapshotService();
        GuiActions actions = new GuiActions(null, svc, null);
        GuiSnapshot snap = svc.buildSnapshot(screen, false,
                GuiSnapshotService.viewport(427, 240, 1, 427, 240), false);

        // A right-click on the fake screen does not move anything, so nothing changes.
        GuiActions.ClickResult r = actions.clickOnScreen(screen, snap.epoch(), snap.fingerprint(),
                "s1", 1);

        assertEquals("a click that changed nothing must not claim CONFIRMED",
                ClickVerdict.NOT_CONFIRMED, r.verdict());
        assertFalse("nothing was proven, so the tool must not call it ok", r.ok());
        assertTrue("the message must say what is not known: " + r.message(),
                r.message().contains("[UNVERIFIED]"));
    }

    /**
     * A button click whose screen looks the same afterwards is driven but unproven.
     * Reported as NOT_CONFIRMED with ok=true, because for a button "nothing
     * observable" is normal and NOT evidence of failure.
     */
    @Test
    public void aButtonClickThatChangesTheScreenIsConfirmed() {
        ButtonScreen screen = new ButtonScreen();
        // A real button usually opens another screen; emulate only the observable
        // part -- a control appearing -- so the fingerprint really moves.
        screen.onAction = b -> screen.add(new GuiButton(200, 0, 200, 100, 20, "Opened"));
        screen.add(new GuiButton(1, 0, 0, 100, 20, "Go"));
        GuiSnapshotService svc = new GuiSnapshotService();
        GuiActions actions = new GuiActions(null, svc, null);
        GuiSnapshot snap = svc.buildSnapshot(screen, false,
                GuiSnapshotService.viewport(320, 240, 1, 320, 240), false);

        GuiActions.ClickResult r = actions.clickOnScreen(screen, snap.epoch(), snap.fingerprint(),
                "b0", 0);
        assertEquals(ClickVerdict.CONFIRMED, r.verdict());
        assertTrue(r.ok());
    }

    /**
     * The staleness guard, and the property that makes the surface usable: MOVING AN
     * ITEM is not staleness. A slot's identity is its index, so a reference captured
     * before a pickup still addresses the same slot afterwards — otherwise every
     * inventory operation would cost a fresh snapshot.
     */
    @Test
    public void movingAnItemDoesNotInvalidateTheReference() {
        InventoryBasic inv = new InventoryBasic("Loot Chest", true, 3);
        inv.setInventorySlotContents(0, new ItemStack(Items.diamond, 7));
        BoxScreen screen = boxScreen(inv, 3);
        GuiSnapshotService svc = new GuiSnapshotService();
        GuiActions actions = new GuiActions(null, svc, null);
        GuiSnapshot snap = svc.buildSnapshot(screen, false,
                GuiSnapshotService.viewport(427, 240, 1, 427, 240), false);

        assertTrue(actions.clickOnScreen(screen, snap.epoch(), snap.fingerprint(), "s0", 0).ok());

        // The reference is still live: the second click lands, it is not refused.
        assertTrue("a pickup must not invalidate the snapshot the agent is holding",
                svc.validateAgainst(screen, snap.epoch(), snap.fingerprint()));
        assertTrue(actions.clickOnScreen(screen, snap.epoch(), snap.fingerprint(), "s0", 0).ok());
        assertEquals("the second click put the stack back", 2, screen.clicks);
    }

    /**
     * The other half of the same property: a change to the ADDRESS SPACE — a
     * different control set — IS staleness, and nothing is clicked.
     */
    @Test
    public void aChangedControlSetRefusesTheClickAndClicksNothing() {
        ButtonScreen screen = new ButtonScreen();
        screen.add(new GuiButton(1, 0, 0, 100, 20, "A"));
        GuiSnapshotService svc = new GuiSnapshotService();
        GuiActions actions = new GuiActions(null, svc, null);
        GuiSnapshot snap = svc.buildSnapshot(screen, false,
                GuiSnapshotService.viewport(320, 240, 1, 320, 240), false);

        screen.add(new GuiButton(2, 0, 30, 100, 20, "B"));

        GuiActions.ClickResult r = actions.clickOnScreen(screen, snap.epoch(), snap.fingerprint(),
                "b0", 0);
        assertEquals(ClickVerdict.REFUSED_STALE, r.verdict());
        assertFalse(r.ok());
        assertEquals("a refused click must not reach the handler", 0, screen.clicks);
    }

    /** A different screen OBJECT is staleness too, even with an identical shape. */
    @Test
    public void aDifferentScreenObjectRefusesTheClick() {
        ButtonScreen a = new ButtonScreen();
        a.add(new GuiButton(1, 0, 0, 100, 20, "A"));
        ButtonScreen b = new ButtonScreen();
        b.add(new GuiButton(1, 0, 0, 100, 20, "A"));
        GuiSnapshotService svc = new GuiSnapshotService();
        GuiActions actions = new GuiActions(null, svc, null);
        GuiSnapshot snap = svc.buildSnapshot(a, false,
                GuiSnapshotService.viewport(320, 240, 1, 320, 240), false);

        GuiActions.ClickResult r = actions.clickOnScreen(b, snap.epoch(), snap.fingerprint(),
                "b0", 0);
        assertEquals(ClickVerdict.REFUSED_STALE, r.verdict());
        assertEquals(0, b.clicks);
    }

    /** An id that resolves to nothing is reported as such, and clicks nothing. */
    @Test
    public void anUnknownElementIdClicksNothing() {
        ButtonScreen screen = new ButtonScreen();
        screen.add(new GuiButton(1, 0, 0, 100, 20, "A"));
        GuiSnapshotService svc = new GuiSnapshotService();
        GuiActions actions = new GuiActions(null, svc, null);
        GuiSnapshot snap = svc.buildSnapshot(screen, false,
                GuiSnapshotService.viewport(320, 240, 1, 320, 240), false);

        GuiActions.ClickResult r = actions.clickOnScreen(screen, snap.epoch(), snap.fingerprint(),
                "s7", 0);
        assertEquals(ClickVerdict.NO_ELEMENT, r.verdict());
        assertFalse(r.ok());
        assertEquals(0, screen.clicks);
    }

    /**
     * The destructive rule, on a real container panel: an element whose click point
     * lands outside the panel is refused, because vanilla rewrites that to
     * {@code slotId = -999} and throws the carried stack into the world. A button
     * parked outside the panel is exactly that case, and vanilla's own hit test
     * (isPointInRegion) is what decides it.
     */
    @Test
    public void anElementOutsideThePanelIsRefusedAsDestructive() {
        InventoryBasic inv = new InventoryBasic("Loot Chest", true, 2);
        BoxScreen screen = boxScreen(inv, 2);
        // guiLeft=100, guiTop=60, xSize=176, ySize=166 by GuiContainer's defaults, so
        // y=400 is outside the panel while still being a perfectly valid element rect.
        screen.add(new GuiButton(1, 100, 400, 100, 20, "Far"));
        GuiSnapshotService svc = new GuiSnapshotService();
        GuiActions actions = new GuiActions(null, svc, null);
        GuiSnapshot snap = svc.buildSnapshot(screen, false,
                GuiSnapshotService.viewport(427, 480, 1, 427, 480), false);

        assertTrue("the guard must agree with vanilla's own panel hit test",
                GuiActions.dropsCarriedStack(screen, new Point(150, 410)));
        assertFalse("a slot inside the panel is not a drop",
                GuiActions.dropsCarriedStack(screen, new Point(116, 86)));

        GuiActions.ClickResult r = actions.clickOnScreen(screen, snap.epoch(), snap.fingerprint(),
                "b0", 0);
        assertEquals(ClickVerdict.REFUSED_DESTRUCTIVE, r.verdict());
        assertFalse(r.ok());
        assertEquals("a refused click must not reach the handler", 0, screen.clicks);
    }

    /**
     * The confirmation WAIT, which is where "it was sent" would hide. Three probes,
     * three honest answers: a slot that changes is CONFIRMED however late it changes;
     * a slot that never changes is NOT_CONFIRMED once the budget passes; a slot that
     * cannot be read is UNREADABLE. There is no fourth answer.
     */
    @Test
    public void theConfirmationWaitOnlyReportsWhatAReadBackShows() {
        GuiStack before = new GuiStack("minecraft:diamond", "Diamond", 7, 0, 64,
                List.of(), List.of(), "");
        GuiStack after = new GuiStack("minecraft:diamond", "Diamond", 6, 0, 64,
                List.of(), List.of(), "");
        AtomicInteger reads = new AtomicInteger();
        GuiActions.Sleeper noSleep = m -> {
        };

        // Changes on the third read: the wait must keep looking, not give up early.
        assertEquals(ClickVerdict.CONFIRMED, GuiActions.awaitChange(before, () ->
                reads.incrementAndGet() >= 3 ? after : before, noSleep, 50));
        assertEquals(3, reads.get());

        // Never changes: NOT_CONFIRMED, never CONFIRMED.
        assertEquals(ClickVerdict.NOT_CONFIRMED,
                GuiActions.awaitChange(before, () -> before, noSleep, 30));

        // Cannot be read: UNREADABLE, never CONFIRMED.
        assertEquals(ClickVerdict.UNREADABLE, GuiActions.awaitChange(before, () -> {
            throw new IllegalStateException("container gone");
        }, noSleep, 50));
    }

    /**
     * The wait itself is a floor on honesty, not a shortcut: with a zero budget it
     * still reads once, so a click that changed nothing is never reported as proven.
     */
    @Test
    public void aZeroBudgetStillRefusesToCallAnUnchangedSlotProven() {
        GuiStack before = new GuiStack("minecraft:iron_ingot", "Stone", 1, 0, 64,
                List.of(), List.of(), "");
        assertEquals(ClickVerdict.NOT_CONFIRMED,
                GuiActions.awaitChange(before, () -> before, m -> {
                }, 0));
    }

    /**
     * The verdict enum has no "sent" value, which is the whole rule: if one is ever
     * added, this fails, because an unobserved outcome must have no name to be
     * reported under.
     */
    @Test
    public void theVerdictEnumHasNoSentValue() {
        List<String> names = new java.util.ArrayList<>();
        for (ClickVerdict v : ClickVerdict.values()) {
            names.add(v.name());
        }
        assertFalse("an unobserved outcome must have no name: " + names,
                names.contains("SENT"));
        assertEquals(6, names.size());
    }

    /**
     * A stale reference is caught even when the fingerprint is right, and vice versa
     * — the pair is the reference, and either half alone is not enough.
     */
    @Test
    public void theEpochAndTheFingerprintAreBothNeeded() {
        ButtonScreen screen = new ButtonScreen();
        screen.add(new GuiButton(1, 0, 0, 100, 20, "A"));
        GuiSnapshotService svc = new GuiSnapshotService();
        GuiActions actions = new GuiActions(null, svc, null);
        GuiSnapshot snap = svc.buildSnapshot(screen, false,
                GuiSnapshotService.viewport(320, 240, 1, 320, 240), false);

        assertTrue(svc.validateAgainst(screen, snap.epoch(), snap.fingerprint()));
        assertFalse("a wrong epoch with the right fingerprint is still stale",
                svc.validateAgainst(screen, snap.epoch() + 7, snap.fingerprint()));
        assertFalse("a right epoch with a wrong fingerprint is still stale",
                svc.validateAgainst(screen, snap.epoch(), "FakeScreen#9#9#dead"));
    }
}
