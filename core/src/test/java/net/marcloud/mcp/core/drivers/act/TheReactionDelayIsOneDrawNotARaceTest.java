package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The reaction delay must be ONE draw per intent, and the wait must be bounded by it.
 *
 * <p>Found by reviewing the uncommitted work, and it is the cause of a test that had been failing
 * about one run in three. The reacting branch of {@code NavController.tick} calls {@code stop()},
 * and {@code stop()} reset {@code reactionTicks} to {@code -1} — so the delay was redrawn on
 * <i>every reacting tick</i> and raced the monotonically increasing tick counter. The walk left
 * when the counter happened to beat a fresh {@code 4 + rand(5)} draw. That is not "stand still
 * for 4-8 ticks": the tail is unbounded, and a caller with a 20-tick budget could lose most of it
 * to standing still.
 *
 * <p>The symptom was misread twice before it was found: the test that noticed it had its wait
 * bound widened from 8 to 32, which made the flake disappear and the bug stay. That is the failure
 * this file exists to make impossible to repeat — a test that fails intermittently must be
 * treated as a defect report about the code, not as a test to be relaxed.
 *
 * <p>The reset was also pointless on its own terms: a new intent already receives a new
 * {@code NavController} ({@code RouteExecutor} creates one per move), so the field is already
 * {@code -1} at the start of every walk.
 */
public final class TheReactionDelayIsOneDrawNotARaceTest {

    /**
     * The band the controller documents: {@code 4 + rand(5)}, i.e. a delay of 4..8 ticks.
     *
     * <p>Plus one, because the branch is {@code ticks <= delay}: the player is still reacting ON
     * the tick equal to the delay and first moves on the one after. The wait a caller observes is
     * therefore {@code delay + 1}, and a bound written as 8 would fail against correct code.
     */
    private static final int MAX_REACTION_TICKS = 8;
    private static final int FIRST_KEY_BY_TICK = MAX_REACTION_TICKS + 1;

    @Test
    public void theDrawIsStableAcrossTheTicksSpentWaiting() {
        // The property, stated exactly: one draw per intent. A caller that re-draws while the
        // player stands still is not paying the delay it sampled, and the value it reports in its
        // own message ("first step at N") would then be a number it is not going to honour.
        //
        // Driven through the APPLIER rather than the controller, because the applier is where the
        // draw lives now. It used to be a NavController field, and that is precisely the defect:
        // RouteExecutor builds a fresh controller per move, so a per-instance draw was a per-BLOCK
        // draw. See MoveApplier.reactionTicks for why the applier owns it.
        for (int run = 0; run < 300; run++) {
            FakeActuator act = new FakeActuator();
            act.setPosition(0, 64, 0);
            act.yaw = 0f;
            ActRuntime runtime = new ActRuntime();
            MoveApplier applier = new MoveApplier(act, runtime);
            SlotRecord rec = SlotRecord.submitted(new NavIntent(20, 64, 0, 200), 0L, 1L, "s");

            // Read the draw AFTER the first tick, not before. The applier draws when it BINDS the
            // intent, so a value sampled before the first apply() is a throwaway that the bind
            // then replaces -- and comparing a throwaway against the real one would fail for a
            // reason that has nothing to do with the property under test. This was a real flaw in
            // the first version of this rewrite, and the "reset does not clear" mutation is what
            // exposed it.
            rec = applier.apply(rec.stampTick(1));
            int drawn = applier.drawnReactionDelay();

            for (int tick = 2; tick <= drawn + 1; tick++) {
                rec = applier.apply(rec.stampTick(tick));
                assertEquals("the draw changed from " + drawn + " to " + applier.drawnReactionDelay()
                                + " on tick " + tick + " of the wait; the delay must be sampled "
                                + "once per intent, not re-rolled while the player stands there",
                        drawn, applier.drawnReactionDelay());
            }
        }
    }

    @Test
    public void theWaitIsTheDelayItDrew() {
        // And the observable consequence: the first key lands on the tick after the drawn delay,
        // every time, for every draw in the band. This is what makes the number in the outcome
        // message true.
        for (int run = 0; run < 300; run++) {
            FakeActuator act = new FakeActuator();
            act.setPosition(0, 64, 0);
            act.yaw = 0f;
            ActRuntime runtime = new ActRuntime();
            MoveApplier applier = new MoveApplier(act, runtime);
            SlotRecord rec = SlotRecord.submitted(new NavIntent(20, 64, 0, 200), 0L, 1L, "s");

            int drawn = -1;
            int firstKey = Integer.MAX_VALUE;
            for (int tick = 1; tick <= 40 && firstKey == Integer.MAX_VALUE; tick++) {
                rec = applier.apply(rec.stampTick(tick));
                // The bind happens on tick 1, which is also the first of the wait, so the draw is
                // read after it -- see the note in the test above.
                if (tick == 1) {
                    drawn = applier.drawnReactionDelay();
                }
                if (runtime.moveForward() != 0f || runtime.moveStrafe() != 0f || runtime.jump()) {
                    firstKey = tick;
                }
            }
            assertEquals("drew " + drawn + " but first moved on tick " + firstKey
                            + "; the applier tells the caller 'first step at " + drawn
                            + "', so that message has to be true", drawn + 1, firstKey);
        }
    }

    /**
     * Consecutive walks must not share one drawn delay.
     *
     * <p>The property that makes the delay a distribution rather than a constant, and the one a
     * reused draw would break silently. If the second walk of a session were handed the first
     * walk's number, a caller measuring onset latency across many walks would find the SAME
     * value recurring -- and "the agent's reaction time is a constant" is a louder tell than any
     * single number being wrong.
     *
     * <p>Stated as "at least one pair differs" rather than "every pair differs", because two
     * independent draws from a five-value band collide one time in five and a test demanding
     * otherwise would fail on the law of large numbers rather than on the defect. Over 200 pairs
     * the chance that all of them collide is 5^-199.
     *
     * <p>Written because a mutation exposed that nothing covered it: clearing the countdown but
     * not the draw leaves every walk still paying a pause, so the "does it pause again" test
     * stays green while the draw is silently reused.
     */
    @Test
    public void twoWalksInARowDoNotShareOneDrawnDelay() {
        int pairs = 200;
        int identical = 0;

        for (int run = 0; run < pairs; run++) {
            FakeActuator act = new FakeActuator();
            act.setPosition(0, 64, 0);
            act.yaw = 0f;
            ActRuntime runtime = new ActRuntime();
            MoveApplier applier = new MoveApplier(act, runtime);

            runtime.registerApplier(ActSlot.MOVE, applier);
            runtime.submitNav(new NavIntent(20, 64, 0, 200));
            SlotRecord rec = runtime.record(ActSlot.MOVE);
            rec = applier.apply(rec.stampTick(1));
            int first = applier.drawnReactionDelay();

            // Run the first walk out so the applier resets, then submit a second one.
            for (int tick = 2; tick < 200 && !rec.phase().isTerminal(); tick++) {
                rec = applier.apply(rec.stampTick(tick));
            }
            runtime.submitNav(new NavIntent(20, 64, 0, 200));
            rec = applier.apply(runtime.record(ActSlot.MOVE).stampTick(1));
            int second = applier.drawnReactionDelay();

            if (first == second) {
                identical++;
            }
        }

        assertTrue("across " + pairs + " pairs of consecutive walks, " + identical + " drew the "
                        + "SAME delay twice. The draw is supposed to be sampled per walk, so that "
                        + "the onset latency a caller measures is a distribution and not a "
                        + "constant; identical pairs should be about " + pairs + "/5 = "
                        + (pairs / 5) + " by chance, not " + identical,
                identical < pairs / 2);
    }
}
