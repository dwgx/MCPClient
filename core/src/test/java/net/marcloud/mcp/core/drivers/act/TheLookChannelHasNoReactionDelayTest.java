package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The LOOK channel must not charge a decision-to-fingertip delay, and this pins its ABSENCE.
 *
 * <p><b>What was removed and why.</b> The field was {@code reactionTicks}, drawn
 * {@code 4 + rand(5)} and charged on any aim big enough to slew. Its justification was
 * 「a human's first rotation lands 214-416 ms after deciding」 — uncited, and the exact framing
 * ADR-0005 §1 bans (纯代价: time spent for no measurable property) and §3.1 retracts for the walk.
 * The MOVE channel's identical delay survives §3.1 as 有收益的代价 because it buys a NAMED
 * property — <b>a cancel and re-submit must not be faster than the first try</b>, pinned by
 * {@code aFreshIntentEarnsAFreshDelay}. The look channel had no such property to buy: there is
 * nothing here for a caller to skip, because the crosshair either is or is not on the target and
 * the correction is written on the tick the error is known. So the same 4-8 ticks here were the
 * same cost with the named property missing — the banned class, not the permitted one.
 *
 * <p><b>Why the assertion is on the WRITE STREAM and not on a field.</b> A test reading
 * {@code reactionTicks} would pass against a controller that charged the delay and lied about
 * it. What leaves the process — and what a server sees — is the sequence of rotation writes, so
 * that is what is asserted. It also catches a delay reintroduced with a different number or a
 * different distribution, because it pins the tick rather than a duration.
 *
 * <p><b>What "begins on the first tick" means here, precisely, and why it is not simply "the
 * yaw changes on tick 1".</b> The controller has two writes: {@code setRotation} (prev==cur, a
 * hold or a landing snap) and {@code setRotationInterp} (distinct prev/cur, the profile spending
 * its arc). A charged delay issued {@code setRotation} with the CURRENT angle — a hold — for its
 * whole run, so its first write was a snap. No delay means the first tick enters the profile,
 * which is an interp write. That is the invariant, and it is exact.
 *
 * <p>The yaw VALUE does not always change on tick 1, and must not be asserted to: the profile
 * opens from rest, so on a long aim against a tight cap its first increments are smaller than
 * one mouse count and {@link LookController#MOUSE_GCD_DEG} quantises them to zero. That is the
 * 1.8.9 mouse lattice — a free-real constraint costing nothing, which would happen anyway — not a
 * pause, and the two are separated into their own tests below so neither is read as the other.
 *
 * <p><b>Extreme draws, not one.</b> The old delay was a random draw in 4..8, so a single
 * trajectory proves nothing about it. Each test runs 200 aims per configuration and requires
 * <b>every one</b> to behave, and the outcome-string test requires the pause's wording to be
 * absent from every tick of a long aim.
 *
 * <p><b>Mutation-verified.</b> Restoring the field, its charge site and the {@code capLanded}
 * gate turns {@link #aSlewingAimEntersTheProfileOnItsFirstTick} red on its first iteration, and
 * the message reports the measured tick. Measured either side of the removal on a 45-degree aim
 * at a 40 deg/tick cap: with the delay the first <i>value</i> change landed on tick 6, without it
 * on tick 1.
 */
public final class TheLookChannelHasNoReactionDelayTest {

    /**
     * Every arc/cap pair a delay could be charged on, as {@code {arcDeg, capDegPerTick}}.
     *
     * <p>Every combination of {2, 5, 40} deg/tick against {10, 45, 90, 150} degrees EXCEPT a
     * 10-degree aim at a 40 deg/tick cap, where the main sequence's 17 deg/tick swallows the arc
     * whole and the aim lands in one tick by ARRIVING rather than by slewing. That combination is
     * asserted as a micro-correction in the last test — so it is covered, not skipped.
     *
     * <p>Both ends of the range are load-bearing. A 10-degree aim against a 2 deg/tick cap is
     * among the SMALLEST slews the delay could ever be charged on; 150 degrees is the largest
     * amplitude this project's saccadic law is asked about, and it is where a 4-8 tick pause is
     * largest in absolute terms.
     */
    private static final double[][] SLEWS = {
        {10, 2}, {45, 2}, {90, 2}, {150, 2},
        {10, 5}, {45, 5}, {90, 5}, {150, 5},
        {45, 40}, {90, 40}, {150, 40},
    };

    /**
     * The subset whose profile opening clears one mouse count, so the crosshair VALUE must move on
     * tick 1 rather than merely begin writing. Measured, not assumed: the rest open sub-lattice.
     */
    private static final double[][] CLEARS_LATTICE = {
        {10, 2}, {10, 5}, {45, 5}, {45, 40}, {90, 40}, {150, 40},
    };

    /**
     * The subset that opens BELOW one mouse count per tick, so a few ticks pass with no visible
     * movement. This is the lattice, and the third test is what stops it being read as a delay.
     */
    private static final double[][] SUB_LATTICE = {
        {45, 2}, {90, 2}, {150, 2}, {90, 5}, {150, 5},
    };

    /** One aim, observed over however many ticks it took to first move the crosshair. */
    private static final class Ticked {
        boolean tickOneWasSnap;
        int firstValueChange;
        String firstMessage;
    }

    private static double arrivalLimit(double arcDeg, double cap) {
        return Math.min(cap, LookController.mainSequencePeakDegPerTick(arcDeg));
    }

    private static Ticked observe(double arcDeg, double cap) {
        FakeActuator act = new FakeActuator();
        act.yaw = 0f;
        act.pitch = 0f;
        LookController look = new LookController(LookIntent.set((float) arcDeg, 0f, (float) cap));
        Ticked t = new Ticked();
        t.firstMessage = look.tick(act).message();
        t.tickOneWasSnap = act.lastSetWasSnap;
        t.firstValueChange = Math.abs(act.yaw) > 1e-9 ? 1 : -1;
        if (t.firstValueChange < 0) {
            float previous = act.yaw;
            for (int tick = 2; tick <= 400; tick++) {
                previous = act.yaw;
                look.tick(act);
                if (Math.abs(act.yaw - previous) > 1e-9) {
                    t.firstValueChange = tick;
                    break;
                }
                if (look.isDone()) {
                    break;
                }
            }
        }
        return t;
    }

    private static String pair(double[] p) {
        return p[0] + "-degree aim at a " + p[1] + " deg/tick cap";
    }

    /**
     * THE CLAIM: a look that traverses real ground begins spending its arc on the intent's own
     * tick, and the tick-one write is the profile's rather than a hold.
     *
     * <p>This is the test that goes red when the delay comes back, and it cannot miss: a charged
     * delay writes {@code setRotation} with the current angle on every tick of its run, so tick
     * one is a snap in all 200 runs, whichever end of 4..8 the draw takes.
     */
    @Test
    public void aSlewingAimEntersTheProfileOnItsFirstTick() {
        for (double[] p : SLEWS) {
            // Precondition, so this cannot pass by being vacuous. The delay was only ever charged
            // on an aim that did NOT land within the arrival limit, so an arc the cap or the main
            // sequence swallows in one tick must not be in this set — "began on tick one" would
            // then be trivially true because there was never an arc to spend. The limit is
            // min(cap, main sequence), NOT the law alone: a tight cap makes almost every aim a
            // slewer however small the arc is.
            assertTrue("precondition: " + pair(p) + " must SLEW, or this test says nothing;"
                            + " arrival limit is " + arrivalLimit(p[0], p[1]),
                    p[0] > arrivalLimit(p[0], p[1]));
            for (int run = 0; run < 200; run++) {
                Ticked t = observe(p[0], p[1]);
                assertFalse(pair(p) + " HELD the rotation on its first tick (run " + run
                                + " of 200), writing the current angle back with setRotation:"
                                + " tick one said \"" + t.firstMessage + "\". A hold before the"
                                + " first write IS the reaction delay that was removed: 200-400"
                                + " ms of task time bought for no observable property, which"
                                + " ADR-0005 §1 bans. If one is being re-added, name what it"
                                + " buys and measure it, and pin it here the way NavController's"
                                + " delay is pinned.",
                        t.tickOneWasSnap);
            }
        }
    }

    /**
     * Where the profile's opening step is at least one mouse count, the crosshair must MOVE on
     * tick 1 — not merely begin a write.
     *
     * <p>Restricted to the arcs whose opening increment clears the lattice, and that restriction
     * is the point rather than a convenience: the profile leaves from rest, so a long aim against
     * a tight cap opens below {@link LookController#MOUSE_GCD_DEG} and quantises to no change at
     * all. Those are {@link #aLongAimOpensSlowlyBecauseOfTheLatticeNotAPause}. Demanding a
     * tick-one value change everywhere would be demanding a faster-than-rest opening step, i.e.
     * contradicting the very hand shape this channel exists to produce.
     */
    @Test
    public void whereTheOpeningStepClearsTheLatticeTheCrosshairMovesOnTickOne() {
        for (double[] p : CLEARS_LATTICE) {
            assertTrue("precondition: " + pair(p) + " must SLEW",
                    p[0] > arrivalLimit(p[0], p[1]));
            for (int run = 0; run < 200; run++) {
                Ticked t = observe(p[0], p[1]);
                assertEquals(pair(p) + " first moved the crosshair on tick "
                                + t.firstValueChange + " (run " + run + " of 200); its opening"
                                + " step clears one mouse count, so it must move on the intent's"
                                + " own tick",
                        1, t.firstValueChange);
            }
        }
    }

    /**
     * The residual cases — a few ticks with no visible movement — are the LATTICE, and this test
     * is what stops anyone reading them as evidence that a delay survived.
     *
     * <p>A long aim against a tight cap spends its opening ticks accumulating less than one mouse
     * count each, and the write quantises each to zero. The distinguishing facts are that the
     * tick-one write is an INTERP (the profile is running, not a hold) and that the wait is
     * bounded by the lattice rather than by a drawn number. A restored delay fails the first of
     * those on tick one.
     */
    @Test
    public void aLongAimOpensSlowlyBecauseOfTheLatticeNotAPause() {
        for (double[] p : SUB_LATTICE) {
            for (int run = 0; run < 200; run++) {
                Ticked t = observe(p[0], p[1]);
                assertFalse(pair(p) + " held the rotation on tick 1 (run " + run + " of 200):"
                                + " the write was a setRotation hold, which is a charged delay,"
                                + " not a curve opening",
                        t.tickOneWasSnap);
                assertTrue(pair(p) + " took " + t.firstValueChange + " ticks to move the"
                                + " crosshair, which is not a lattice-bounded opening: 150"
                                + " degrees at 2 deg/tick accumulates one 0.15-degree mouse"
                                + " count within a handful of ticks and no longer",
                        t.firstValueChange > 0 && t.firstValueChange <= 6);
            }
        }
    }

    /**
     * The pause had its own outcome string, and the string is part of what a caller reads.
     *
     * <p>「reacting, aim at yaw=... (tick n/m, first move at k)」 told a caller the aim had not
     * started. Deleting the path deleted the string with it. This asserts the absence rather than
     * only a changed tick count, because a future editor could reintroduce a pause under
     * different wording and satisfy a tick-count test by changing what it counts.
     */
    @Test
    public void noOutcomeOnAnyChannelReportsAReactionPause() {
        FakeActuator act = new FakeActuator();
        act.yaw = 0f;
        act.pitch = 0f;
        LookController look = new LookController(LookIntent.set(150f, 0f, 5f));
        for (int tick = 1; tick <= 400 && !look.isDone(); tick++) {
            String message = look.tick(act).message();
            assertFalse("tick " + tick + " reported a reaction pause: " + message,
                    message.contains("reacting"));
            assertFalse("tick " + tick + " promised a first move at some later tick: " + message,
                    message.contains("first move at"));
        }
        assertTrue("the aim must have finished, or this did not see every tick", look.isDone());
    }

    /**
     * The micro-correction path, which the removal must NOT have changed.
     *
     * <p>This is the case the old {@code capLanded} gate existed for: an aim the caller can
     * complete in one tick is not a movement a hand decides to make, and a 2-degree wrap from
     * 179 to -179 used to land in one step. Because that aim NEVER paid the 4-8 ticks — the gate
     * excluded it — deleting the delay cannot move it. So the honest statement is not "the gate
     * was kept" but "the gate went with the delay and this observable is identical either way":
     * one tick, terminal, {@code ok}, snapped onto the mouse lattice.
     */
    @Test
    public void theMicroCorrectionStillLandsOnOneTickAndPaidNothing() {
        FakeActuator wrapped = new FakeActuator();
        wrapped.yaw = 179f;
        LookController wrapLook = new LookController(LookIntent.set(-179f, 0f, 5f));
        ActOutcome wrappedOut = wrapLook.tick(wrapped);
        assertTrue("the 2-degree short arc across the wrap must still land in ONE tick, and did"
                        + " not: " + wrappedOut.message(),
                wrappedOut.terminal() && wrappedOut.ok());
        assertEquals(1, wrapLook.ticks());
        assertEquals("on the nearest mouse-lattice position to the target",
                -179f, wrapped.yaw, 0.15);

        // The micro-corrections the gate let through at a loose cap, including the 10-degree case
        // excluded from SLEWS above, and 25 -- the last degree that still arrives rather than
        // slews at this cap.
        for (double arc : new double[] {2, 5, 8, 10, 25}) {
            FakeActuator act = new FakeActuator();
            act.yaw = 0f;
            act.pitch = 0f;
            LookController look = new LookController(LookIntent.set((float) arc, 0f, 40f));
            ActOutcome out = look.tick(act);
            assertTrue("a " + arc + "-degree aim must land on its first tick, and did not: "
                    + out.message(), out.terminal() && out.ok());
            assertEquals(1, look.ticks());
        }

        // And the real boundary between the two classes, which is not a round number and is the
        // main sequence's own: at a 40 deg/tick cap the arrival limit saturates at 25.98
        // deg/tick, so 25 degrees still lands on tick 1 and 26 starts slewing. Asserting both
        // sides is what makes "the micro-correction path is untouched" a statement about the
        // arrival limit rather than about the arcs I happened to pick.
        FakeActuator slewer = new FakeActuator();
        slewer.yaw = 0f;
        slewer.pitch = 0f;
        LookController slewLook = new LookController(LookIntent.set(26f, 0f, 40f));
        ActOutcome slewOut = slewLook.tick(slewer);
        assertFalse("26 degrees at a 40 deg/tick cap is outside the arrival limit and must slew",
                slewOut.terminal());
        assertEquals("and it must have written its first step on that same tick 1",
                26f, slewer.yaw, 15f);
        assertEquals("with the profile running rather than one snap having landed it",
                1, slewLook.ticks());
    }
}
