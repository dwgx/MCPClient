package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.event.events.TickEvent;
import org.junit.Test;

/**
 * The INTERACT applier must ROUTE {@link InteractIntent.Kind#DROP} to {@link DropController}.
 *
 * <p>Separate from {@link DropControllerTest} because that one drives the controller directly, and
 * driving it directly cannot tell a wired route from a defaulted one: {@code InteractApplier}'s
 * {@code step} and {@code bind} switches both end in {@code default ->}, so a DROP that reached
 * {@code InteractController} instead would still return a terminal outcome with a plausible
 * message. Only the SLOT PHASE and the slot's own contents distinguish them.
 *
 * <p>Driven through the real {@link ActTickLoop} and a private {@link GameClock}, exactly as
 * {@code EvalHarness} drives it, so the effective-tick gate is part of what is under test rather
 * than something the test quietly steps past.
 */
public class InteractApplierDropRoutingTest {

    /** A runtime with the INTERACT slot wired to {@code act} and nothing else. */
    private static ActRuntime runtime(FakeActuator act, GameClock clock) {
        ActRuntime rt = new ActRuntime(clock);
        rt.registerApplier(ActSlot.INTERACT, new InteractApplier(act));
        return rt;
    }

    /** Tick until the INTERACT slot is terminal, or the budget runs out. */
    private static boolean tickUntilTerminal(ActRuntime rt, ActTickLoop loop, GameClock clock) {
        for (int i = 0; i < 8; i++) {
            if (rt.record(ActSlot.INTERACT).phase().isTerminal()) {
                return true;
            }
            loop.onTick(new TickEvent(clock.advance()));
        }
        return rt.record(ActSlot.INTERACT).phase().isTerminal();
    }

    @Test
    public void aDropOnTheInteractSlotEmptiesTheSlotItNamed() {
        FakeActuator act = new FakeActuator();
        act.putStack(21, 64);
        GameClock clock = new GameClock();
        ActRuntime rt = runtime(act, clock);
        ActTickLoop loop = new ActTickLoop(rt);
        rt.submit(InteractIntent.dropStack(21));

        assertTrue("the slot must reach a verdict", tickUntilTerminal(rt, loop, clock));
        assertEquals("the slot it named must be empty", 0, act.slotStackSize(21));
        assertEquals(ActPhase.COMPLETE, rt.record(ActSlot.INTERACT).phase());
        assertTrue("and the slot's own message must name the slot it emptied: "
                + rt.record(ActSlot.INTERACT).message(),
                rt.record(ActSlot.INTERACT).message().contains("21"));
    }

    @Test
    public void aDropThatDoesNotLandFailsTheSlotRatherThanCompletingIt() {
        FakeActuator act = new FakeActuator();
        act.putStack(21, 64);
        act.dropIsANoOp = true;
        GameClock clock = new GameClock();
        ActRuntime rt = runtime(act, clock);
        ActTickLoop loop = new ActTickLoop(rt);
        rt.submit(InteractIntent.dropStack(21));

        assertTrue(tickUntilTerminal(rt, loop, clock));
        assertEquals("the stack must still be there", 64, act.slotStackSize(21));
        assertEquals("and the slot must say the drop did not happen", ActPhase.FAILED,
                rt.record(ActSlot.INTERACT).phase());
    }

    @Test
    public void oneTickIsNotEnoughToCompleteADrop() {
        // If InteractApplier's route is ever removed, DROP falls into `default ->` and
        // InteractController answers instantly on a block target that does not exist. Both facts
        // are visible here and neither needs a word of message text: nothing was dropped, and the
        // slot is terminal after a single applied tick.
        FakeActuator act = new FakeActuator();
        act.putStack(21, 64);
        GameClock clock = new GameClock();
        ActRuntime rt = runtime(act, clock);
        ActTickLoop loop = new ActTickLoop(rt);
        rt.submit(InteractIntent.dropStack(21));

        // Tick until the click has actually gone out (the effective-tick gate may spend a tick
        // before the first applied one), then judge that very tick.
        for (int i = 0; i < 4 && act.calls.isEmpty(); i++) {
            loop.onTick(new TickEvent(clock.advance()));
        }
        assertEquals("the click went out", 1, act.calls.size());
        assertFalse("the send tick cannot be terminal -- the slot has not been RE-READ yet, so a"
                + " verdict here would be a guess rather than a measurement",
                rt.record(ActSlot.INTERACT).phase().isTerminal());
        // The fake applies the click synchronously, exactly as PlayerControllerMP.windowClick does
        // on a live client, so the world's state has already moved by the end of this tick. That
        // is precisely why the phase being non-terminal is the assertion: the state changed and
        // the slot is still refusing to claim it until it has asked again. A controller that
        // reported on the send tick would be reporting the write it just made, not the world.
    }

    @Test
    public void aDropOfAnEmptySlotFailsTheSlotWithoutSendingAnything() {
        FakeActuator act = new FakeActuator();
        GameClock clock = new GameClock();
        ActRuntime rt = runtime(act, clock);
        ActTickLoop loop = new ActTickLoop(rt);
        rt.submit(InteractIntent.dropStack(21));

        assertTrue(tickUntilTerminal(rt, loop, clock));
        assertEquals(ActPhase.FAILED, rt.record(ActSlot.INTERACT).phase());
        assertEquals("no click may be sent for a slot with nothing in it", 0, act.calls.size());
    }
}