package net.marcloud.mcp.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Headless guard for {@link LiveGameGate}, the skip-vs-fail wiring shared by the
 * five game-dependent {@code *LiveIT} scaffolds. Same technique as
 * {@code NativeDebugGateTest}: exercise the pure decision so the truth table is
 * pinned without a game.
 *
 * <p>The assertion that matters is {@link #liveRequestedButGameAbsentFails()}.
 * Before this change every IT resolved that case with a second
 * {@code Assume.assumeTrue}, i.e. SKIP, which is exactly the recorded defect: an
 * operator ran with {@code -Dmcp.it.live=true} and got seven skips and BUILD
 * SUCCESS. This test goes red on that old semantics rather than merely failing
 * to compile, so it can distinguish "gate rewritten" from "gate reverted".
 */
public class LiveGameGateTest {

    @Test
    public void liveNotRequestedSkips() {
        assertEquals("nobody asked for a live run, so skipping is the honest answer",
                LiveGameGate.Gate.SKIP, LiveGameGate.gate(false, false));
    }

    @Test
    public void liveNotRequestedSkipsEvenIfTheGameIsSomehowUp() {
        // Belt-and-braces on the flag being the sole trigger: an unrequested live
        // run must not start just because a game happens to be reachable.
        assertEquals(LiveGameGate.Gate.SKIP, LiveGameGate.gate(false, true));
    }

    @Test
    public void liveRequestedButGameAbsentFails() {
        assertEquals("an explicit -Dmcp.it.live=true with no reachable game must FAIL; "
                        + "skipping here is the defect that made a live request report success",
                LiveGameGate.Gate.FAIL, LiveGameGate.gate(true, false));
    }

    @Test
    public void liveRequestedAndGamePresentRuns() {
        assertEquals(LiveGameGate.Gate.RUN, LiveGameGate.gate(true, true));
    }

    @Test
    public void failMessageNamesTheNullSingletonAndTheRealLiveRoute() {
        String msg = LiveGameGate.failMessage("live GUI screen", "NullPointerException: mc");
        assertTrue("must name Minecraft.getMinecraft() so the reader knows WHY it can never pass",
                msg.contains("Minecraft.getMinecraft()"));
        assertTrue("must point at the real live-verification route",
                msg.contains("scripts/nav-astar-probe.py"));
        assertTrue("must carry the swallowed probe reason through to the operator",
                msg.contains("NullPointerException: mc"));
    }

    @Test
    public void probeKeepsTheThrownReasonInsteadOfSwallowingIt() {
        LiveGameGate.Liveness l = LiveGameGate.probe(() -> {
            throw new IllegalStateException("no game here");
        });
        assertFalse("a throwing probe means the game is not up", l.up());
        assertTrue("the old catch(Throwable) dropped this; the reason must survive: " + l.reason(),
                l.reason().contains("IllegalStateException")
                        && l.reason().contains("no game here"));
    }

    @Test
    public void probeReportsAPlainFalseWithoutInventingAnException() {
        LiveGameGate.Liveness l = LiveGameGate.probe(() -> false);
        assertFalse(l.up());
        assertTrue("a quiet false still needs a reason: " + l.reason(),
                l.reason().contains("returned false"));
    }

    @Test
    public void probeReportsSuccessWithNoReason() {
        LiveGameGate.Liveness l = LiveGameGate.probe(() -> true);
        assertTrue(l.up());
        assertEquals(null, l.reason());
    }

    @Test
    public void skipMessageTellsTheOperatorHowToDemandALiveRun() {
        String msg = LiveGameGate.skipMessage("live GUI screen");
        assertTrue("a skip must name the flag that turns it into a real attempt",
                msg.contains("-Dmcp.it.live=true"));
        assertTrue("and the failsafe knob, since the ITs only run under failsafe",
                msg.contains("core.it.skip"));
    }
}
