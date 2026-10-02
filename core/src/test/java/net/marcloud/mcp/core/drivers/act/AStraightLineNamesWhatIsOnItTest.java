package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

/**
 * A straight-line walk must name what is on the line, while there is still distance to change
 * your mind about it.
 *
 * <p>Written after a live client walked the player into the sea twice. {@code to} is documented
 * as never routing around anything, and it does not — but "does not route" is not "does not
 * warn", and the controller was asking the world nothing at all. The run said
 * {@code "walking, 135.25 blocks to go"} for the entire 30-block fall, and the player's health
 * went 20 → 0 with nothing in between that a caller could have read.
 *
 * <p>The second half of this file is the one that matters for correctness: a warning that can be
 * <em>manufactured</em> is worse than none, because a caller learns to ignore it. The specific
 * trap is that {@code blockAt} answers {@code null} for air AND for "could not read", so an
 * unloaded chunk would read as a bottomless pit on every line out of it.
 */
public class AStraightLineNamesWhatIsOnItTest {

    private FakeActuator act;
    private NavController nav;

    @Before
    public void setUp() {
        act = new FakeActuator();
        // The fake defaults to the origin, and the hazard scan samples at the FEET cell -- so a
        // test that leaves the player at y=0 scans a line through y=0 and finds nothing at y=64.
        act.setPosition(0.5, 64.0, 0.5);
        act.putBlock(0, 63, 0);
        nav = new NavController(40.0, 64.0, 0.0, 400);
    }

    private String runSomeTicks() {
        String last = "";
        for (int i = 0; i < 6; i++) {
            ActOutcome o = nav.tick(act);
            last = o.message();
            if (o.terminal()) {
                break;
            }
        }
        return last;
    }

    @Test
    public void aClearLineSaysNothingAboutHazards() {
        for (int x = 0; x <= 40; x++) {
            act.putBlock(x, 63, 0);
        }
        String msg = runSomeTicks();
        assertFalse("an ordinary flat line must not cry wolf: " + msg, msg.contains("LAVA"));
        assertFalse(msg, msg.contains("WATER"));
        assertFalse(msg, msg.contains("drop"));
    }

    @Test
    public void lavaOnTheLineIsNamedWithWhereAndHowFar() {
        for (int x = 0; x <= 40; x++) {
            act.putBlock(x, 63, 0);
        }
        act.putBlock(20, 64, 0, "lava");

        String msg = runSomeTicks();
        assertTrue("lava is unsurvivable and must be named: " + msg, msg.contains("LAVA"));
        assertTrue("and the caller needs to know WHERE, to judge whether it matters: " + msg,
                msg.contains("(20,64,0)"));
        // A BLOCK DISTANCE, not a percentage. "45% of the way there" is the same sentence for a
        // hazard 180 blocks out on a 400-block line and one 2 blocks out on a 5-block line, so it
        // cannot tell a caller whether cancelling is still cheap -- which is the one thing a
        // warning has to answer.
        assertTrue("and how far, in blocks, because a percentage of an unknown total is not a "
                        + "warning: " + msg,
                msg.matches("(?s).*sampled: LAVA at \\(20,64,0\\), \\d+\\.\\d blocks ahead.*"));
    }

    @Test
    public void waterOnTheLineIsNamedAndSaysItWillDrown() {
        for (int x = 0; x <= 40; x++) {
            act.putBlock(x, 63, 0);
        }
        act.putBlock(12, 64, 0, "water");

        String msg = runSomeTicks();
        assertTrue("this is the one that killed the player twice: " + msg, msg.contains("WATER"));
        assertTrue("and the message must say the consequence, or a caller reads a block name and "
                + "has to know what water does unaided: " + msg, msg.contains("drown"));
    }

    @Test
    public void aDropWithNoFloorIsNamedAndPutsAPriceOnIt() {
        // Ground for the first 10 blocks, then nothing: the 30-block fall that killed the player.
        for (int x = 0; x < 10; x++) {
            act.putBlock(x, 63, 0);
        }

        String msg = runSomeTicks();
        assertTrue("a 30-block fall is what the live client did: " + msg, msg.contains("drop"));
        assertTrue("and the price, because 'a drop' does not tell a caller whether to care: "
                + msg, msg.contains("ceil(distance-3)"));
        assertTrue("and how deep the gap is, because a 6-block drop and a 30-block one are the "
                + "same word: " + msg, msg.contains("no floor for " + NavController.SAFE_DROP
                + " blocks"));
    }

    @Test
    public void aShortStepDownIsNotAWarning() {
        // Three blocks of open air below the line is an ordinary walk down a hill, not a hazard.
        for (int x = 0; x < 10; x++) {
            act.putBlock(x, 63, 0);
        }
        for (int x = 10; x < 14; x++) {
            act.putBlock(x, 60, 0);
        }
        for (int x = 14; x <= 40; x++) {
            act.putBlock(x, 63, 0);
        }

        String msg = runSomeTicks();
        assertFalse("warning on every 3-block step would train the caller to ignore warnings: "
                + msg, msg.contains("drop"));
    }

    @Test
    public void anUnreadableWorldDoesNotManufactureAHazard() {
        // The important one. blockAt answers null for air AND for "no world", so with the floor
        // unread the drop test has no signal at all. Reporting a pit here would mean every line
        // out of an unloaded chunk claims a bottomless drop, and the first caller that sees one
        // stops believing the rest.
        FakeActuator blind = new FakeActuator();
        blind.setPosition(0.5, 64.0, 0.5);
        blind.blockAtReturnsNull = true;     // every read answers null
        NavController blindNav = new NavController(40.0, 64.0, 0.0, 400);

        String last = "";
        for (int i = 0; i < 6; i++) {
            ActOutcome o = blindNav.tick(blind);
            last = o.message();
            if (o.terminal()) {
                break;
            }
        }
        assertFalse("an unread world must produce no drop claim at all: " + last,
                last.contains("drop"));
        assertFalse(last, last.contains("LAVA"));
        assertFalse(last, last.contains("WATER"));
    }

    @Test
    public void aHazardStaysInTheMessageAfterThePlayerPassesIt() {
        // The scan looks AHEAD, so a hazard leaves the sample as the player walks through it.
        // Dropping the warning at that moment would leave the rest of the line looking clear on
        // no new evidence.
        for (int x = 0; x <= 40; x++) {
            act.putBlock(x, 63, 0);
        }
        act.putBlock(3, 64, 0, "lava");

        String first = runSomeTicks();
        assertTrue(first, first.contains("LAVA"));
        // Walk past it. Budget covers the 4-8 tick reaction delay first, then the 8 ticks the
        // comment describes -- the delay is real input behaviour, not something a test should
        // pretend away by starting the clock after it.
        String later = "";
        for (int i = 0; i < 20; i++) {
            act.setPosition(act.position()[0] + 0.25, act.position()[1], act.position()[2]);
            ActOutcome o = nav.tick(act);
            later = o.message();
            if (o.terminal()) {
                break;
            }
        }
        assertTrue("the warning is about the LINE, not the cell the player occupies: " + later,
                later.contains("LAVA"));
    }

    @Test
    public void aSecondIntentOnTheSameControllerEarnsItsOwnWarning() {
        for (int x = 0; x <= 40; x++) {
            act.putBlock(x, 63, 0);
        }
        act.putBlock(20, 64, 0, "lava");
        runSomeTicks();

        nav.requestCancel();
        nav.tick(act);

        // A fresh intent heading the other way must not inherit a warning about terrain it is
        // not walking toward.
        NavController away = new NavController(-40.0, 64.0, 0.0, 400);
        for (int x = -40; x <= 0; x++) {
            act.putBlock(x, 63, 0);
        }
        String last = "";
        for (int i = 0; i < 6; i++) {
            ActOutcome o = away.tick(act);
            last = o.message();
            if (o.terminal()) {
                break;
            }
        }
        assertFalse("a hazard found on one line says nothing about another: " + last,
                last.contains("LAVA"));
    }

    @Test
    public void theScanIsBoundedSoALongWalkDoesNotCostAWorldReadPerBlock() {
        // 400 blocks at one read per block is 400 world reads on a single tick. Bounded, and the
        // bound is deliberately well inside the warning horizon rather than at the destination.
        assertTrue("the scan must be bounded", SCAN_MAX_BLOCKS_PROBE > 0 && SCAN_MAX_BLOCKS_PROBE <= 64);
    }

    /** Read through the package so the constant is checked against the real number, not a copy. */
    private static final int SCAN_MAX_BLOCKS_PROBE = probeScanBound();

    private static int probeScanBound() {
        try {
            java.lang.reflect.Field f = NavController.class.getDeclaredField("SCAN_MAX_BLOCKS");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("NavController.SCAN_MAX_BLOCKS must exist", e);
        }
    }
}
