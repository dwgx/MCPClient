package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Teeth for {@link HoldController}: a use must SURVIVE across ticks, end by its own kind's rule, and
 * fail honestly when it cannot.
 *
 * <p>The load-bearing part is not this file, it is {@link FakeActuator#advanceGameTick()}. Vanilla
 * cancels a use on any tick its key is not down ({@code Minecraft.java:2118-2122}), and that is the
 * entire defect being fixed, so a fake that did not model the auto-release would let "still using
 * after 30 ticks" pass for a controller that asserts nothing at all. Every test here steps the fake's
 * vanilla model between controller ticks for that reason.
 *
 * <p>Every harness bound is STRICTLY LARGER than the controller's own budget, so a controller that
 * never terminates shows up as a red non-terminal assertion rather than as a hung build.
 */
public class HoldControllerTest {

    /** Food: 32 ticks, and the only kind for which "until done" means anything. */
    private static final int FOOD_USE_TICKS = 32;

    /** Vanilla's sentinel duration for a bow and for a blocking sword: 72000 ticks. */
    private static final int HELD_INDEFINITELY_TICKS = 72000;

    private static FakeActuator holdingFood() {
        FakeActuator act = new FakeActuator();
        act.useStartCount = FOOD_USE_TICKS;
        return act;
    }

    private static FakeActuator holdingBow() {
        FakeActuator act = new FakeActuator();
        act.useStartCount = HELD_INDEFINITELY_TICKS;
        return act;
    }

    /**
     * Step the controller and then the rest of the game tick, in vanilla's order.
     *
     * <p>The fake advances even on the terminal tick, because vanilla's {@code runTick} does not stop
     * where our advice returns -- that is exactly the window in which a completed use whose key was
     * left asserted gets restarted ({@code Minecraft.java:2158}), and
     * {@link FakeActuator#autoStarts} only becomes readable if we let that window run.
     */
    private static ActOutcome run(HoldController c, FakeActuator act, int maxTicks) {
        ActOutcome out = null;
        for (int i = 0; i < maxTicks; i++) {
            out = c.tick(act);
            act.advanceGameTick();
            if (out.terminal()) {
                break;
            }
        }
        return out;
    }

    @Test
    public void aHoldSurvivesManyTicksBecauseTheKeyIsReAssertedEveryTick() {
        // The regression itself. A one-shot start dies within a couple of ticks against the fake's
        // auto-release, exactly as it did on the live client (count 32 -> 0 in about eight ticks).
        FakeActuator act = holdingFood();
        act.serverFinishDelayTicks = 0;
        HoldController c = new HoldController(InteractIntent.holdThenRelease(30));

        ActOutcome out = null;
        for (int i = 0; i < 20; i++) {
            out = c.tick(act);
            act.advanceGameTick();
            assertFalse("must not have terminated by tick " + (i + 1) + ": " + out.message(),
                out.terminal());
        }
        assertTrue("the use must still be live after 20 ticks -- if the controller stopped asserting "
                + "the key, the fake's auto-release would have ended it: " + out.message(),
            act.isUsingItem());
        assertEquals("and every tick must have re-asserted, not trusted a single assertion",
            20, act.holdUseKeyCalls);
        assertEquals(20, c.heldTicks());
        assertEquals("a hold must never re-start the use", 1, act.useInAirCalls);
    }

    @Test
    public void eatingCompletesWhenVanillaEndsTheUseAndTheKeyIsReleased() {
        FakeActuator act = holdingFood();
        HoldController c = new HoldController(InteractIntent.holdUntilDone());

        // Bound well past the food's own 32 ticks + the fake's server round trip.
        ActOutcome out = run(c, act, 120);
        assertTrue("must terminate", out != null && out.terminal());
        assertTrue("eating must complete: " + out.message(), out.ok());
        assertTrue("and say it completed: " + out.message(),
            out.message().contains("use completed"));
        assertTrue("the hold must have lasted roughly the food's own duration, was " + c.heldTicks(),
            c.heldTicks() >= FOOD_USE_TICKS);
        assertFalse("the key must be released once the use is over", act.useKeyHeld());
        assertEquals("and releasing on the completing tick is what stops vanilla eating a SECOND "
                + "item (Minecraft.java:2158 restarts a use while the key is down)",
            0, act.autoStarts);
    }

    @Test
    public void eatingStillCompletesWhenTheServerFinishArrivesWithNoDelay() {
        // The tight boundary for the completed-vs-interrupted test. With no round trip the use clears
        // the same tick the count reaches zero, so the last count the controller sampled is at its
        // least negative -- if the completion slack were too tight this would be misreported as an
        // interruption.
        FakeActuator act = holdingFood();
        act.serverFinishDelayTicks = 0;
        HoldController c = new HoldController(InteractIntent.holdUntilDone());

        ActOutcome out = run(c, act, 120);
        assertTrue("must terminate", out != null && out.terminal());
        assertTrue("must be reported as completed, not interrupted: " + out.message(), out.ok());
        assertFalse("and must not name an interruption: " + out.message(),
            out.message().contains("interrupted"));
    }

    @Test
    public void aUseTakenAwayMidHoldIsReportedAsInterruptedNotAsCompleted() {
        // A hotbar switch clears vanilla's itemInUse (EntityPlayer.onUpdate:293) with the clock still
        // full. Calling that "eaten" would tell the caller their food was consumed when it was not.
        FakeActuator act = holdingFood();
        HoldController c = new HoldController(InteractIntent.holdUntilDone());

        ActOutcome out = null;
        for (int i = 0; i < 60; i++) {
            out = c.tick(act);
            act.advanceGameTick();
            if (out.terminal()) {
                break;
            }
            if (i == 4) {
                act.interruptUse();
            }
        }
        assertTrue("must terminate", out != null && out.terminal());
        assertFalse("must not claim the use finished: " + out.message(), out.ok());
        assertTrue("must name the interruption and the ticks left: " + out.message(),
            out.message().contains("interrupted"));
    }

    @Test
    public void untilDoneRefusesAnItemThatNeverSelfTerminates() {
        // A bow is 72000 ticks, so UNTIL_DONE would hold for an hour. Fail on tick one with the real
        // duration, and do not leave a draw asserted behind.
        FakeActuator act = holdingBow();
        HoldController c = new HoldController(InteractIntent.holdUntilDone());

        ActOutcome out = run(c, act, 10);
        assertTrue("must terminate immediately", out != null && out.terminal());
        assertFalse(out.ok());
        assertTrue("must name the duration and point at THEN_RELEASE: " + out.message(),
            out.message().contains("" + HELD_INDEFINITELY_TICKS)
                && out.message().contains("THEN_RELEASE"));
        assertFalse("must not leave the bow drawn", act.useKeyHeld());
    }

    @Test
    public void aBowHeldThenReleasedReportsTheDrawTicksVanillaWillSee() {
        FakeActuator act = holdingBow();
        HoldController c = new HoldController(InteractIntent.holdThenRelease(20));

        ActOutcome out = run(c, act, 60);
        assertTrue("must terminate", out != null && out.terminal());
        assertTrue("a released bow is a success: " + out.message(), out.ok());
        assertEquals("the hold must have lasted exactly what was asked", 20, c.heldTicks());
        assertTrue("must report 20 draw ticks -- the number vanilla's charge formula uses, and one "
                + "short would misreport a minimum-charge shot as firing nothing: " + out.message(),
            out.message().contains("20 draw ticks"));
        assertTrue("20 ticks is full charge, and the message should say so: " + out.message(),
            out.message().contains("full charge"));
        assertFalse("the key must end up released -- that IS the shot", act.useKeyHeld());
        assertFalse("and the use must be over", act.isUsingItem());
        assertEquals("exactly one release", 1, act.releaseUseKeyCalls);
        assertEquals("and vanilla must not have re-drawn after the shot", 0, act.autoStarts);
    }

    @Test
    public void aBowReleasedBelowTheChargeFloorSaysNothingWouldFire() {
        // ItemBow.onPlayerStoppedUsing returns without creating an arrow when the charge is under
        // 0.1, which is under 3 draw ticks. A 2-tick "shot" must not read like a shot.
        FakeActuator act = holdingBow();
        HoldController c = new HoldController(
                InteractIntent.holdThenRelease(HoldController.BOW_MIN_CHARGE_TICKS - 1));

        ActOutcome out = run(c, act, 30);
        assertTrue("must terminate", out != null && out.terminal());
        assertTrue("the hold and release themselves succeeded: " + out.message(), out.ok());
        assertTrue("but must warn that nothing fires at this charge: " + out.message(),
            out.message().contains("fire anything"));
    }

    @Test
    public void blockingHoldsForTheRequestedTicksAndNeverSelfTerminates() {
        // A blocking sword is also 72000 ticks and its onPlayerStoppedUsing does nothing, so there is
        // no natural end at all -- the requested tick count is the only thing that can end it.
        FakeActuator act = holdingBow();   // same 72000 sentinel duration as a sword
        HoldController c = new HoldController(InteractIntent.holdThenRelease(40));

        ActOutcome out = null;
        for (int i = 0; i < 39; i++) {
            out = c.tick(act);
            act.advanceGameTick();
            assertFalse("blocking must still be running at tick " + (i + 1) + ": " + out.message(),
                out.terminal());
        }
        assertTrue("still blocking after 39 ticks", act.isUsingItem());

        out = run(c, act, 20);
        assertTrue("must end when the requested block duration is up: " + out.message(),
            out.terminal() && out.ok());
        assertEquals(40, c.heldTicks());
    }

    @Test
    public void aKeyThatCannotBeAssertedFailsAtOnceWithoutStartingAUse() {
        FakeActuator act = holdingFood();
        act.useKeyWritable = false;
        HoldController c = new HoldController(InteractIntent.holdUntilDone());

        ActOutcome out = run(c, act, 10);
        assertTrue("must terminate on the first tick", out != null && out.terminal());
        assertFalse(out.ok());
        assertTrue("must say the key could not be asserted: " + out.message(),
            out.message().contains("use key"));
        assertEquals("and must not have started a use it cannot sustain", 0, act.useInAirCalls);
        assertFalse(act.isUsingItem());
    }

    @Test
    public void aKeyClearedByAScreenFailsHonestlyRatherThanContinuing() {
        // KeyBinding.unPressAllKeys wipes every binding when a GUI opens (Minecraft.java:1469). The
        // hold must notice, because vanilla has already stopped the use by then; silently
        // re-asserting would report a hold that is not happening.
        FakeActuator act = holdingFood();
        HoldController c = new HoldController(InteractIntent.holdThenRelease(30));

        ActOutcome out = null;
        for (int i = 0; i < 50; i++) {
            out = c.tick(act);
            act.advanceGameTick();
            if (out.terminal()) {
                break;
            }
            if (i == 3) {
                act.useKeyDown = false;   // a screen opened between our tick and the next
            }
        }
        assertTrue("must terminate", out != null && out.terminal());
        assertFalse("must not report success for a hold that was wiped: " + out.message(), out.ok());
        assertTrue("must name the cleared key and point at the screen: " + out.message(),
            out.message().contains("use key was cleared") && out.message().contains("screen"));
    }

    @Test
    public void cancelReleasesTheKeySoNoUseIsLeftRunning() {
        FakeActuator act = holdingFood();
        HoldController c = new HoldController(InteractIntent.holdThenRelease(100));
        c.tick(act);
        act.advanceGameTick();
        c.tick(act);
        act.advanceGameTick();
        assertTrue("precondition: the hold is live", act.useKeyHeld() && act.isUsingItem());

        c.requestCancel();
        ActOutcome out = c.tick(act);
        act.advanceGameTick();
        assertEquals(ActPhase.CANCELLED, out.state());
        assertFalse("a cancel must release the key, or the player keeps eating unattended",
            act.useKeyHeld());
        assertEquals("exactly one release", 1, act.releaseUseKeyCalls);
        assertFalse("and vanilla must have ended the use", act.isUsingItem());
    }

    @Test
    public void anItemThatUsesInstantlyFailsBecauseThereIsNothingToHold() {
        // A snowball: sendUseItem changes the stack and no use state exists afterwards. The throw
        // HAPPENED, so the message says so, but the caller asked for a hold and did not get one.
        FakeActuator act = new FakeActuator();
        act.useStartsSustained = false;
        HoldController c = new HoldController(InteractIntent.holdUntilDone());

        ActOutcome out = run(c, act, 10);
        assertTrue("must terminate", out != null && out.terminal());
        assertFalse(out.ok());
        assertTrue("must distinguish 'nothing to hold' from 'the use was refused': " + out.message(),
            out.message().contains("no use duration"));
        assertFalse("and must not leave the key asserted", act.useKeyHeld());
    }

    @Test
    public void aRefusedUseFailsWithoutHoldingTheKey() {
        FakeActuator act = holdingFood();
        act.useInAirResult = false;   // empty hand, or hunger already full
        HoldController c = new HoldController(InteractIntent.holdUntilDone());

        ActOutcome out = run(c, act, 10);
        assertTrue("must terminate", out != null && out.terminal());
        assertFalse(out.ok());
        assertTrue("must say the use was rejected: " + out.message(),
            out.message().contains("use rejected"));
        assertFalse("and must not hold a key with no use behind it", act.useKeyHeld());
    }

    @Test
    public void aHoldAlreadyInProgressIsAdoptedRatherThanRestarted() {
        // The human is holding the button, or a USE intent just started something. Starting a second
        // use would consume a second item.
        FakeActuator act = holdingFood();
        act.usingItem = true;
        act.useCount = 20;
        HoldController c = new HoldController(InteractIntent.holdThenRelease(5));

        ActOutcome out = run(c, act, 30);
        assertTrue("must terminate", out != null && out.terminal());
        assertTrue("adopting the running use is the success case: " + out.message(), out.ok());
        assertEquals("must not have started a second use", 0, act.useInAirCalls);
    }

    @Test
    public void thenReleaseWithoutATickCountFailsInsteadOfHoldingForever() {
        FakeActuator act = holdingBow();
        HoldController c = new HoldController(InteractIntent.holdThenRelease(0));

        ActOutcome out = run(c, act, 10);
        assertTrue("must terminate on the first tick", out != null && out.terminal());
        assertFalse(out.ok());
        assertTrue("must name the requirement: " + out.message(),
            out.message().contains("holdTicks"));
        assertEquals("and must not have touched the game", 0, act.useInAirCalls);
    }

    @Test
    public void notInWorldFailsWithoutTouchingTheKey() {
        FakeActuator act = holdingFood();
        act.inWorld = false;
        HoldController c = new HoldController(InteractIntent.holdUntilDone());

        ActOutcome out = run(c, act, 5);
        assertTrue(out != null && out.terminal());
        assertFalse(out.ok());
        assertEquals(0, act.holdUseKeyCalls);
    }

    /**
     * Losing the world MID-hold must release the key, not just stop.
     *
     * <p>The test above sets {@code inWorld} false before the first tick, so the key was never
     * asserted and {@code holdUseKeyCalls == 0} passes without proving anything about teardown. The
     * case that matters is the one it cannot reach: death, a dimension change or a disconnect while
     * the key IS down. {@code KeyBinding.pressed} lives in a static binding, so it outlives world
     * teardown -- an assertion abandoned here is still down when the player next spawns, a phantom
     * right-click held from the first tick of the new world with nothing on our side aware of it.
     */
    @Test
    public void losingTheWorldMidHoldStillReleasesTheKey() {
        FakeActuator act = holdingFood();
        HoldController c = new HoldController(InteractIntent.holdUntilDone());

        assertFalse("precondition: the hold is running", c.tick(act).terminal());
        assertTrue("precondition: the key is asserted", act.useKeyHeld());

        act.inWorld = false;
        ActOutcome out = c.tick(act);

        assertTrue("losing the world is terminal", out.terminal());
        assertFalse("and it is a failure, not a success", out.ok());
        assertFalse("the key must not be left asserted across world teardown -- it is static and "
                + "survives into the next world", act.useKeyHeld());
        assertTrue("and the release must be an explicit call", act.releaseUseKeyCalls > 0);
    }
}
