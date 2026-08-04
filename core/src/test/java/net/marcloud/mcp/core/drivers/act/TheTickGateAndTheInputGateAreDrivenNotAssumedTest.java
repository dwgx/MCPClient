package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.event.events.TickEvent;

/**
 * The two gates that decide when an intent applies and when the player's keyboard comes back.
 *
 * <p>Both survived mutation against all 957 tests:
 *
 * <table>
 *   <tr><th>mutation</th><th>consequence</th></tr>
 *   <tr><td>{@code ActTickLoop}: {@code tick < rec.effectiveTick()} becomes
 *       {@code tick < rec.submittedTick()}</td>
 *       <td>an intent submitted from a worker thread mid-tick applies during the tick it arrived --
 *       the half-applied-within-a-tick race the whole three-channel design exists to prevent</td></tr>
 *   <tr><td>{@code ActRuntime.moveActive}: {@code phase == ACTIVE} becomes {@code phase != IDLE}</td>
 *       <td>a COMPLETE/FAILED/CANCELLED slot still reports active, so the vanilla-input override
 *       never lifts: the player walks forever on a finished intent and the human's keyboard stays
 *       dead</td></tr>
 * </table>
 *
 * <p><b>Why the existing tests could not see either.</b> Two different shapes of the same mistake --
 * asserting the state you want and never the state you are excluding.
 *
 * <ul>
 *   <li>The tick gate is asserted only on the SUBMIT side ({@code effectiveTick == submittedTick+1}),
 *       and every test that drives the loop advances the clock BEFORE ticking. So the loop is never
 *       invoked with a tick id equal to a record's {@code submittedTick} -- the one input where the
 *       two expressions differ. {@code intentDoesNotApplyOnTheTickItWasSubmitted} has exactly the
 *       right name and asserts that the applier ran at tick 2; it never asserts it did NOT run at
 *       tick 1.</li>
 *   <li>{@code moveActive} is asserted false for a freshly-submitted IDLE record and true once
 *       ACTIVE. IDLE is the one phase the mutant also reports false for, so both survive. No test
 *       asked what it returns after a terminal phase, which is the only moment that decides whether
 *       control returns to the player.</li>
 * </ul>
 */
public class TheTickGateAndTheInputGateAreDrivenNotAssumedTest {

    private GameClock clock;
    private ActRuntime runtime;
    private ActTickLoop loop;

    @Before
    public void setUp() {
        clock = new GameClock();
        clock.reset();
        runtime = new ActRuntime(clock);
        loop = new ActTickLoop(runtime);
    }

    /**
     * The submission tick itself must not apply the intent.
     *
     * <p>This is the input the existing coverage never presents: ticking with the SAME id the intent
     * was stamped with. A worker thread submits partway through a game tick, and applying it in that
     * tick means half the frame ran under the old intent and half under the new one -- which is the
     * one thing the effective-tick convention exists to make impossible.
     */
    @Test
    public void anIntentSubmittedDuringATickIsNotAppliedInThatSameTick() {
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier());
        long now = clock.advance(); // now = 1
        SlotRecord submitted = runtime.submitMove(new MoveIntent(1f, 0f, false, false, false, 0));
        assertEquals("premise: the intent is stamped with the current tick", now,
                submitted.submittedTick());
        assertEquals("premise: and becomes eligible one tick later", now + 1,
                submitted.effectiveTick());

        // Re-deliver the SAME tick the submission happened in, as a worker-thread submit racing the
        // game thread would produce. Not clock.advance(): that is what every existing test does, and
        // it steps straight past the only tick where the gate can be wrong.
        loop.onTick(new TickEvent(now));

        SlotRecord after = runtime.record(ActSlot.MOVE);
        assertEquals("the intent must still be IDLE on its own submission tick: applying it here is "
                + "the half-applied-within-a-tick race the effective-tick convention prevents",
                ActPhase.IDLE, after.phase());
        assertEquals("and it must not have been stepped", 0, after.ticksActive());
        assertFalse("nor may it drive vanilla input yet", runtime.moveActive());

        // And the following tick does apply it, so the assertion above is not satisfied by a loop
        // that never applies anything.
        loop.onTick(new TickEvent(clock.advance()));
        assertEquals("the effective tick applies it", ActPhase.ACTIVE,
                runtime.record(ActSlot.MOVE).phase());
    }

    /**
     * A cancel arriving before the intent ever started ends it without teardown.
     *
     * <p>Included because the mutation also makes this branch unreachable -- with the gate keyed to
     * {@code submittedTick} the loop is always past it, so the cancel-before-start path can never
     * run and the cancel would instead go through the applier as though the intent had started.
     */
    @Test
    public void aCancelOnTheSubmissionTickEndsWithoutRunningTheApplier() {
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier());
        long now = clock.advance();
        runtime.submitMove(new MoveIntent(1f, 0f, false, false, false, 0));
        runtime.cancel(ActSlot.MOVE);

        loop.onTick(new TickEvent(now));

        SlotRecord r = runtime.record(ActSlot.MOVE);
        assertEquals("cancelled before it was ever eligible", ActPhase.CANCELLED, r.phase());
        assertEquals("and never stepped, so there was nothing to tear down", 0, r.ticksActive());
        assertTrue("the message must say so, since 'cancelled' alone does not distinguish a slot "
                + "that ran from one that never started: " + r.message(),
                r.message().contains("before start"));
    }

    /**
     * The vanilla-input override must lift the moment the slot stops being ACTIVE.
     *
     * <p>This is what returns the keyboard to the human. While {@code moveActive()} is true,
     * {@code ActMovementInput} overwrites vanilla's four movement fields every tick, so a gate that
     * stays true after a terminal phase means the player keeps walking on a finished intent with no
     * way to stop -- and {@code act_cancel} cannot help, because the slot is already terminal.
     */
    @Test
    public void aCompletedMoveStopsDrivingVanillaInput() {
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier());
        clock.advance();
        runtime.submitMove(new MoveIntent(1f, 0f, false, false, false, 1));

        loop.onTick(new TickEvent(clock.advance()));
        SlotRecord done = runtime.record(ActSlot.MOVE);
        assertEquals("premise: a duration-1 intent completes on its first active tick",
                ActPhase.COMPLETE, done.phase());

        assertFalse("a COMPLETE slot must not keep driving vanilla input: while moveActive() is "
                + "true ActMovementInput overwrites vanilla's movement fields every tick, so the "
                + "player would walk forever on a finished intent and the human's keyboard would "
                + "stay dead with act_cancel unable to help",
                runtime.moveActive());
    }

    /** The same for the terminal phases a failure produces, not only for a clean completion. */
    @Test
    public void aFailedOrCancelledMoveAlsoStopsDrivingVanillaInput() {
        // No applier registered: the loop fails the slot honestly rather than spinning IDLE.
        clock.advance();
        runtime.submitMove(new MoveIntent(1f, 0f, false, false, false, 0));
        loop.onTick(new TickEvent(clock.advance()));
        assertEquals("premise: a live intent with no applier fails rather than spinning",
                ActPhase.FAILED, runtime.record(ActSlot.MOVE).phase());
        assertFalse("a FAILED slot must not hold the input override open -- this is the worst of "
                + "the three, since nothing completed and nothing will step it again",
                runtime.moveActive());

        setUp();
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier());
        clock.advance();
        runtime.submitMove(new MoveIntent(1f, 0f, false, false, false, 0));
        loop.onTick(new TickEvent(clock.advance()));
        assertTrue("premise: an unbounded intent stays active", runtime.moveActive());
        runtime.cancel(ActSlot.MOVE);
        loop.onTick(new TickEvent(clock.advance()));
        assertEquals("premise: the cancel lands", ActPhase.CANCELLED,
                runtime.record(ActSlot.MOVE).phase());
        assertFalse("and a CANCELLED slot releases the override, which is the whole point of "
                + "act_cancel", runtime.moveActive());
    }
}
