package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * The hazard has to be READABLE BEFORE ARRIVAL, not only in the terminal report.
 *
 * <p>Audit 2.3's remaining half. The walk already named lava, water and deep drops; what it did
 * not do was hand the caller anything it could <em>act</em> on until the walk was over. The
 * warning existed as a sentence glued onto the running message, so the only way to use it was to
 * substring-match prose -- and a warning that needs a parser before it can be believed gets read
 * after the player has walked into the water. This file pins the shape that fixes it: a typed
 * {@link NavHazard} on {@link ActOutcome#hazard()}, present on every walking tick including the
 * first, carrying a distance that shrinks as the player closes on it.
 *
 * <p><b>What "advance" is asserted to mean.</b> Not "the field is eventually non-null". The
 * strongest form available headlessly is on the FIRST tick, where the player has not taken a step
 * yet: {@link NavController} draws its reaction delay before its first key press, so a hazard
 * present there is visible while cancelling is still free. That is asserted together with a zero
 * forward axis -- the warning and the fact that nothing has moved yet, in the same observation,
 * which is the whole promise of "warn in advance".
 *
 * <p><b>What is deliberately NOT asserted.</b> That the walk stops. {@code walk_straight} is
 * documented as never planning and never steering, and a controller that silently refused to move
 * would be a different tool under the same name. So the movement assertions below are not
 * decoration: they are the guard on the other half of the change, and a mutation that made the
 * hazard a veto would turn them red rather than pass unnoticed.
 *
 * <p>The mutation this file was written against: publishing the hazard ONLY on the terminal
 * outcome, with the running ticks carrying the prose alone. Every assertion here about a
 * non-terminal tick goes red under it.
 */
public class AHazardIsReadableBeforeTheWalkArrivesTest {

    /** The line runs +x from the origin, so a hazard has to sit ON it to be on the walked line. */
    private static final double START_X = 0.5;
    private static final double START_Y = 64.0;
    private static final double START_Z = 0.5;
    private static final int LAVA_X = 12;
    private static final int TARGET_X = 30;

    /** One walking tick of a normal player's ~4.2 blocks/second. */
    private static final double STEP = 0.25;

    private FakeActuator act;
    private NavController nav;

    /** One driven tick: the outcome it returned and the axis it published with it. */
    private record Tick(ActOutcome outcome, float forward) { }

    /**
     * Yaw that makes W walk towards +x.
     *
     * <p>Not decoration: {@code moveFlying} applies {@code motionX += strafe*cos - forward*sin},
     * so at yaw 0 a +x heading is a STRAFE press and not a forward one. The repo documents
     * {@code strafe +1} as LEFT while vanilla names it nothing, so which named axis moves the body
     * where is exactly the thing not to assume -- see {@code NavController}'s own note. Facing
     * -90 puts this walk on the forward axis, which is what the movement assertions below are
     * about.
     */
    private static final float FACING_PLUS_X = -90f;

    @Before
    public void setUp() {
        act = new FakeActuator();
        act.setPosition(START_X, START_Y, START_Z);
        act.yaw = FACING_PLUS_X;
        // A solid, readable line, so the floor-readability gate passes and the drop test can run.
        // Without a readable floor the scan is REQUIRED to stay silent, which is a separate test.
        for (int x = 0; x <= TARGET_X; x++) {
            act.putBlock(x, 63, 0);
        }
        nav = new NavController(TARGET_X, START_Y, 0.0, 400);
    }

    private void layLava() {
        act.putBlock(LAVA_X, 64, 0, "lava");
    }

    /**
     * One tick, then the player moves by a walking step along the axes the controller published.
     *
     * <p>The axis is captured WITH the outcome rather than read afterwards: {@code stop()} clears
     * the axes on every reacting tick and on arrival, so a value read after the walk has finished
     * describes the last tick rather than the walk, and would let a controller that never pressed
     * anything look like one that did.
     *
     * <p>The movement applies vanilla's own rotation rather than trusting the controller's
     * inversion, for the reason {@code NavControllerTest} gives: a mirrored sign would otherwise
     * be cancelled out by a matching error here.
     */
    private Tick stepOnce() {
        ActOutcome out = nav.tick(act);
        Tick t = new Tick(out, nav.forward());
        if (out.terminal()) {
            return t;
        }
        double yaw = Math.toRadians(act.yaw);
        double mx = nav.strafe() * Math.cos(yaw) - t.forward() * Math.sin(yaw);
        double mz = t.forward() * Math.cos(yaw) + nav.strafe() * Math.sin(yaw);
        double len = Math.hypot(mx, mz);
        if (len > 1e-6) {
            act.nudge(STEP * mx / len, 0, STEP * mz / len);
        }
        return t;
    }

    /** Ticks until terminal, collecting every tick on the way. */
    private List<Tick> walkToTheEnd() {
        List<Tick> seen = new ArrayList<>();
        Tick last = null;
        for (int i = 0; i < 400 && (last == null || !last.outcome().terminal()); i++) {
            last = stepOnce();
            seen.add(last);
        }
        assertNotNull("the walk never returned an outcome", last);
        assertTrue("the walk must have ended within 400 ticks: " + last.outcome().message(),
                last.outcome().terminal());
        return seen;
    }

    // ===== the acceptance: readable before arrival =====

    @Test
    public void theFirstTickAlreadyCarriesTheHazardAndThePlayerHasNotMovedYet() {
        layLava();

        Tick first = stepOnce();

        assertFalse("the load-bearing claim is that this is NOT the terminal report, so a caller "
                + "reading it still has distance left to react on: " + first.outcome().message(),
                first.outcome().terminal());
        assertNotNull("the hazard must be readable on the FIRST tick. A warning that first appears "
                + "at the terminal outcome is a postmortem, and a postmortem cannot be acted on: "
                + first.outcome().message(), first.outcome().hazard());
        NavHazard hz = first.outcome().hazard();
        assertEquals("and it must name the real cause rather than describe one: " + hz,
                NavHazard.Kind.LAVA, hz.kind());
        assertEquals("at the cell that was actually read: " + hz, LAVA_X, hz.x());
        assertEquals(64, hz.y());
        assertEquals(0, hz.z());
        // The hazard is readable on the FIRST tick, which is the whole claim: a warning that first
        // appears at the terminal outcome is a postmortem, and a postmortem cannot be acted on.
        //
        // What is NOT asserted here any more is that the axes are zero on that tick. They were,
        // because the reaction pause used to sit inside NavController and hold the body still
        // while it scanned. The pause now belongs to MoveApplier, which withholds the keys while
        // THIS controller walks from its first tick -- so the "nothing has moved yet, cancel for
        // free" window still exists end to end, but it is the applier's to hold, not this
        // controller's. TheEarlyHazardWarningReachesActStatusTest drives the real applier and
        // pins that the hazard is in act_status while the walk is still ACTIVE and still short of
        // the destination; that is where the cancel window is now covered.
        assertNotNull("and the same warning must be on the controller itself, because a caller "
                + "driving the controller directly reads the field", nav.hazard());
        assertEquals("and the two must be the same warning, not two from different scans",
                hz.kind(), nav.hazard().kind());
    }

    @Test
    public void theDistanceShrinksAsThePlayerWalksSoTheWarningCloses() {
        layLava();

        Tick first = stepOnce();
        double start = first.outcome().hazard().blocksAhead();

        // No reaction pause to step over any more. It used to be `drawnReactionDelay() + 4`
        // because the controller stood still for it and a constant distance was then correct; the
        // delay has moved to MoveApplier, which withholds the keys at the applier while this
        // controller walks from its first tick. So the distance now falls from tick one, and a
        // test that skipped a pause would be skipping nothing.
        int walking = 8;
        Tick later = first;
        for (int i = 0; i < walking && !later.outcome().terminal(); i++) {
            later = stepOnce();
        }

        assertNotNull(walking + " ticks in, long before any arrival is possible at 0.25 blocks a "
                        + "tick, the hazard must still be readable: " + later.outcome().message(),
                later.outcome().hazard());
        assertTrue("and it must be CLOSER, not the frozen number the scan happened to find. The "
                        + "cell cannot move, but the player does, and a warning whose distance does "
                        + "not fall cannot say whether cancelling is still cheap: "
                        + start + " then " + later.outcome().hazard().blocksAhead(),
                later.outcome().hazard().blocksAhead() < start);
        assertEquals("and it is the same hazard, not a different one each tick",
                first.outcome().hazard().kind(), later.outcome().hazard().kind());
        assertEquals(first.outcome().hazard().x(), later.outcome().hazard().x());
    }

    /**
     * The distance must fall on EVERY tick, not only on the ticks that happen to re-sample.
     *
     * <p>This is the assertion with teeth on the type's headline claim, and it exists because the
     * weaker one did not have any. {@link NavController} caches the sampled cells and re-reads the
     * world only once the player has drifted {@code SCAN_DRIFT} blocks off the line -- which at
     * 0.25 blocks a tick is every EIGHTH tick. A version that refreshed the distance only on those
     * ticks would still satisfy "the distance shrank by tick 10", because eight ticks is early
     * enough to look like progress. What it would report is a step function: seven identical
     * numbers and then a drop, which is a stale label rather than a closing warning. So this
     * asserts a strict decrease between CONSECUTIVE ticks.
     */
    @Test
    public void theDistanceFallsOnEverySingleTickNotOnlyOnTheTicksThatReSample() {
        layLava();

        // One tick to produce the FIRST sample. The scan runs inside tick(), so before any tick
        // there is no hazard at all and reading the field would be a null dereference rather than
        // a measurement. The old code read it straight after a warm-up loop; the loop is gone --
        // the controller no longer pauses -- but the first tick still has to happen first.
        stepOnce();
        double previous = nav.hazard().blocksAhead();

        for (int i = 0; i < 5; i++) {
            Tick t = stepOnce();
            double now = t.outcome().hazard().blocksAhead();
            assertTrue("tick " + (i + 1) + " of the walk reported " + now + " after " + previous
                            + ". A distance that only falls when the scan re-samples is a label, "
                            + "not a closing warning: between re-samples the caller is reading a "
                            + "stale number about a hazard it is walking towards.",
                    now < previous);
            previous = now;
        }
    }

    @Test
    public void theWarningFlipsToBehindOnceThePlayerHasWalkedPastIt() {
        // Deliberately past the lava, not up to it. An UNSIGNED distance would report "1.2 blocks
        // ahead" for a cell the player is standing in, and a caller reading that would cancel a
        // walk that had already got past the danger -- a warning turning into a false alarm exactly
        // once it stops being true is how callers learn to ignore warnings.
        layLava();

        Tick out = null;
        boolean turnedBehind = false;
        for (int i = 0; i < 400 && (out == null || !out.outcome().terminal()); i++) {
            out = stepOnce();
            if (out.outcome().hazard() != null && out.outcome().hazard().behind()) {
                turnedBehind = true;
            }
        }

        assertTrue("the player has to have walked THROUGH the hazard cell for this to test "
                + "anything: " + out.outcome().message(), turnedBehind);
        assertNotNull("the hazard must not be deleted when it goes behind the player: the rest of "
                + "the line is unexamined, and dropping the warning would leave it looking clear "
                + "on no new evidence", out.outcome().hazard());

        // The far side, which is where a sticky warning turns back into a false alarm if the sign
        // is not carried through. Once the player is past the cell the scan no longer finds it, so
        // what is reported is the STICKY value being re-measured -- and it must keep getting
        // further behind, never bounce back to a positive number now that the scan cannot see it.
        NavHazard far = out.outcome().hazard();
        assertTrue("and it must still be behind on the far side, never ahead again: " + far,
                far.blocksAhead() < 0);
        assertTrue("the prose has to agree with the sign, or the message contradicts the field it "
                + "is rendered from: " + out.outcome().message(),
                out.outcome().message().contains("behind"));
        assertFalse("and it must not still claim to be ahead: " + out.outcome().message(),
                out.outcome().message().contains("blocks ahead"));
    }

    // ===== the other half of the change: the warning does not steer =====

    @Test
    public void aHazardOnTheLineDoesNotStopTheWalk() {
        // walk_straight never plans and never routes. If the hazard became a veto the tool would
        // be a different one wearing this name, and the caller would get a stall it has to
        // diagnose instead of the walk it asked for plus an honest warning.
        layLava();

        List<Tick> ticks = walkToTheEnd();
        boolean pressedForward = false;
        for (Tick t : ticks) {
            if (!t.outcome().terminal() && t.forward() > 0f) {
                pressedForward = true;
            }
        }
        ActOutcome last = ticks.get(ticks.size() - 1).outcome();

        assertTrue("the walk must actually arrive despite the lava on its line: " + last.message(),
                last.ok());
        assertTrue("it must have pressed forward while the hazard was known, which is the "
                        + "behaviour that makes the warning a REPORT rather than a veto",
                pressedForward);
        assertTrue("and it must report arrival rather than a stall: " + last.message(),
                last.message().contains("arrived"));
        assertTrue("which means the player really closed the distance: " + last.message(),
                Math.hypot(TARGET_X - act.position()[0], -act.position()[2]) <= 1.0);
    }

    // ===== a hazard is never manufactured =====

    @Test
    public void anUnreadableWorldStillReportsNoHazardOnAnyTick() {
        // The typed field is a NEW channel, so the property has to be pinned on it and not only on
        // the message: blockAt answers null for air AND for "could not read", and a field that
        // reports a pit the world never had would train the caller to ignore every warning it
        // gets. This is the bug that already happened once, on an unloaded chunk.
        FakeActuator blind = new FakeActuator();
        blind.setPosition(START_X, START_Y, START_Z);
        blind.blockAtReturnsNull = true;
        NavController blindNav = new NavController(TARGET_X, START_Y, 0.0, 400);

        for (int i = 0; i < 20; i++) {
            ActOutcome out = blindNav.tick(blind);
            assertNull("tick " + (i + 1) + " invented a hazard out of cells it could not read: "
                    + out.hazard() + " / " + out.message(), out.hazard());
            if (out.terminal()) {
                break;
            }
        }
    }

    @Test
    public void aClearLineReportsNoHazardAnywhereOnTheWalk() {
        List<Tick> ticks = walkToTheEnd();
        for (Tick t : ticks) {
            assertNull("an ordinary flat line must not cry wolf: " + t.outcome().hazard() + " / "
                    + t.outcome().message(), t.outcome().hazard());
        }
        ActOutcome last = ticks.get(ticks.size() - 1).outcome();
        assertTrue("and the walk still finishes: " + last.message(), last.ok());
    }

    @Test
    public void aTerminalOutcomeStillCarriesTheHazardThatEndedIt() {
        // The walk is what ends; this is the report of it. A terminal status line that says only
        // "arrived" or "gave up" tells the caller nothing about the terrain the walk crossed, and
        // after the player has fallen 30 blocks the cause is the one thing worth knowing.
        layLava();

        List<Tick> ticks = walkToTheEnd();
        ActOutcome last = ticks.get(ticks.size() - 1).outcome();

        assertTrue("the walk must have ended: " + last, last.terminal());
        assertNotNull("and the hazard it ended with must survive onto the terminal outcome: "
                + last.message(), last.hazard());
        assertEquals("with the cause still named: " + last.hazard(),
                NavHazard.Kind.LAVA, last.hazard().kind());
    }

    @Test
    public void aFreshWalkDoesNotInheritTheLastWalksWarning() {
        // Sticky WITHIN an intent is the point; sticky ACROSS intents is a lie about terrain the
        // new walk is not heading toward.
        layLava();
        walkToTheEnd();

        for (int x = -20; x <= 0; x++) {
            act.putBlock(x, 63, 0);
        }
        act.setPosition(START_X, START_Y, START_Z);
        NavController away = new NavController(-20.0, START_Y, 0.0, 400);

        ActOutcome out = away.tick(act);
        assertNull("a fresh walk must earn its own warning, not inherit the last one's: "
                + out.hazard() + " / " + out.message(), out.hazard());
        assertNull("and the controller must not be carrying one either: " + away.hazard(),
                away.hazard());
    }
}
