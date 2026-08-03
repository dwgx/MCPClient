package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The MOVE slot must report how far the player went, not only how long the key was held.
 *
 * <p><b>The defect.</b> {@code MoveApplier} reports {@code "moving (tick N/M)"}. That is a
 * statement about the applier's own bookkeeping, and it is indistinguishable between a player
 * crossing open ground and a player pressed against a wall -- both count ticks at the same rate.
 * Measured on a live client: an open-loop intent travels a straight line on flat ground (8.408
 * blocks, 0.04 degrees off the facing yaw) but jams at zero displacement against a wall with
 * {@code isCollidedHorizontally} true, and {@code act_status} said "moving" in both cases.
 *
 * <p><b>Why displacement rather than velocity.</b> Velocity does separate the two states -- measured
 * live, the Z component reads 0.09 walking against 0.0 jammed -- but it is reachable only through
 * the observe path, and it answers "how fast right now" when the caller's question is "did this
 * intent accomplish anything". Displacement since the intent became ACTIVE answers that directly,
 * and it is the term a stuck test needs: no displacement over N ticks while ACTIVE.
 *
 * <p>This is deliberately NOT a navigation controller. It adds no intent type and no slot, so it
 * stays neutral on how closed-loop steering is eventually shaped -- it only makes the slot tell the
 * truth about what already happens.
 */
public class MoveApplierReportsDisplacementTest {

    private static SlotRecord activeMove(FakeActuator act, int durationTicks) {
        MoveIntent mi = new MoveIntent(1.0f, 0f, false, false, false, durationTicks);
        return SlotRecord.submitted(mi, 0L, 1L, "submitted");
    }

    /**
     * Step the applier n times, moving the fake BEFORE each tick.
     *
     * <p>Order matters and this is the real one: the game moves the player, then the applier
     * observes it. Nudging afterwards makes the applier miss the last step, which is how the
     * first version of this test came to assert a distance one tick short of what the applier
     * could legitimately see.
     */
    private static SlotRecord run(MoveApplier applier, SlotRecord rec, int ticks,
                                  Runnable beforeEachTick) {
        for (int i = 0; i < ticks; i++) {
            if (beforeEachTick != null) {
                beforeEachTick.run();
            }
            rec = applier.apply(rec.stampTick(i + 1));
        }
        return rec;
    }

    @Test
    public void aWalkingPlayerAndAJammedPlayerDoNotReportTheSameThing() {
        FakeActuator walking = new FakeActuator();
        walking.setPosition(0, 64, 0);
        MoveApplier walkApplier = new MoveApplier(walking);
        SlotRecord walked = run(walkApplier, activeMove(walking, 0), 5,
                () -> walking.nudge(0.2, 0, 0));

        FakeActuator jammed = new FakeActuator();
        jammed.setPosition(0, 64, 0);
        jammed.collidedHorizontally = true;
        MoveApplier jamApplier = new MoveApplier(jammed);
        SlotRecord stuck = run(jamApplier, activeMove(jammed, 0), 5, null);

        assertNotEquals("a player crossing ground and a player pressed against a wall must not "
                + "produce the same status. Before this, both read \"moving (tick 5)\" because the "
                + "message counted the applier's own ticks and never looked at the world.",
            walked.message(), stuck.message());
    }

    @Test
    public void theMessageCarriesTheDistanceActuallyTravelled() {
        FakeActuator act = new FakeActuator();
        act.setPosition(0, 64, 0);
        MoveApplier applier = new MoveApplier(act);
        // Five ticks at 0.25 blocks, and the ORIGIN is captured on the first of them -- so four
        // intervals are measurable, i.e. 1.00 blocks. Movement before the applier's first
        // observation is deliberately not attributed to this intent: an intent is answerable for
        // what happened after it became active, not for where the player already was.
        SlotRecord rec = run(applier, activeMove(act, 0), 5, () -> act.nudge(0.25, 0, 0));

        String m = rec.message();
        assertTrue("the MOVE status must state distance travelled, so a caller can tell progress "
                + "from a held key. Got: " + m,
            m.contains("1.00"));
    }

    @Test
    public void aJammedPlayerIsCalledOutRatherThanReportedAsMoving() {
        FakeActuator act = new FakeActuator();
        act.setPosition(5, 64, 5);
        act.collidedHorizontally = true;
        MoveApplier applier = new MoveApplier(act);
        SlotRecord rec = run(applier, activeMove(act, 0), 6, null);

        String m = rec.message().toLowerCase();
        assertTrue("a jam is the one locomotion failure the caller cannot see any other way, and "
                + "collidedHorizontally is exactly the boolean for it. The status should say so "
                + "rather than reporting motion that is not happening. Got: " + rec.message(),
            m.contains("stuck") || m.contains("blocked") || m.contains("jam"));
    }

    @Test
    public void theSlotStillCompletesOnItsDurationBudget() {
        // Guard the behaviour that already worked: measured live, durationTicks 40 stopped exactly
        // on budget. A displacement term must not disturb the lifecycle.
        FakeActuator act = new FakeActuator();
        act.setPosition(0, 64, 0);
        MoveApplier applier = new MoveApplier(act);
        SlotRecord rec = run(applier, activeMove(act, 3), 3, () -> act.nudge(0.1, 0, 0));
        assertTrue("a positive duration must still complete the slot on budget; phase was "
                + rec.phase(), rec.phase() == ActPhase.COMPLETE);
    }
}
