package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import net.marcloud.mcp.core.util.Belief;

/**
 * Grading a dig completion must cost the world exactly what it cost before the grade existed.
 *
 * <p><b>The constraint this file exists to catch.</b> ADR-0005 rejects a mechanism that spends
 * capability and buys none. A belief is exactly the shape of thing that gets built that way: a
 * second {@code blockAt} "just to see whether the name was real", a readability probe to decide
 * whether to call the completion OBSERVED, a re-verification pass on a read the controller had
 * already made. None of those is wrong, all of them are invisible in a diff review, and together
 * they are the third category arriving under a new name.
 *
 * <p><b>Exact equality, not a bound,</b> for the reason {@code TheBeliefLayerCostsNoWorldReadTest}
 * gives for the walk: the property here is zero, so any drift is a strict increase and a generous
 * bound would let a ten-read regression pass.
 *
 * <p><b>Where the baseline came from.</b> Measured on the PRE-CHANGE tree, before a line of the
 * grade was written, by running this same counting harness against the controller as it stood at
 * {@code HEAD} with its compiled class placed ahead of the new one on the classpath. The numbers
 * below are that measurement and not a guess -- and the dig already read the world TWICE on its
 * completing tick, because {@code targetGone} reads the name to answer its own question and
 * {@code brokenMessage} reads it again to build the ", now X" clause. The grade is a function of
 * the read {@code targetGone} already made, which is why the second read is still there.
 */
public class ABeliefOnADigCostsNoWorldReadTest {

    /**
     * Counts every read of the world a controller can make through the actuator: both
     * {@code blockAt}, which asks for a name, and {@code blockPresent}, which asks whether the
     * space is occupied.
     *
     * <p>Both, not just {@code blockAt}: the fallback completion path calls {@code blockPresent},
     * so counting only the naming seam would let a grade bought with a presence probe pass.
     */
    private static final class CountingActuator extends FakeActuator {
        int nameReads;
        int presenceReads;

        @Override
        public String blockAt(int x, int y, int z) {
            nameReads++;
            return super.blockAt(x, y, z);
        }

        @Override
        public boolean blockPresent(int x, int y, int z) {
            presenceReads++;
            return super.blockPresent(x, y, z);
        }

        int reads() {
            return nameReads + presenceReads;
        }
    }

    private static final int PRE_CHANGE_OBSERVED_COMPLETION_READS = 5;
    private static final int PRE_CHANGE_FALLBACK_COMPLETION_READS = 4;
    private static final int PRE_CHANGE_MID_DIG_READS = 9;

    /**
     * Two reads on a pumping tick, not one, and this surprises people: a DIGGING tick asks
     * {@code targetGone} at the top of {@code tick} and again after {@code pumpDig}, because the
     * block can break during the pump. That is the pre-existing shape and it is deliberately kept
     * -- the claim this file makes is that grading added nothing, not that the dig became cheaper.
     */
    private static final int READS_PER_UNFINISHED_PUMP = 2;

    @Test
    public void aCompletionTheControllerWatchedCostWhatItCostBeforeTheGradeExisted() {
        CountingActuator act = new CountingActuator();
        act.putBlock(0, 1, 0, "stone");
        act.breakAfterPumps = 1;
        act.fillsWith = "water";
        DigController c = new DigController(InteractIntent.dig(0, 1, 0, 1), 5);

        c.tick(act);          // RESOLVING: presence check, then the baseline name sample
        int beforeBreaking = act.reads();
        c.tick(act);          // the pump that breaks it, and the completion verdict

        assertEquals("the start tick's own cost, which the grade did not add to either: "
                + beforeBreaking, 2, beforeBreaking);
        assertEquals("a completion graded OBSERVED now costs " + act.reads() + " world reads; it cost "
                        + PRE_CHANGE_OBSERVED_COMPLETION_READS + " before the belief layer existed. "
                        + "An increase means the grade asked the world something the completion had "
                        + "already asked, which is ADR-0005's pure-cost category under a new name",
                PRE_CHANGE_OBSERVED_COMPLETION_READS, act.reads());
    }

    @Test
    public void aCompletionThatRestsOnTheEmptinessFallbackCostsWhatItCostBeforeTheGradeExisted() {
        CountingActuator act = new CountingActuator();
        act.putBlock(0, 1, 0, "stone");
        act.blockAtReturnsNull = true;      // the baseline sample cannot be read
        DigController c = new DigController(InteractIntent.dig(0, 1, 0, 1), 5);

        c.tick(act);
        act.removeBlock(0, 1, 0);
        c.tick(act);                        // graded INFERRED, off the emptiness fallback

        assertEquals("an INFERRED completion now costs " + act.reads() + " world reads; it cost "
                        + PRE_CHANGE_FALLBACK_COMPLETION_READS + " before the belief layer existed. "
                        + "This is the path where a readability probe would be most tempting -- "
                        + "'was the target unreadable, or did it break?' -- and that probe is "
                        + "precisely the extra read this forbids",
                PRE_CHANGE_FALLBACK_COMPLETION_READS, act.reads());
    }

    @Test
    public void aTickOfAnOrdinaryDigIsStillUnchangedAndTheCrosshairReadCostsNothingEither() {
        CountingActuator act = new CountingActuator();
        act.putBlock(0, 1, 0, "stone");
        act.breakAfterPumps = 3;
        DigController c = new DigController(InteractIntent.dig(0, 1, 0, 1), 5);

        c.tick(act);
        int afterStart = act.reads();
        c.tick(act);
        int afterOnePump = act.reads();
        c.tick(act);
        c.tick(act);

        assertEquals("one pump of an unfinished dig must not have got more expensive: " + afterOnePump,
                afterStart + READS_PER_UNFINISHED_PUMP, afterOnePump);
        assertEquals("and the whole dig still costs " + act.reads() + " world reads; it cost "
                        + PRE_CHANGE_MID_DIG_READS + " before the belief layer existed",
                PRE_CHANGE_MID_DIG_READS, act.reads());

        // The crosshair grade, for completeness: reading it asks nothing of the world at all,
        // because it is a re-reading of a field the game already traced.
        int beforeMouseOver = act.reads();
        assertEquals("and the crosshair is a re-reading of a field the game already traced, so "
                + "grading it asks the world nothing", Belief.INFERRED, act.mouseOver().belief());
        assertEquals("grading the crosshair must not read the world: " + beforeMouseOver,
                beforeMouseOver, act.reads());
    }
}

