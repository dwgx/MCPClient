package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.event.events.TickEvent;
import org.junit.Before;
import org.junit.Test;

/**
 * The LOOK slot must be able to FOLLOW a target, and the half that was missing is the half these
 * tests are about.
 *
 * <p>{@link LookIntent.Mode#LOOK_AT} already re-resolved the target angle from the eye on every
 * tick, so "a moving entity is tracked" was half true. The other half is that the correction has to
 * keep being written: under {@link LookIntent.AimMode#ONCE} the controller returns a terminal
 * outcome the tick the crosshair lands, {@link ActTickLoop} then skips the slot forever
 * ({@code stepSlot} returns early on a terminal phase), and the tick where the mob has already
 * walked on has nobody correcting it. Recomputing an angle that nothing writes is not tracking.
 *
 * <p>Driven end-to-end through {@link ActRuntime} + {@link ActTickLoop} rather than by calling the
 * controller directly, because two of the properties are not the controller's:
 *
 * <ul>
 *   <li><b>Arrival must not end the slot.</b> Visible only through the loop, since it is the loop
 *       that stops stepping a terminal slot.
 *   <li><b>Identity freshness.</b> {@link LookApplier} detects a new submission by intent IDENTITY,
 *       so a track -- which lasts many ticks -- must keep the SAME record in the slot. Anything
 *       rewriting it per tick would rebuild the controller every tick and restart the aim forever,
 *       resetting its duration bound with it. {@code InteractApplier} carries the same constraint
 *       for the hold channel, which is why nav publishes its axes out-of-band instead.
 * </ul>
 */
public class LookTrackingDoesNotSelfTerminateTest {

    private GameClock clock;
    private ActRuntime runtime;
    private ActTickLoop loop;
    private FakeActuator act;

    @Before
    public void setUp() {
        clock = new GameClock();
        clock.reset();
        runtime = new ActRuntime(clock);
        loop = new ActTickLoop(runtime);
        act = new FakeActuator();
        act.eye = new double[] {0, 0, 0};
        runtime.registerApplier(ActSlot.LOOK, new LookApplier(act));
    }

    private void tick() {
        loop.onTick(new TickEvent(clock.advance()));
    }

    private SlotRecord look() {
        return runtime.record(ActSlot.LOOK);
    }

    private int rotationWrites() {
        int n = 0;
        for (String c : act.calls) {
            if (c.startsWith("setRotation")) {
                n++;
            }
        }
        return n;
    }

    /** The vanilla-convention yaw that aiming from the eye at {@code (x,y,z)} produces. */
    private float expectedYaw(double x, double y, double z) {
        return LookController.anglesTo(act.eye[0], act.eye[1], act.eye[2], x, y, z)[0];
    }

    /**
     * The core regression: the target moves AFTER the crosshair landed on it, and the aim follows.
     *
     * <p>On the previous code the first tick returned {@code done()}, the slot went COMPLETE, and
     * the loop never stepped it again -- so the yaw stayed pointed at where the entity had been.
     * The second sample is the whole test; the first only establishes that it ever aimed at all.
     */
    @Test
    public void aTrackedEntityIsFollowedAfterTheAimHasAlreadyLanded() {
        act.entityEyes.put(42, new double[] {5, 0, 0});
        clock.advance();
        runtime.submitLook(LookIntent.trackEntity(42, 0f, 0));   // instant slew, unbounded

        tick();
        assertEquals("aimed at the entity's first position",
                expectedYaw(5, 0, 0), act.yaw, 1e-3);
        assertEquals("and arrival must NOT end the slot: " + look().message(),
                ActPhase.ACTIVE, look().phase());

        // The mob walks a quarter of the way around us.
        act.entityEyes.put(42, new double[] {0, 0, 5});
        tick();
        assertEquals("the aim must have followed the entity to its new position",
                expectedYaw(0, 0, 5), act.yaw, 1e-3);
        assertEquals("and it is still tracking", ActPhase.ACTIVE, look().phase());

        // Again, so this cannot pass on a controller that merely survives one extra tick.
        act.entityEyes.put(42, new double[] {-5, 0, 0});
        tick();
        assertEquals(expectedYaw(-5, 0, 0), act.yaw, 1e-3);
        assertEquals("three ticks, three corrections", 3, rotationWrites());
    }

    /**
     * The default is unchanged, and that matters as much as the new mode.
     *
     * <p>Making LOOK_AT always keep aiming was the rejected alternative: the commonest use of the
     * slot is turn-then-dig, and that caller needs the slot to REACH a terminal phase for
     * {@code act_status} to answer "did I finish turning" and needs it free for the next aim.
     */
    @Test
    public void anOrdinaryAimStillCompletesOnArrivalAndStopsBeingCorrected() {
        act.entityEyes.put(42, new double[] {5, 0, 0});
        clock.advance();
        runtime.submitLook(LookIntent.lookAtEntity(42, 0f));

        tick();
        assertEquals("an ONCE aim ends the tick it lands", ActPhase.COMPLETE, look().phase());
        assertTrue(look().message().contains("aimed at"));

        int writes = rotationWrites();
        act.entityEyes.put(42, new double[] {0, 0, 5});
        tick();
        assertEquals("and nothing corrects it afterwards -- which is exactly why track exists",
                writes, rotationWrites());
        assertEquals("the aim is left pointed where the entity WAS",
                expectedYaw(5, 0, 0), act.yaw, 1e-3);
    }

    /**
     * A block track exists for the same reason an entity track does: the PLAYER moves.
     *
     * <p>The eye position is what changes here, not the target, so this is the one case where
     * re-resolving is pointless unless something also re-writes -- a caller walking while it mines.
     */
    @Test
    public void aTrackedBlockIsReAimedWhenThePlayerMovesInstead() {
        clock.advance();
        runtime.submitLook(LookIntent.trackBlock(0, 0, 5, 0f, 0));

        tick();
        float first = act.yaw;
        assertEquals("aimed at the block centre from the original eye",
                expectedYaw(0.5, 0.5, 5.5), first, 1e-3);

        act.eye = new double[] {10, 0, 0};
        tick();
        assertEquals("the aim must be recomputed from the NEW eye position",
                expectedYaw(0.5, 0.5, 5.5), act.yaw, 1e-3);
        assertNotEquals("precondition: moving the eye really does change the answer",
                first, act.yaw, 1.0);
    }

    /**
     * The controller must survive across ticks rather than being rebuilt.
     *
     * <p>Observed through the tick counter the controller itself keeps and puts in its message: a
     * per-tick rebuild would report "tick 1" forever, and would also never reach a duration bound.
     */
    @Test
    public void theTrackKeepsOneControllerRatherThanRebuildingItEachTick() {
        act.entityEyes.put(42, new double[] {5, 0, 0});
        clock.advance();
        runtime.submitLook(LookIntent.trackEntity(42, 0f, 0));

        for (int i = 1; i <= 6; i++) {
            tick();
            assertTrue("tick " + i + " must be reported by the SAME controller, so its own count "
                            + "reaches " + i + ": " + look().message(),
                    look().message().contains("tick " + i));
        }
        assertEquals("and the slot's intent record was never swapped -- identity is how the applier "
                        + "decides freshness", 6, look().ticksActive());
    }

    /** A bounded track ends COMPLETE at its own tick count, having actually held the aim. */
    @Test
    public void aBoundedTrackEndsCompleteOnItsLastTick() {
        act.entityEyes.put(42, new double[] {5, 0, 0});
        clock.advance();
        runtime.submitLook(LookIntent.trackEntity(42, 0f, 5));

        for (int i = 0; i < 4; i++) {
            tick();
            assertEquals("still tracking at tick " + (i + 1), ActPhase.ACTIVE, look().phase());
        }
        tick();
        assertEquals("the fifth tick is the bound: " + look().message(),
                ActPhase.COMPLETE, look().phase());
        assertTrue("and it says how long it tracked: " + look().message(),
                look().message().contains("tracked for 5 ticks"));
        assertEquals("the last tick still corrected the aim rather than spending itself on "
                + "bookkeeping", 5, rotationWrites());
    }

    /**
     * A bounded track whose slew cap never caught the target must NOT report success.
     *
     * <p>"tracked for N ticks" reads as "I am looking at it", and a caller acts on that by
     * attacking or digging. At 1 degree/tick against a 90-degree error the crosshair is nowhere
     * near the mob when the bound expires, and calling that done would be a confident claim in the
     * dangerous direction -- the same failure shape as the hold channel reporting an interrupted
     * meal as eaten.
     */
    @Test
    public void aBoundedTrackThatNeverCaughtTheTargetFailsRatherThanClaimingItAimed() {
        act.entityEyes.put(42, new double[] {5, 0, 0});   // ~-90 degrees away from yaw 0
        act.yaw = 0f;
        clock.advance();
        runtime.submitLook(LookIntent.trackEntity(42, 1f, 10));  // 1 deg/tick, 10 ticks = 10 degrees

        for (int i = 0; i < 10; i++) {
            tick();
        }
        SlotRecord rec = look();
        assertEquals("it must fail, not complete: " + rec.message(), ActPhase.FAILED, rec.phase());
        assertTrue("and must say the crosshair never arrived: " + rec.message(),
                rec.message().contains("never reached the target"));
        assertTrue("naming the residual error so the caller can size its slew: " + rec.message(),
                rec.message().contains("degrees of yaw out"));
    }

    /** Losing the tracked entity is an honest failure, and says how long it had been followed. */
    @Test
    public void losingTheTrackedEntityFailsHonestlyWithHowLongItWasFollowed() {
        act.entityEyes.put(42, new double[] {5, 0, 0});
        clock.advance();
        runtime.submitLook(LookIntent.trackEntity(42, 0f, 0));

        for (int i = 0; i < 3; i++) {
            tick();
        }
        assertEquals(ActPhase.ACTIVE, look().phase());

        act.entityEyes.remove(42);
        tick();
        SlotRecord rec = look();
        assertEquals("a gone target ends the track: " + rec.message(), ActPhase.FAILED, rec.phase());
        assertTrue(rec.message().contains("gone"));
        assertTrue("the tick count separates 'the mob died after a while' from 'that id was never "
                        + "there': " + rec.message(),
                rec.message().contains("after 3 ticks of tracking"));
    }

    /**
     * An unbounded track has exactly one caller-side ending, so this is the one that has to work:
     * {@code act_cancel} must release the LOOK slot, and a fresh aim must take it cleanly.
     */
    @Test
    public void cancellingATrackReleasesTheSlotAndANewAimTakesOver() {
        act.entityEyes.put(42, new double[] {5, 0, 0});
        clock.advance();
        runtime.submitLook(LookIntent.trackEntity(42, 0f, 0));
        tick();
        tick();
        assertEquals(ActPhase.ACTIVE, look().phase());

        assertTrue("a live track must be FLAGGED for teardown, not reset from under the applier",
                runtime.cancel(ActSlot.LOOK));
        tick();
        assertEquals(ActPhase.CANCELLED, look().phase());
        assertTrue("the cancel report is the whole account of an unbounded track, so it carries the "
                        + "tick count: " + look().message(),
                look().message().contains("look cancelled after 2 ticks"));

        // The slot is genuinely free: a plain aim now runs and completes.
        runtime.submitLook(LookIntent.set(45f, 10f, 0f));
        tick();
        assertEquals("the replacement aim must run on a slot the track no longer owns: "
                + look().message(), ActPhase.COMPLETE, look().phase());
        assertEquals(45f, act.yaw, 1e-3);
        assertEquals(10f, act.pitch, 1e-3);
    }

    /** Replacing a live track starts a fresh controller rather than continuing the old aim. */
    @Test
    public void submittingANewLookReplacesALiveTrack() {
        act.entityEyes.put(42, new double[] {5, 0, 0});
        clock.advance();
        runtime.submitLook(LookIntent.trackEntity(42, 0f, 0));
        tick();
        tick();

        runtime.submitLook(LookIntent.trackEntity(42, 0f, 3));
        tick();
        assertTrue("the new intent's controller starts its own count at 1: " + look().message(),
                look().message().contains("tick 1/3"));
        assertFalse("it must not have inherited the previous controller's progress",
                look().message().contains("tick 3/3"));
    }

    /**
     * A track that outlives the world ends FAILED rather than writing rotation into nothing.
     *
     * <p>Unbounded by construction, so without this ending a disconnect would leave the LOOK slot
     * ACTIVE forever with a controller aiming at a target it can no longer resolve.
     */
    @Test
    public void aTrackEndsWhenTheWorldGoesAway() {
        act.entityEyes.put(42, new double[] {5, 0, 0});
        clock.advance();
        runtime.submitLook(LookIntent.trackEntity(42, 0f, 0));
        tick();
        assertEquals(ActPhase.ACTIVE, look().phase());

        act.inWorld = false;
        tick();
        assertEquals(ActPhase.FAILED, look().phase());
        assertTrue(look().message().contains("not in world"));
    }
}
