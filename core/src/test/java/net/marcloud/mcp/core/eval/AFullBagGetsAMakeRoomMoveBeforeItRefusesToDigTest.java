package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * The make-room move, on the world T23 is built from, stated as properties rather than as a
 * re-run of the task.
 *
 * <p><b>Why not just T23.</b> T23 asks whether the goal is REACHED, which is the outcome that
 * matters and the reason this work exists. These ask the narrower questions whose answers decide
 * WHAT gets thrown away, because "the goal was reached" is equally compatible with a policy that
 * reached it by throwing away the very planks the pickaxe was made of. Each property below is
 * separately falsifiable, and each has a mutation that falsifies only it.
 *
 * <p>The world is {@link EvalSuite.T21CountBeforeYouSpend#buildWorld()} filled to 36/36 with dirt,
 * which is the controlled comparison T23 makes: same goal, same world, one variable.
 */
public class AFullBagGetsAMakeRoomMoveBeforeItRefusesToDigTest {

    /** The stocked T21/T23 world with every one of the 36 slots holding a full stack of dirt. */
    private static SimWorld fullBagOfDirt() {
        return filledWith("dirt", 64);
    }

    /** The same world, but every slot holds a stack of an item the wooden pickaxe is made of. */
    private static SimWorld fullBagOfPlanks() {
        return filledWith("planks", 4);
    }

    private static SimWorld filledWith(String item, int count) {
        SimWorld w = EvalSuite.T21CountBeforeYouSpend.buildWorld();
        for (int slot = 0; slot < w.inventory().mainInventory.length; slot++) {
            w.give(slot, item, count);
        }
        return w;
    }

    /** How many log cells are still standing, over the outcrop T21 and T23 share. */
    private static int logsStanding(SimWorld w) {
        int n = 0;
        for (int x = -6; x <= 1; x++) {
            for (int z = 2; z <= 8; z++) {
                if ("log".equals(w.blockAt(x, 64, z))) {
                    n++;
                }
            }
        }
        return n;
    }

    private static void assertEveryThrowIsDirt(SimWorld w) {
        for (String thrown : w.thrownByPlayer()) {
            assertTrue("the agent must never throw away something it is about to need, but it"
                    + " threw " + thrown, thrown.startsWith("64x dirt/"));
        }
    }

    /**
     * PROPERTY 1: on a full bag of junk, the policy MAKES ROOM and goes on, rather than
     * refusing.
     */
    @Test
    public void aFullBagOfJunkIsEmptiedByDroppingAndTheGoalIsStillReached() {
        SimWorld w = fullBagOfDirt();
        assertEquals("premise: the bag is full", 0, w.freeSlots());
        int logsBefore = logsStanding(w);

        EvalHarness h = new EvalHarness(w);
        GoalPolicy policy = new GoalPolicy(w, h);
        boolean got = policy.obtain("cobblestone");

        assertTrue("the goal must be reached from a full bag: " + policy.traceSummary(),
                got && w.count("cobblestone") > 0);
        assertFalse("and something must have been thrown to make the room",
                w.thrownByPlayer().isEmpty());
        assertTrue("the logs the chain consumed must have come OUT of the world, which is what"
                + " makes this different from the original failure: "
                + (logsBefore - logsStanding(w)) + " log cell(s) spent",
                logsStanding(w) < logsBefore);
    }

    /**
     * PROPERTY 2: whatever is thrown is junk -- never a stack the chain still needs.
     *
     * <p>This is the assertion that separates "the run reached the goal" from "the run reached the
     * goal without eating its own supply". At its hungriest the chain is holding planks, sticks
     * and logs, and every one of them is an input of a craft that has not run.
     */
    @Test
    public void nothingTheCraftChainStillNeedsIsEverThrownAway() {
        SimWorld w = fullBagOfDirt();
        EvalHarness h = new EvalHarness(w);
        new GoalPolicy(w, h).obtain("cobblestone");

        assertEveryThrowIsDirt(w);
        assertEquals("and the goal was still reached, so the protection cost nothing",
                1, w.count("cobblestone"));
    }

    /**
     * PROPERTY 3: on a bag of nothing but needed stacks, the throw is BOUNDED -- and then the
     * policy refuses rather than emptying the pack.
     *
     * <p>The bag is 36 stacks of {@code planks}, which is what a wooden pickaxe is made of. This
     * is the case the rule has to get right in the other direction from T23, and it is MEASURED
     * rather than asserted from a story: the chain asks for planks AFTER the first make-room,
     * not before, so that first throw is speculative and does happen. What must not happen is the
     * run eating its own supply one stack at a time for the rest of the chain -- and it does not,
     * because from the moment the craft bills are live the rule finds nothing throwable and says
     * so in those words.
     *
     * <p>So the assertions are a SHAPE, not a constant: the goal is out of reach, the stacks
     * thrown are a small fraction of the stacks there were, and every log the world lost has a
     * freed slot behind it -- i.e. the policy dug nothing it had not already made room for, which
     * is the whole of T23's bad failure.
     */
    @Test
    public void aBagOfNothingButNeededStacksIsThrownFromOnlyAsFarAsTheChainCannotSee() {
        SimWorld w = fullBagOfPlanks();
        int logsBefore = logsStanding(w);

        EvalHarness h = new EvalHarness(w);
        GoalPolicy policy = new GoalPolicy(w, h);
        boolean got = policy.obtain("cobblestone");

        int thrown = w.thrownByPlayer().size();
        int slots = w.inventory().mainInventory.length;
        assertFalse("with nothing safely throwable left, the goal is out of reach: "
                + policy.traceSummary(), got);
        assertTrue("the FIRST make-room is speculative and does happen -- the chain has not asked"
                + " for planks at that moment -- so the count is 1 or more, never 0", thrown >= 1);
        assertTrue("but it must stop. " + thrown + " of " + slots + " stacks thrown is not an agent"
                + " emptying its own supply to feed the chain it is trying to run",
                thrown * 4 < slots);
        assertTrue("and every log the world lost must have a freed slot behind it, or a dig threw"
                + " its result away: " + (logsBefore - logsStanding(w)) + " log(s) lost for "
                + thrown + " slot(s) made", logsBefore - logsStanding(w) <= thrown);
    }

    /**
     * The protected set is CONSERVATIVE, and this is the proof: the run reaches a point where it
     * stops, and says so.
     *
     * <p>Without this the suite would pass just as happily if the rule threw whatever it saw,
     * because T23's bag happens to be all dirt. The refusal is the half of the behaviour that
     * makes a drop safe to permit at all, and it has to be a SPOKEN refusal -- the thing a caller
     * reads is the trace, not the slot.
     */
    @Test
    public void theRefusalIsReachableAndNamedRatherThanTakenSilently() {
        SimWorld w = fullBagOfPlanks();
        EvalHarness h = new EvalHarness(w);
        GoalPolicy policy = new GoalPolicy(w, h);
        policy.obtain("cobblestone");

        List<String> trace = policy.trace();
        assertTrue("the refusal must be SAID, naming the cost of going on, not merely taken: "
                + trace, trace.stream().anyMatch(s -> s.contains("can be thrown away without"
                        + " losing what this chain still needs")));
        assertTrue("and the consequence of not finding room must be named too, so the trace says"
                + " what the item is losing: " + trace,
                trace.stream().anyMatch(s -> s.contains("out of reach of any dig that reads")));
    }

    /**
     * The throw goes through the PRODUCTION verb, not by editing the inventory.
     *
     * <p>{@code SimWorld} only empties a slot from {@link SimWorld#dropStack}, the actuator method
     * {@code DropController} calls. A policy that reached into {@code mainInventory} directly would
     * make every other test in this file pass while proving nothing about the capability this work
     * added.
     */
    @Test
    public void theThrowIsADropAndNotAnInventoryEdit() {
        SimWorld w = fullBagOfDirt();
        EvalHarness h = new EvalHarness(w);
        new GoalPolicy(w, h).obtain("cobblestone");

        // A dropped stack is gone from the BAG, and it is recorded as having left. A deletion
        // would show neither.
        assertFalse(w.thrownByPlayer().isEmpty());
        assertFalse("and the slot the policy named must actually be empty, not merely unlisted: "
                + w.describeInventory(), w.describeInventory().contains("35:dirt"));
    }
}