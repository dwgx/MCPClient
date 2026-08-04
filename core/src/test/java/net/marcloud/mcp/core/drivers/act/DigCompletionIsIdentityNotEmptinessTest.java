package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A dig is finished when THE TARGETED BLOCK is gone -- not when the space is empty.
 *
 * <p>FOUND BY ASKING A LIVE CLIENT what {@code blockPresent} returns, one block type at a time.
 * It is {@code getMaterial() != Material.air}, so measured live it is TRUE for water, flowing water,
 * lava, gravel, tall grass and a torch -- everything except air. As a completion test that reports
 * "still digging" for any position that has been refilled, when the block in fact broke.
 *
 * <p><b>THE PREDICTED CONSEQUENCE DID NOT REPRODUCE, and saying so is the point of this paragraph.</b>
 * The claim was that mining stone underwater would break it, water would fill the space, and the dig
 * would announce "dig stalled" about a block already gone. Run on a live client it completed
 * correctly, and the measurement says why: water reaches the emptied space <b>3 game ticks</b> after
 * the break (t=362045 to t=362048; water's {@code tickRate} is 5), while {@link DigController} polls
 * once per tick -- so the deciding poll sees air and the old test was right. Lava is slower
 * ({@code tickRate} 30) and falling gravel becomes an entity, so neither fills the space on the
 * breaking tick either.
 *
 * <p>What these tests therefore pin is <b>correctness by construction</b>: the completion test asks
 * the caller's actual question, which holds whatever the refill timing is -- a server with different
 * fluid rates, a block that replaces itself instantly, another player filling the hole. The cases
 * below are constructed rather than observed, and that is stated in each one rather than dressed up
 * as a reproduction.
 *
 * <p><b>The defect that WAS reachable</b> is the ordering, pinned by
 * {@link #aPumpThatAppliesNoDamageBecauseTheBlockJustBrokeIsNotAStall}: the stall was tested before
 * the gone, so a pump that reports "no damage applied" because the block had just broken produced a
 * failure for a completed dig. That one does not depend on refill timing at all.
 *
 * <p><b>Why the headless suite could not see any of it.</b> {@code FakeActuator} tracked presence as
 * a set of positions, so "broken and replaced" was not a state it could represent -- every existing
 * dig test breaks a block cleanly into air, the one case that always worked. The fake now carries a
 * name per position.
 */
public class DigCompletionIsIdentityNotEmptinessTest {

    private static final int X = 4;
    private static final int Y = 62;
    private static final int Z = -7;

    private static FakeActuator withStone() {
        FakeActuator act = new FakeActuator();
        act.eye = new double[] {X + 0.5, Y + 0.5, Z + 0.5};   // in reach
        act.putBlock(X, Y, Z, "stone");
        return act;
    }

    private static DigController digger() {
        return new DigController(InteractIntent.dig(X, Y, Z, 1));
    }

    /** Run to a terminal outcome, or return the last one if it never finishes. */
    private static ActOutcome runTo(DigController c, FakeActuator act, int bound) {
        ActOutcome out = null;
        for (int i = 0; i < bound; i++) {
            out = c.tick(act);
            if (out.terminal()) {
                return out;
            }
        }
        return out;
    }

    /**
     * A block broken and refilled in the same tick must still COMPLETE.
     *
     * <p>CONSTRUCTED, not observed: measured live, water needs 3 ticks to arrive and the deciding
     * poll is 1 tick after the break, so vanilla water never actually produces this state. What the
     * case pins is that the completion test asks about the TARGET rather than the space, which is
     * what makes it right whatever the refill timing is. On the previous code it never terminated --
     * the message below shows it still "digging stone" after 39 ticks.
     */
    @Test
    public void aBlockBrokenAndImmediatelyFilledByWaterStillCompletes() {
        FakeActuator act = withStone();
        act.breakAfterPumps = 3;
        act.fillsWith = "flowing_water";   // vanilla flows in on the tick the block breaks

        ActOutcome out = runTo(digger(), act, 40);

        assertTrue("the dig must terminate", out != null && out.terminal());
        assertTrue("the block broke, so this must be a SUCCESS. The emptiness test sees water in "
                        + "the space and never concludes -- constructed, since live water takes 3 "
                        + "ticks to arrive: " + out.message(),
                out.ok());
        assertTrue("and it must name the block it broke: " + out.message(),
                out.message().contains("stone"));
        assertTrue("and say what filled the space, because a hole full of water is not the same "
                        + "next step as an empty one: " + out.message(),
                out.message().contains("now flowing_water"));
    }

    /** The same shape with lava, where acting on a wrong report is worse than inconvenient. */
    @Test
    public void aBlockBrokenAndFilledByLavaCompletesAndSaysSo() {
        FakeActuator act = withStone();
        act.breakAfterPumps = 2;
        act.fillsWith = "lava";

        ActOutcome out = runTo(digger(), act, 40);

        assertTrue("breaking a block into lava is still a completed dig: " + out.message(),
                out != null && out.terminal() && out.ok());
        assertTrue("and the caller must be told what is in the hole before it steps in: "
                + out.message(), out.message().contains("now lava"));
    }

    /** Gravel falling in: the other everyday filler. */
    @Test
    public void aBlockBrokenAndFilledByFallingGravelCompletes() {
        FakeActuator act = withStone();
        act.breakAfterPumps = 2;
        act.fillsWith = "gravel";

        ActOutcome out = runTo(digger(), act, 40);
        assertTrue("gravel falling into the hole does not make the dig unfinished: " + out.message(),
                out != null && out.terminal() && out.ok());
    }

    /** The ordinary case must be untouched: a clean break into air still completes and says so. */
    @Test
    public void acleanBreakIntoAirStillCompletes() {
        FakeActuator act = withStone();
        act.breakAfterPumps = 2;   // fillsWith stays null: breaks to air

        ActOutcome out = runTo(digger(), act, 40);
        assertTrue(out != null && out.terminal() && out.ok());
        assertTrue("the block name still belongs in the message: " + out.message(),
                out.message().contains("stone"));
        assertFalse("and nothing filled the space, so there is nothing to report: " + out.message(),
                out.message().contains("now "));
    }

    /**
     * A real stall must still fail. Without this, "always complete" would pass every test above.
     *
     * <p>The block never breaks and the pump reports no damage -- a tool that cannot break the
     * material. That is a genuine failure and the caller needs it as one.
     */
    @Test
    public void aGenuineStallOnAnUnbrokenBlockStillFails() {
        FakeActuator act = withStone();
        act.pumpStallAt = 1;       // first pump applies no damage
        act.breakAfterPumps = 0;   // and the block never goes away

        ActOutcome out = runTo(digger(), act, 40);
        assertTrue(out != null && out.terminal());
        assertFalse("a block that never broke is a failure, not a success: " + out.message(),
                out.ok());
        assertTrue(out.message().contains("stalled"));
    }

    /** Digging air is still an honest failure, and must not be confused with a completed dig. */
    @Test
    public void diggingWhereThereIsNoBlockStillFailsHonestly() {
        FakeActuator act = new FakeActuator();
        act.eye = new double[] {X + 0.5, Y + 0.5, Z + 0.5};
        // no putBlock: the target is air

        ActOutcome out = runTo(digger(), act, 10);
        assertTrue(out != null && out.terminal());
        assertFalse(out.ok());
        assertTrue("the message must say there was nothing there, not that something broke: "
                + out.message(), out.message().contains("no block to dig"));
    }

    /**
     * The pump reporting no damage on the FINISHING tick must not be read as a stall.
     *
     * <p>An ordering property, and it was wrong before: {@code pumpDig} answers "was damage
     * applied", so the tick that finishes a block can honestly answer no -- there is nothing left to
     * damage. The stall test ran first and announced a failure for the very block that had just
     * broken. Scripted here so the two cannot be reordered back.
     */
    @Test
    public void aPumpThatAppliesNoDamageBecauseTheBlockJustBrokeIsNotAStall() {
        FakeActuator act = withStone();
        act.breakAfterPumps = 2;
        act.stallOnTheBreakingPump = true;   // the finishing pump reports false

        ActOutcome out = runTo(digger(), act, 40);
        assertTrue(out != null && out.terminal());
        assertTrue("the block broke on that very pump, so 'no damage applied' is not a stall: "
                + out.message(), out.ok());
    }

    /**
     * The gone test degrades to the old question when the start sample could not be read.
     *
     * <p>Not a detail: with no baseline, an identity comparison against null would make EVERY dig
     * complete on its first tick -- a false success on every dig in the game. Falling back to
     * emptiness is wrong in the safe direction (it under-reports completion), and that is the
     * direction to fail in.
     */
    @Test
    public void anUnreadableStartSampleFallsBackToEmptinessRatherThanCompletingInstantly() {
        FakeActuator act = withStone();
        act.blockAtReturnsNull = true;   // the name could not be read
        act.breakAfterPumps = 3;

        DigController c = digger();
        ActOutcome first = c.tick(act);      // RESOLVING -> startDig, samples null
        assertFalse("a dig with an unreadable target must not complete on its first tick: "
                + first.message(), first.terminal());

        ActOutcome out = runTo(c, act, 40);
        assertTrue("and it must still reach a conclusion once the block actually goes away: "
                + (out == null ? "null" : out.message()), out != null && out.terminal() && out.ok());
    }

    /** The fake's own contract, so the tests above are not resting on an invented world. */
    @Test
    public void theFakeReportsNamesAndPresenceConsistently() {
        FakeActuator act = new FakeActuator();
        assertNull("an absent position has no name", act.blockAt(1, 2, 3));
        assertFalse(act.blockPresent(1, 2, 3));

        act.putBlock(1, 2, 3, "iron_ore");
        assertEquals("iron_ore", act.blockAt(1, 2, 3));
        assertTrue(act.blockPresent(1, 2, 3));

        act.replaceBlock(1, 2, 3, "water");
        assertEquals("a replacement keeps the space occupied -- which is the whole point",
                "water", act.blockAt(1, 2, 3));
        assertTrue("and blockPresent still says true, exactly as the live client does",
                act.blockPresent(1, 2, 3));

        act.removeBlock(1, 2, 3);
        assertNull(act.blockAt(1, 2, 3));
        assertFalse(act.blockPresent(1, 2, 3));
    }
}
