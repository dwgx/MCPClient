package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.event.events.TickEvent;

/**
 * Four act-runtime decisions that survived mutation against the whole suite, each driven with the
 * one input the mutant and the original disagree on.
 *
 * <table>
 *   <tr><th>mutation</th><th>consequence</th></tr>
 *   <tr><td>{@code ActRuntime.effective}: {@code return axes} becomes
 *       {@code return LocomotionAxes.NEUTRAL}</td>
 *       <td>nav locomotion dies silently -- the slot reports a walk in progress and
 *       {@code act_status} says ACTIVE while the player never moves an inch</td></tr>
 *   <tr><td>{@code MoveApplier}: {@code stillTicks >= STUCK_TICKS && against} becomes {@code ||}</td>
 *       <td>a player falling down a shaft or waiting on a chunk is reported to the AI as stuck
 *       against a wall, and so is one sliding productively along a surface</td></tr>
 *   <tr><td>{@code MoveApplier}: {@code boundTo != current.intent()} becomes
 *       {@code boundTo == null}</td>
 *       <td>a replacement intent inherits the previous one's origin and still-ticks: it is credited
 *       with distance it did not travel and can report a jam on its first tick</td></tr>
 *   <tr><td>{@code ActRuntime.cancel}: {@code cur.isLive()} becomes
 *       {@code cur.intent() != null}</td>
 *       <td>{@code act_cancel} claims to have torn down a finished intent and never frees the slot,
 *       so {@code act_status} reports a stale intent and "cancel requested" forever</td></tr>
 * </table>
 *
 * <p><b>The shape they share.</b> Every one of them is a two-operand decision whose tests only ever
 * presented inputs where both operands agreed. The nav read side of {@code effective()} is never
 * reached because every existing reader holds a {@code MoveIntent} and takes the other branch;
 * the stuck conjunction is only driven with contact AND stillness together, or through the duration
 * branch that returns before it; the rebind is only driven on a fresh applier where
 * {@code boundTo} is already null; and cancel's else branch is only reached with a null intent,
 * never with a terminal one. So the tests below drive each operand alone.
 */
public class ActRuntimeGatesArePinnedAtTheirBoundaryTest {

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

    private void tick() {
        loop.onTick(new TickEvent(clock.advance()));
    }

    private String moveMessage() {
        return runtime.record(ActSlot.MOVE).message();
    }

    /**
     * A NAV intent's axes must reach vanilla input through the value the applier publishes.
     *
     * <p>This is the only source there is. A {@code MoveIntent} IS its axes, so the intent branch of
     * {@code effective()} can read them back; a {@code NavIntent} carries none, and
     * {@code NavController} recomputes them from the live position every tick. Nothing else feeds
     * {@code ActMovementInput}, so a read that answers NEUTRAL here leaves the player standing still
     * while the slot truthfully reports a walk in progress -- the failure mode where the AI is told
     * it is navigating and the world disagrees.
     *
     * <p>Driven diagonally on purpose: a target straight ahead would leave strafe at zero, which
     * NEUTRAL also produces, and the test would only pin one of the two axes.
     */
    @Test
    public void navAxesReachVanillaInputThroughThePublishedValue() {
        FakeActuator act = new FakeActuator();
        act.setPosition(0, 64, 0);
        act.yaw = 0f;
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier(act, runtime));

        assertEquals("premise: nothing published yet, so the read starts neutral",
                0.0f, runtime.moveForward(), 1e-6);

        clock.advance();
        // (3, 4) at 5 blocks out, so the unit direction is (0.6, 0.8) and at yaw 0 the axes come out
        // forward 0.8 / strafe 0.6 -- two different non-zero values, one per axis.
        runtime.submitNav(new NavIntent(3, 64, 4, 0));
        tick();

        assertEquals("premise: the nav slot is walking", ActPhase.ACTIVE,
                runtime.record(ActSlot.MOVE).phase());
        assertTrue("a NavIntent drives the same input override a MoveIntent does",
                runtime.moveActive());
        assertEquals("the forward axis NavController computed must be what ActMovementInput reads; "
                + "a neutral read here means the AI is told it is navigating while the player never "
                + "moves an inch", 0.8f, runtime.moveForward(), 1e-6);
        assertEquals("and the strafe axis with it, or the bot walks the wrong side of its heading",
                0.6f, runtime.moveStrafe(), 1e-6);

        // Arrive: the applier publishes null on a terminal outcome, so the read reverts to neutral.
        act.setPosition(3, 64, 4);
        tick();

        assertEquals("premise: arrival completes the slot", ActPhase.COMPLETE,
                runtime.record(ActSlot.MOVE).phase());
        assertEquals("a finished nav must stop forcing an axis, or the published value latches and "
                + "the player keeps walking past the destination", 0.0f, runtime.moveForward(), 1e-6);
        assertFalse("and the override must lift so the human's keyboard works again -- a COMPLETE "
                + "slot still reporting active is the one state act_cancel cannot rescue",
                runtime.moveActive());
    }

    /**
     * Contact alone is not a jam: it takes STUCK_TICKS of stillness as well.
     *
     * <p>Driven at exactly one still-tick below the threshold and then at the threshold, so the count
     * is pinned rather than bounded. The first two ticks are the inputs the conjunction and the
     * disjunction disagree on -- a player pressed against a wall for one tick is a player who just
     * arrived at it, and calling that a jam would have the AI turn around before it had walked.
     */
    @Test
    public void contactAloneIsNotAJamUntilThePlayerHasBeenStillForThreeTicks() {
        FakeActuator act = new FakeActuator();
        act.setPosition(0, 64, 0);
        act.collidedHorizontally = true;
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier(act));

        clock.advance();
        // Unbounded, so the duration branch cannot return before the jam check is evaluated.
        runtime.submitMove(new MoveIntent(1f, 0f, false, false, false, 0));

        tick(); // first applied tick: the origin is captured here, so this tick counts as still
        assertEquals("one tick of contact is a player who just reached the wall, not a jam",
                "moving (tick 1), moved 0.00 blocks", moveMessage());

        tick(); // still-ticks 2: one short of the threshold
        assertEquals("two still ticks against a wall is still short of a jam, and the count in the "
                + "message is what the AI decides on -- reporting it early makes the bot give up on "
                + "a wall it has not finished walking into",
                "moving (tick 2), moved 0.00 blocks", moveMessage());

        tick(); // still-ticks 3: the threshold, so the jam is real
        assertTrue("three still ticks WITH contact is the jam this branch exists to report, and it "
                + "must name the count so the caller can tell a fresh wall from a long one. Got: "
                + moveMessage(),
                moveMessage().contains("stuck against a wall for 3 ticks"));
    }

    /**
     * Stillness alone is not a jam either: without contact there is no wall to be stuck against.
     *
     * <p>The other operand, driven alone. A player can be motionless for reasons that have nothing to
     * do with a wall -- falling down a shaft, waiting on a chunk, held by a mob -- and telling the AI
     * "stuck against a wall" there sends it turning and jumping away from an obstacle that is not
     * where it was told. Held well past the threshold so the still-ticks operand is unambiguously
     * satisfied and only the contact operand keeps the verdict false.
     */
    @Test
    public void aStillPlayerTouchingNothingIsNotReportedStuckHoweverLongItStandsThere() {
        FakeActuator act = new FakeActuator();
        act.setPosition(0, 64, 0);
        act.collidedHorizontally = false;
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier(act));

        clock.advance();
        runtime.submitMove(new MoveIntent(1f, 0f, false, false, false, 0));

        for (int i = 0; i < 5; i++) {
            tick();
        }
        assertEquals("five ticks of going nowhere with nothing to be stuck against must still read "
                + "as moving: naming a wall the player is not touching sends the AI evading an "
                + "obstacle that is not there",
                "moving (tick 5), moved 0.00 blocks", moveMessage());

        // The same fixture DOES report a jam once the missing operand arrives, so the assertion above
        // is not satisfied by an applier that never reports one.
        act.collidedHorizontally = true;
        tick();
        assertTrue("adding contact to the same standstill is a real jam and must be reported. Got: "
                + moveMessage(),
                moveMessage().contains("stuck against a wall for 6 ticks"));
    }

    /**
     * Replacing a live MOVE intent restarts the displacement measurement from where the player is.
     *
     * <p>The input no existing test presents: a SECOND intent handed to an applier that is already
     * driving one. Every displacement test builds a fresh applier, so the binding is only ever
     * exercised from null, where "has the intent changed" and "is nothing bound" agree. Displacement
     * is per-intent, and an applier that keeps the old origin credits the new intent with ground the
     * previous one covered -- the caller asks "did this move accomplish anything" and is answered
     * about a different move.
     */
    @Test
    public void replacingALiveMoveIntentRestartsTheDisplacementMeasurement() {
        FakeActuator act = new FakeActuator();
        act.setPosition(0, 64, 0);
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier(act));

        clock.advance();
        runtime.submitMove(new MoveIntent(1f, 0f, false, false, false, 0));

        // The game moves the player, then the applier observes: three ticks at half a block, with the
        // origin captured on the first, leaves the first intent credited with a metre.
        for (int i = 0; i < 3; i++) {
            act.nudge(0.5, 0, 0);
            tick();
        }
        assertEquals("premise: the first intent has a metre of travel on its books",
                "moving (tick 3), moved 1.00 blocks", moveMessage());

        // A different intent object, which is what a fresh act_move submit produces.
        runtime.submitMove(new MoveIntent(-1f, 0f, false, false, false, 0));
        act.nudge(0.5, 0, 0);
        tick();

        assertEquals("a replacement intent must be measured from where the player was when IT "
                + "started: inheriting the previous origin credits this move with 1.50 blocks it "
                + "never walked, and a caller deciding whether to try another route reads that as "
                + "success",
                "moving (tick 1), moved 0.00 blocks", moveMessage());

        // And the restarted measurement is live rather than frozen at zero.
        act.nudge(0.5, 0, 0);
        tick();
        assertEquals("the new intent's own travel must then accumulate, or the fix would be an "
                + "applier that reports nothing rather than one that reports the right thing",
                "moving (tick 2), moved 0.50 blocks", moveMessage());
    }

    /**
     * Cancelling a finished slot frees it and says nothing was torn down.
     *
     * <p>A terminal record still carries its intent -- {@code withPhase} keeps it so the caller can
     * read the outcome -- so "is there an intent" and "is there a live intent" differ on exactly this
     * record, and no test had ever cancelled one. Treating it as live has {@code act_cancel} report
     * that it tore down a running intent that had already finished, and leaves the slot occupied by a
     * stale intent with "cancel requested" as its status, which no later tick will ever clear because
     * the tick loop skips terminal slots.
     */
    @Test
    public void cancellingAFinishedSlotFreesItAndReportsThatNothingWasTornDown() {
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier());
        clock.advance();
        runtime.submitMove(new MoveIntent(1f, 0f, false, false, false, 1));
        tick();

        SlotRecord finished = runtime.record(ActSlot.MOVE);
        assertEquals("premise: a duration-1 intent completes on its first active tick",
                ActPhase.COMPLETE, finished.phase());
        assertNotNull("premise: and a terminal record keeps its intent, which is what makes the two "
                + "cancel conditions differ here", finished.intent());

        assertFalse("cancelling a slot that already finished tore nothing down, and act_cancel "
                + "reports exactly what this returns -- claiming a running intent was stopped tells "
                + "the AI it interrupted work that had already completed",
                runtime.cancel(ActSlot.MOVE));

        SlotRecord after = runtime.record(ActSlot.MOVE);
        assertNull("and the slot must be freed rather than flagged: a terminal slot keeps its stale "
                + "intent forever, because the tick loop skips terminal slots and nothing will ever "
                + "clear the flag", after.intent());
        assertEquals("a freed slot is IDLE, which is how act_status says the channel is available",
                ActPhase.IDLE, after.phase());
        assertEquals("with the empty slot's own message, not a cancellation that never happened",
                "idle", after.message());
        assertFalse("act_status must stop advertising an intent on a freed channel",
                runtime.status().slots().get(ActSlot.MOVE.ordinal()).hasIntent());

        // The live case, so the assertions above are not satisfied by a cancel that always declines.
        runtime.submitLook(LookIntent.set(10f, 0f, 0f));
        assertTrue("cancelling a live intent must still report that it was flagged",
                runtime.cancel(ActSlot.LOOK));
        SlotRecord look = runtime.record(ActSlot.LOOK);
        assertNotNull("and must keep the intent, since the applier needs one more tick to tear it "
                + "down cleanly", look.intent());
        assertTrue("with the flag set for that tick to find", look.cancelRequested());
    }

    /**
     * {@code act_cancel} over every slot counts only the channels that were actually running.
     *
     * <p>The count is what {@code act_cancel} turns into its "cancelled" array, so it is the number
     * the AI reads back to learn what it just interrupted. Set up so the three slots are in three
     * different states -- finished, running, never used -- and the answer is one. Miscounting the
     * finished slot inflates it to two and reports an interruption that did not occur.
     */
    @Test
    public void cancelAllCountsOnlyTheChannelsThatWereActuallyRunning() {
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier());
        clock.advance();
        runtime.submitMove(new MoveIntent(1f, 0f, false, false, false, 1));
        tick();
        assertEquals("premise: MOVE has finished", ActPhase.COMPLETE,
                runtime.record(ActSlot.MOVE).phase());

        runtime.submitLook(LookIntent.set(5f, 0f, 0f));
        // INTERACT is left untouched, so all three slot states are represented.

        assertEquals("exactly one channel was running, and that count is the 'cancelled' array the "
                + "AI reads to learn what it interrupted -- counting a slot that had already "
                + "completed reports work torn down that finished on its own",
                1, runtime.cancelAll());
    }
}
