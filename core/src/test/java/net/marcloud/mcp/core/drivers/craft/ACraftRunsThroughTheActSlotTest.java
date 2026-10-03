package net.marcloud.mcp.core.drivers.craft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.drivers.act.ActPhase;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.drivers.act.ActSlot;
import net.marcloud.mcp.core.drivers.act.ActTickLoop;
import net.marcloud.mcp.core.drivers.act.FakeActuator;
import net.marcloud.mcp.core.drivers.act.InteractApplier;
import net.marcloud.mcp.core.drivers.act.InteractIntent;
import net.marcloud.mcp.core.drivers.act.SlotRecord;

import org.junit.Test;

/**
 * The craft has to be REACHABLE, not merely present.
 *
 * <p><b>The defect this file exists for, in the project's own words:</b> the DROP verb was built,
 * driven and proved green in the eval harness, and was still unreachable by a model -- the schema
 * published {@code interact.kind} as an enum without {@code drop}, so the boundary refused the call
 * while the parser and the controller sat there green. {@code ADropIsReachableThroughTheRealToolBoundaryTest}
 * was written for that; this is its counterpart for craft, and it closes a gap of exactly the same
 * kind. The craft controller had 27 test construction sites and ZERO producers in the main tree,
 * which is the eighth entry in {@code failure-shapes.md} wearing a different hat.
 *
 * <p><b>What is asserted here and why the layers are chained.</b> Each half was green while the
 * other was broken before, so the assertion that catches the real defect is the one that does not
 * stop in between: the INTERACT slot is handed a CRAFT intent, the applier binds it, and the
 * craft reaches a real output in a real inventory. A test that only checked the enum would have
 * passed against a controller nothing drives; one that only checked the controller would have
 * passed against a verb nothing can submit.
 *
 * <p><b>Non-vacuity.</b> The window's slots MUTATE on click and the result slot is computed by the
 * REAL vanilla recipe table, so "the output is in the bag" cannot be produced by a controller that
 * clicked nothing. The negative controls are the other half of that: a refusal names what is
 * missing rather than reporting a craft that never happened.
 */
public final class ACraftRunsThroughTheActSlotTest {

    private static final String ITEM = "stick";

    /**
     * Drive the runtime through the REAL {@link ActTickLoop} until INTERACT goes terminal.
     *
     * <p>Through the loop and not by calling the applier directly, because the loop is what STORES
     * the applier's result: a hand-rolled loop that ticked the applier and threw the returned
     * record away would leave every slot at IDLE and report a craft that never started as one that
     * never finished. It also owns the {@code effectiveTick} gate and the plan sequencer, so this is
     * the same path a live client takes.
     */
    private static SlotRecord drive(ActRuntime runtime, FakeCraftWindow win, int maxTicks) {
        ActTickLoop loop = new ActTickLoop(runtime);
        for (int i = 1; i <= maxTicks; i++) {
            loop.onTick(new net.marcloud.mcp.core.ke.event.events.TickEvent(i));
            win.advanceTick();
            SlotRecord rec = runtime.record(ActSlot.INTERACT);
            if (rec.phase().isTerminal()) {
                return rec;
            }
        }
        throw new AssertionError("the craft never reached a terminal phase in " + maxTicks
                + " ticks; phase=" + runtime.record(ActSlot.INTERACT).phase()
                + " message=" + runtime.record(ActSlot.INTERACT).message());
    }

    /**
     * The whole path: an applier over a real window, a CRAFT intent in the slot, and a stick that
     * was not in the bag before the call.
     *
     * <p>The stick recipe is two planks stacked, which is the smallest recipe that still needs a
     * pick-up, a place, a settle, a take and a confirm -- so this exercises every state in the
     * machine rather than the single-click path.
     */
    @Test
    public void aCraftIntentInTheSlotProducesTheItemInTheInventory() {
        FakeActuator act = new FakeActuator();
        FakeCraftWindow win = FakeCraftWindow.bench();
        win.carrying(0, "planks", 0, 2);

        ActRuntime runtime = new ActRuntime();
        runtime.registerApplier(ActSlot.INTERACT, new InteractApplier(act, win));

        assertTrue("the fixture must be craftable before the test proves anything",
                CraftWire.bind(ITEM, win).craftable());
        assertEquals("the fixture must NOT already contain the output, or the assertion below "
                + "would pass without a craft", 0, countOf(storedOf(win), ITEM));

        runtime.submitInteract(InteractIntent.craftItem(ITEM));

        SlotRecord done = drive(runtime, win, 60);

        assertEquals("a craft that ran to completion must report COMPLETE: " + done.message(),
                ActPhase.COMPLETE, done.phase());
        // 4, not 2: vanilla's stick recipe is one plank -> four sticks, and the assertion is
        // deliberately the recipe's OWN outputCount rather than a number picked to look right.
        assertEquals("the stick must be in the bag afterwards, or nothing was crafted",
                4, countOf(storedOf(win), ITEM));
        assertEquals("and the planks must have been SPENT, or the ingredients were never placed",
                0, countOf(storedOf(win), "planks"));
    }

    /**
     * The negative control that makes the positive one mean something.
     *
     * <p>Being short an ingredient is the single most common answer this path produces, and it must
     * be a TERMINAL refusal that NAMES the shortfall -- never a slot that sits ACTIVE reporting
     * progress while doing nothing, which is the {@code act_set move} fake-success this repository
     * rates as its highest historical risk.
     */
    @Test
    public void aCraftThePlayerCannotPayForIsRefusedByNameAndTerminates() {
        FakeActuator act = new FakeActuator();
        FakeCraftWindow win = FakeCraftWindow.bench();
        // Deliberately empty: a bench with nothing in the bag.

        ActRuntime runtime = new ActRuntime();
        runtime.registerApplier(ActSlot.INTERACT, new InteractApplier(act, win));
        runtime.submitInteract(InteractIntent.craftItem(ITEM));

        SlotRecord out = drive(runtime, win, 30);

        assertEquals("an unpayable craft must FAIL, not sit ACTIVE: " + out.message(),
                ActPhase.FAILED, out.phase());
        assertTrue("the refusal must name what is short, got: " + out.message(),
                out.message().contains("planks"));
    }

    /**
     * No recipe is a different answer from "you are short", and it is the answer that sends a model
     * looking for a route it can actually take.
     */
    @Test
    public void anItemWithNoRecipeIsRefusedWithoutClaimingThePlayerIsShort() {
        FakeCraftWindow win = FakeCraftWindow.bench();
        win.carrying(0, "planks", 0, 8);

        CraftWire.Bound bound = CraftWire.bind("not_a_real_item", win);

        assertFalse("an item with no recipe must not bind a controller", bound.craftable());
        assertNull(bound.controller());
        assertTrue("the refusal must say there is no recipe: " + bound.message(),
                bound.message().contains("no recipe"));
    }

    /**
     * A refusal is TERMINAL, on purpose.
     *
     * <p>"You are short planks" does not become true by waiting. A non-terminal refusal would hold
     * the INTERACT slot -- the one channel dig, drop and attack also use -- spinning until its
     * lease expired, so a model that was told to gather wood would find the channel busy and every
     * other verb blocked behind a craft that was never going to happen.
     */
    @Test
    public void aRefusedCraftIsTerminal() {
        FakeCraftWindow win = FakeCraftWindow.bench();
        // No ingredients at all, so this binds to a refusal rather than a controller.
        CraftWire.Bound bound = CraftWire.bind(ITEM, win);
        assertFalse("the fixture must actually refuse for this to be about the refusal path",
                bound.craftable());
        assertTrue("a refusal must be TERMINAL. It is not something waiting can fix, and a "
                        + "non-terminal one would hold the single INTERACT channel -- the channel "
                        + "dig, drop and attack also use -- reporting progress forever",
                bound.terminal());
    }

    /**
     * Cancelling a running craft must give the ingredients back.
     *
     * <p>This is the assertion that earns the CRAST branch in
     * {@code InteractApplier.cancelLiveController}. Vanilla DROPS a crafting grid's contents when
     * the window closes ({@code ContainerPlayer:83-98}, {@code ContainerWorkbench:62-78}) and
     * {@code world_view} reads {@code mainInventory} only, so a stranded ingredient is invisible to
     * every observation tool the model has: it would learn about the loss only as items missing
     * from its pockets. Dropping the controller reference on cancel would strand them.
     */
    @Test
    public void cancellingARunningCraftReturnsTheGridToTheBag() {
        FakeActuator act = new FakeActuator();
        FakeCraftWindow win = FakeCraftWindow.bench();
        win.carrying(0, "planks", 0, 4);

        ActRuntime runtime = new ActRuntime();
        runtime.registerApplier(ActSlot.INTERACT, new InteractApplier(act, win));
        runtime.submitInteract(InteractIntent.craftItem(ITEM));

        // Two ticks through the real loop. The FIRST ends in CHECKING, which is where the grid
        // width and the bill are verified and no click has happened yet; the second is PLACING.
        // Cancelling between them would prove nothing about the sweep.
        ActTickLoop loop = new ActTickLoop(runtime);
        loop.onTick(new net.marcloud.mcp.core.ke.event.events.TickEvent(1));
        loop.onTick(new net.marcloud.mcp.core.ke.event.events.TickEvent(2));
        assertTrue("the craft must have started before a cancel means anything; phase="
                        + runtime.record(ActSlot.INTERACT).phase()
                        + " message=" + runtime.record(ActSlot.INTERACT).message(),
                heldInGrid(win) > 0);

        runtime.cancel(ActSlot.INTERACT);
        SlotRecord out = drive(runtime, win, 20);

        assertEquals("a cancelled craft must report CANCELLED, not FAILED or COMPLETE: "
                + out.message(), ActPhase.CANCELLED, out.phase());
        assertEquals("every ingredient must be back in the bag; a stranded one is dropped by "
                + "vanilla when the window closes and no observation tool can see it",
                0, heldInGrid(win));
        assertEquals("and nothing may be fabricated on the way out: " + out.message(),
                0, countOf(storedOf(win), ITEM));
    }

    /**
     * An applier built with no crafting window must refuse rather than guess.
     *
     * <p>The one-argument constructor is what every existing headless test uses, so the craft path
     * has to be honest there instead of throwing or silently doing nothing.
     */
    @Test
    public void anApplierWithNoCraftWindowRefusesTheVerbByName() {
        FakeActuator act = new FakeActuator();
        ActRuntime runtime = new ActRuntime();
        runtime.registerApplier(ActSlot.INTERACT, new InteractApplier(act));
        runtime.submitInteract(InteractIntent.craftItem(ITEM));

        SlotRecord out = drive(runtime, winless(), 10);

        assertEquals(ActPhase.FAILED, out.phase());
        assertTrue("the refusal must say no crafting window is available, got: " + out.message(),
                out.message().contains("no crafting window"));
    }

    /** A window that is open but is not a crafting grid -- a chest. */
    private static FakeCraftWindow winless() {
        return FakeCraftWindow.playerWindow();
    }

    /** How many ingredient stacks the craft currently has sitting in the grid. */
    private static int heldInGrid(FakeCraftWindow win) {
        int n = 0;
        for (int row = 0; row < win.gridWidth(); row++) {
            for (int col = 0; col < win.gridWidth(); col++) {
                if (win.stackAt(win.matrixSlot(row, col)) != null) {
                    n++;
                }
            }
        }
        return n;
    }

    private static CraftInventory storedOf(CraftWindow win) {
        java.util.List<CraftInventory.Held> held = new java.util.ArrayList<>();
        for (int slot : win.storageSlots()) {
            CraftInventory.Held there = win.stackAt(slot);
            if (there != null) {
                held.add(there);
            }
        }
        return new CraftInventory(held);
    }

    private static int countOf(CraftInventory inv, String item) {
        int n = 0;
        for (CraftInventory.Held h : inv.items()) {
            if (h.item() != null && h.item().equals(item)) {
                n += h.count();
            }
        }
        return n;
    }
}