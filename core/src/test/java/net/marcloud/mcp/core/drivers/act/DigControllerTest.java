package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Teeth for {@link DigController}: start-retry honoring blockHitDelay, K-pump
 * completion, cancel teardown, stall failure, no-block and out-of-reach honest
 * failures.
 */
public class DigControllerTest {

    /** A reachable block at (0,1,2) given the fake's default eye at (0,1.62,0). */
    private FakeActuator worldWithReachableBlock() {
        FakeActuator act = new FakeActuator();
        act.putBlock(0, 1, 2);
        return act;
    }

    private static InteractIntent dig() {
        return InteractIntent.dig(0, 1, 2, 1);
    }

    @Test
    public void startRetryHonorsBlockHitDelayThenFails() {
        FakeActuator act = worldWithReachableBlock();
        act.startDigFailFirst = 1000; // startDig always reports "not yet"
        DigController c = new DigController(dig(), 5); // blockHitDelay budget = 5

        ActOutcome out = null;
        for (int i = 0; i < 5; i++) {
            out = c.tick(act);
        }
        assertTrue("fails once the retry budget is exhausted", out.terminal());
        assertFalse(out.ok());
        assertEquals("retried exactly blockHitDelay times", 5, act.startDigCalls);
    }

    @Test
    public void startRetrySucceedsWithinBudget() {
        FakeActuator act = worldWithReachableBlock();
        act.startDigFailFirst = 2; // first 2 fail, 3rd succeeds
        DigController c = new DigController(dig(), 5);

        assertFalse(c.tick(act).terminal()); // attempt 1: fail, running
        assertFalse(c.tick(act).terminal()); // attempt 2: fail, running
        ActOutcome out = c.tick(act);        // attempt 3: start ok -> digging
        assertFalse("digging is not terminal", out.terminal());
        assertEquals(3, act.startDigCalls);
    }

    @Test
    public void kPumpsThenAirCompletesAtPumpK() {
        FakeActuator act = worldWithReachableBlock();
        act.breakAfterPumps = 3; // block gone on the 3rd pump
        DigController c = new DigController(dig(), 5);

        c.tick(act); // RESOLVING -> DIGGING (startDig, no pump)
        c.tick(act); // pump #1
        c.tick(act); // pump #2
        ActOutcome out = c.tick(act); // pump #3 breaks it
        assertTrue(out.terminal() && out.ok());
        assertEquals(3, c.pumps());
        assertTrue(out.message().contains("broken"));
    }

    @Test
    public void cancelCallsCancelDigAndEndsCancelled() {
        FakeActuator act = worldWithReachableBlock();
        DigController c = new DigController(dig(), 5);
        c.tick(act); // start digging
        c.requestCancel();
        ActOutcome out = c.tick(act);
        assertEquals(ActPhase.CANCELLED, out.state());
        assertEquals("cancelDig must be issued exactly once", 1, act.cancelDigCalls);
    }

    @Test
    public void stalledPumpFails() {
        FakeActuator act = worldWithReachableBlock();
        act.pumpStallAt = 1; // first pump reports no progress
        DigController c = new DigController(dig(), 5);
        c.tick(act);                  // start -> digging
        ActOutcome out = c.tick(act); // pump #1 stalls
        assertTrue(out.terminal());
        assertFalse(out.ok());
        assertTrue(out.message().contains("stalled"));
    }

    @Test
    public void noBlockToDigFailsBeforeStarting() {
        FakeActuator act = new FakeActuator(); // no block placed
        DigController c = new DigController(dig(), 5);
        ActOutcome out = c.tick(act);
        assertTrue(out.terminal());
        assertFalse(out.ok());
        assertEquals("must not have called startDig for empty air", 0, act.startDigCalls);
        assertTrue(out.message().contains("no block"));
    }

    @Test
    public void outOfReachFails() {
        FakeActuator act = new FakeActuator();
        act.putBlock(0, 1, 20); // ~18 blocks away, past the 4.5 reach
        DigController c = new DigController(InteractIntent.dig(0, 1, 20, 1), 5);
        ActOutcome out = c.tick(act);
        assertTrue(out.terminal());
        assertFalse(out.ok());
        assertEquals("out-of-reach must not call startDig", 0, act.startDigCalls);
        assertTrue(out.message().contains("reach"));
    }
}
