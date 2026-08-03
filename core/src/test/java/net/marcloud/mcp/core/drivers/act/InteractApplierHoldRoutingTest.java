package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.event.events.TickEvent;
import org.junit.Before;
import org.junit.Test;

/**
 * The INTERACT slot must drive a hold for its whole life, and let go of it on a cancel.
 *
 * <p>Two properties here belong to the applier rather than to {@link HoldController}, and neither is
 * visible from a controller test:
 *
 * <ul>
 *   <li><b>Identity freshness.</b> {@link InteractApplier} detects a new submission by intent
 *       IDENTITY. A dig or a hotbar select is over inside one or two ticks so the question barely
 *       arises; a hold lasts tens of ticks, and anything rewriting the slot's intent per tick would
 *       rebuild the controller on every one of them and restart the hold forever.
 *   <li><b>Cancel teardown.</b> The cancel branch used to forward only to a live
 *       {@link DigController}. A hold reached the {@code reset()} path instead, which drops the
 *       controller without ever releasing vanilla's use key -- leaving the player eating or blocking
 *       with nothing driving it.
 * </ul>
 */
public class InteractApplierHoldRoutingTest {

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
        act.useStartCount = 32;   // food
        runtime.registerApplier(ActSlot.INTERACT, new InteractApplier(act));
    }

    /** One whole game tick: the loop's advice, then the rest of vanilla's tick. */
    private void tick() {
        long id = clock.advance();
        loop.onTick(new TickEvent(id));
        act.advanceGameTick();
    }

    @Test
    public void aHoldStaysActiveAcrossManyTicksThroughTheSlot() {
        clock.advance();
        runtime.submitInteract(InteractIntent.holdThenRelease(30));

        for (int i = 0; i < 15; i++) {
            tick();
            assertEquals("the slot must still be ACTIVE at tick " + (i + 1) + ": "
                    + runtime.record(ActSlot.INTERACT).message(),
                ActPhase.ACTIVE, runtime.record(ActSlot.INTERACT).phase());
        }
        assertTrue("and the use must still be live -- the fake auto-releases on any tick the key is "
                + "not asserted, so this is the whole regression",
            act.isUsingItem());
        assertEquals("exactly one use started, not one per tick", 1, act.useInAirCalls);
        assertEquals("the controller must have been kept, not rebuilt each tick "
                + "(a rebuild would re-run the STARTING branch every tick)",
            15, act.holdUseKeyCalls);
    }

    @Test
    public void theSlotReportsTheHoldSoAStalledWalkIsDiagnosable() {
        // Vanilla scales movement to 0.2x while isUsingItem (EntityPlayerSP:788-792), which
        // decalibrates NavController's steering. Not fixed here -- but act_status must at least show
        // WHY, which means the slot's message has to say a use is being held.
        clock.advance();
        runtime.submitInteract(InteractIntent.holdUntilDone());
        tick();
        String msg = runtime.record(ActSlot.INTERACT).message();
        assertTrue("the first message must name the movement scaling, since a caller whose walk "
                + "suddenly crawls reads this line: " + msg,
            msg.contains("0.2x"));
        tick();
        assertTrue("and later ticks must show the hold is still running: "
                + runtime.record(ActSlot.INTERACT).message(),
            runtime.record(ActSlot.INTERACT).message().contains("holding"));
    }

    @Test
    public void cancellingAHoldReleasesTheUseKey() {
        clock.advance();
        runtime.submitInteract(InteractIntent.holdThenRelease(200));
        tick();
        tick();
        assertTrue("precondition: the hold is live", act.useKeyHeld() && act.isUsingItem());

        assertTrue("a live hold must be flagged, not reset", runtime.cancel(ActSlot.INTERACT));
        tick();
        assertEquals(ActPhase.CANCELLED, runtime.record(ActSlot.INTERACT).phase());
        assertFalse("the cancel must have released the key through the controller's teardown",
            act.useKeyHeld());
        assertEquals(1, act.releaseUseKeyCalls);
        assertFalse("so vanilla ends the use rather than the player eating unattended",
            act.isUsingItem());
    }

    @Test
    public void aCompletedHoldEndsTheSlotWithoutStartingASecondUse() {
        clock.advance();
        runtime.submitInteract(InteractIntent.holdUntilDone());

        // Bound strictly larger than the food's 32 ticks plus the fake's server round trip, so a
        // controller that never terminates fails the assertion below instead of hanging.
        for (int i = 0; i < 120 && !runtime.record(ActSlot.INTERACT).phase().isTerminal(); i++) {
            tick();
        }
        SlotRecord rec = runtime.record(ActSlot.INTERACT);
        assertEquals("the slot must complete: " + rec.message(), ActPhase.COMPLETE, rec.phase());
        assertEquals("and must not have eaten a second item -- releasing on the completing tick is "
                + "what stops Minecraft.java:2158 restarting the use", 0, act.autoStarts);
    }

    @Test
    public void aFreshSubmitReplacesALiveHold() {
        clock.advance();
        runtime.submitInteract(InteractIntent.holdThenRelease(200));
        tick();
        tick();
        assertTrue(act.isUsingItem());

        // A different intent object: the applier must rebuild rather than keep driving the old hold.
        runtime.submitInteract(InteractIntent.hotbar(4));
        tick();
        assertEquals("the new intent must have run", 4, act.heldSlot);
    }
}
