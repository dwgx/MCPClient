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
        act.maxUseDuration = HELD_INDEFINITELY_TICKS;
        return act;
    }

    /**
     * A bow a human has ALREADY been drawing for {@code alreadyDrawn} ticks when the hold arrives.
     *
     * <p>The distinction this sets up is the one that hid a real defect: {@code maxUseDuration} stays
     * the item's own 72000 while the live count is already down, so a controller measuring elapsed
     * ticks from its own first observation disagrees with vanilla, which measures from the item's
     * duration ({@code ItemBow.java:32}). Every earlier bow test started the draw itself, where the
     * two baselines coincide, so none of them could see it.
     */
    private static FakeActuator bowAlreadyDrawnFor(int alreadyDrawn) {
        FakeActuator act = holdingBow();
        act.usingItem = true;
        act.useKeyDown = true;
        act.useCount = HELD_INDEFINITELY_TICKS - alreadyDrawn;
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

    /**
     * A hotbar switch in the LAST few ticks of a meal is still an interruption.
     *
     * <p>The window the count band could not see. Completion was judged by "the clock is nearly
     * out", on the reasoning that an interruption leaves tens of ticks -- true mid-meal, false here:
     * a 32-tick eat passes through 3, 2, 1, 0, and a switch inside that stretch was reported as
     * "use completed". Roughly four ticks in every meal, and the caller then believes hunger was
     * restored when the stack was pulled out of its hand.
     *
     * <p>The sibling test above interrupts at tick 5, deep inside the band where the clock alone is
     * decisive, which is why it passed throughout.
     */
    @Test
    public void aHotbarSwitchInTheFinalTicksIsStillInterruptedNotCompleted() {
        FakeActuator act = holdingFood();
        HoldController c = new HoldController(InteractIntent.holdUntilDone());

        ActOutcome out = null;
        for (int i = 0; i < 60; i++) {
            out = c.tick(act);
            act.advanceGameTick();
            if (out.terminal()) {
                break;
            }
            // Inside COMPLETION_COUNT_SLACK: the clock now looks exactly like a finishing meal.
            if (act.itemInUseCount() <= 2 && act.isUsingItem()) {
                act.interruptBySwitchingSlot(7);
            }
        }

        assertTrue("must terminate", out != null && out.terminal());
        assertFalse("a meal cut short in its last ticks must NOT be reported as eaten -- the caller "
                        + "acts on that: " + out.message(), out.ok());
        assertTrue("and the message must name the slot change rather than the clock: " + out.message(),
                out.message().contains("held slot changed"));
    }

    /**
     * Adopting a draw already in progress must report the draw VANILLA sees, not the part we watched.
     *
     * <p>The defect this pins: the draw count was computed against the count observed when this
     * controller adopted the use, while {@code ItemBow.java:32} charges on
     * {@code getMaxItemUseDuration(stack) - timeLeft}. A human draws 15 ticks, the hold adopts and
     * releases 2 ticks later: ours said 2 and described it as below the minimum -- "fires nothing" --
     * while vanilla saw 17 and loosed a nearly full arrow. The report was not merely imprecise, it
     * asserted the opposite outcome, and a caller deciding whether to shoot again acts on it.
     *
     * <p>Adoption is a supported entry rather than a corner: the hold is documented as able to take
     * over a use the player already started.
     */
    @Test
    public void adoptingADrawInProgressReportsTheDrawFromVanillasBaseline() {
        FakeActuator act = bowAlreadyDrawnFor(15);
        HoldController c = new HoldController(InteractIntent.holdThenRelease(2));

        ActOutcome out = run(c, act, 30);
        assertTrue("must terminate", out != null && out.terminal());
        assertTrue("a released bow is a success: " + out.message(), out.ok());
        assertTrue("the draw must count from the item's own duration, so 15 already drawn plus the "
                        + "2 we held reads as 17 -- not the 2 we happened to watch: " + out.message(),
                out.message().contains("17 draw ticks"));
        assertFalse("and 17 ticks is well past the minimum, so it must NOT claim nothing was fired: "
                        + out.message(),
                out.message().contains("fire anything") || out.message().contains("no arrow"));
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

    /**
     * A screen opening clears the key but does NOT end the use, and the report must say so.
     *
     * <p>The claim this replaces was backwards on the commonest path there is. Vanilla's stop branch
     * ({@code Minecraft.java:2118-2122}) sits inside
     * {@code if (currentScreen == null || currentScreen.allowUserInput)} at
     * {@code Minecraft.java:1829}, and {@code allowUserInput} defaults false with only
     * {@code GuiInventory} and {@code GuiContainerCreative} setting it. So the very screen that wipes
     * the bindings also gates off the code that would have ended the use: chat, the pause menu, a
     * chest, a furnace. Reporting "vanilla has already stopped the use" told the caller the opposite
     * of the truth -- the meal keeps ticking, a drawn bow stays drawn and fires when the screen
     * closes, and a caller that believed the use was over would never look again.
     *
     * <p>The sibling test below keeps the focus-loss case, where the coupling DOES hold, so the two
     * endings stay distinguishable rather than collapsing into one message.
     */
    @Test
    public void aScreenThatClearsTheKeyWithoutEndingTheUseIsReportedAsStillRunning() {
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
                // A chest opens: the bindings are wiped AND vanilla's stop branch is gated off.
                act.useKeyDown = false;
                act.screenGatesVanillaStop = true;
            }
        }

        assertTrue("must terminate", out != null && out.terminal());
        assertFalse("losing control of a use is a failure", out.ok());
        assertTrue("the message must say the use is STILL RUNNING rather than claiming vanilla "
                        + "stopped it: " + out.message(),
                out.message().contains("STILL RUNNING"));
        assertTrue("and must warn that a drawn bow fires when the screen closes, since that is the "
                        + "consequence the caller cannot see: " + out.message(),
                out.message().contains("fire when that screen closes"));
    }

    /**
     * A PAUSED game freezes the use without clearing the key: the deadline must blame the client.
     *
     * <p>FOUND ON A LIVE CLIENT, and it is the branch automation hits while a human does not. A
     * screen usually clears the use key, so the hold ends on the key-lost branch above with the
     * screen named -- but that clearing runs inside {@code setIngameNotInFocus}, guarded by
     * {@code if (this.inGameHasFocus)} ({@code Minecraft.java:1467-1469}). Measured both ways on a
     * live client: with in-game focus, opening a screen cleared the key; without it, THE KEY
     * SURVIVED, the hold kept re-asserting for 73 ticks with the count frozen, and the deadline then
     * reported "the server never sent the finish (status id 9)".
     *
     * <p>That message was the defect. The server was never asked anything: in single player
     * {@code isGamePaused} ({@code Minecraft.java:1184}) stops
     * {@code theWorld.updateEntities()} ({@code Minecraft.java:2195-2202}), so the use does not
     * progress on the CLIENT and the integrated server is not running either. A caller reading the
     * old message checks its connection while the pause menu sits open in front of it -- the same
     * message-names-a-cause-the-situation-does-not-support shape as the rest of this round.
     *
     * <p><b>The first version of this test named the wrong gate too</b>, asserting on
     * {@code Minecraft.java:1829} and describing the screen as chat. The live client refuted both:
     * chat does not pause ({@code GuiChat.doesGuiPauseGame()} is false) and a meal completes behind
     * it, which is now the sibling test below. Kept in the record because a test that agrees with a
     * wrong production message is worse than no test -- both halves were mine, and they agreed with
     * each other rather than with the game.
     */
    @Test
    public void aPausedGameFreezesTheUseAndTheDeadlineBlamesTheClientNotTheServer() {
        FakeActuator act = holdingFood();
        HoldController c = new HoldController(InteractIntent.holdUntilDone());

        ActOutcome out = null;
        for (int i = 0; i < 200; i++) {
            out = c.tick(act);
            act.advanceGameTick();
            if (out.terminal()) {
                break;
            }
            if (i == 3) {
                // A PAUSING screen opens on a client with NO in-game focus: the world stops, so the
                // count freezes -- but the key is NOT cleared, so the hold keeps going.
                act.gamePaused = true;
            }
        }

        assertTrue("must terminate at the deadline rather than holding forever",
                out != null && out.terminal());
        assertFalse("a use that never progressed is a failure", out.ok());
        assertTrue("the message must report that the COUNT stopped moving, which is the fact in "
                        + "hand: " + out.message(),
                out.message().contains("the count has not moved for"));
        assertTrue("and name the PAUSE gate, not the input gate: a wrong line number sends the "
                        + "reader to the wrong mechanism: " + out.message(),
                out.message().contains("isGamePaused"));
        assertTrue("naming the screen predicate that decides it, since that is what tells a caller "
                        + "WHICH screens do this: " + out.message(),
                out.message().contains("doesGuiPauseGame"));
        assertFalse("IT MUST NOT BLAME THE SERVER: nothing was ever asked of it, and a caller told "
                        + "this checks its connection while the pause menu is open: " + out.message(),
                out.message().contains("the server never sent the finish"));
    }

    /**
     * Chat does NOT pause, so a meal finishes behind it -- the half the first fix got backwards.
     *
     * <p>Measured live: with chat open the count ran 32 down to 7 and the hold reported
     * COMPLETE, because {@code GuiChat} is the one override whose {@code doesGuiPauseGame()} returns
     * false ({@code GuiScreen}'s default is true). Without this test the production message could go
     * back to listing chat among the causes and nothing would object.
     */
    @Test
    public void aMealCompletesBehindChatBecauseChatDoesNotPauseTheGame() {
        FakeActuator act = holdingFood();
        HoldController c = new HoldController(InteractIntent.holdUntilDone());

        ActOutcome out = null;
        for (int i = 0; i < 200; i++) {
            out = c.tick(act);
            act.advanceGameTick();
            if (out.terminal()) {
                break;
            }
            if (i == 3) {
                // Chat: the stop branch is gated off (allowUserInput false) but the world RUNS.
                act.screenGatesVanillaStop = true;
            }
        }

        assertTrue("must terminate", out != null && out.terminal());
        assertTrue("the meal must COMPLETE behind chat -- the world is still running, so the count "
                        + "reaches zero and the server's finish arrives: " + out.message(),
                out.ok());
        assertTrue("and it must read as a completed use, not as a rescue: " + out.message(),
                out.message().contains("use completed"));
    }

    /**
     * The sibling, so the fix above did not simply replace one wrong cause with another.
     *
     * <p>A use whose count IS moving and which still outlives its duration really is waiting on a
     * server that never answered, and that message must survive. Without this test, blaming the
     * client unconditionally would pass the test above and be just as wrong in the other direction.
     */
    @Test
    public void aUseWhoseCountKeepsMovingStillBlamesTheMissingServerFinish() {
        FakeActuator act = holdingFood();
        // The count runs down past zero and the use never clears: exactly a finish that never came.
        act.serverFinishDelayTicks = 10_000;
        HoldController c = new HoldController(InteractIntent.holdUntilDone());

        ActOutcome out = null;
        for (int i = 0; i < 200; i++) {
            out = c.tick(act);
            act.advanceGameTick();
            if (out.terminal()) {
                break;
            }
        }

        assertTrue("must terminate at the deadline", out != null && out.terminal());
        assertFalse(out.ok());
        assertTrue("a moving count with no finish IS the server's fault, and that message must "
                        + "survive the fix: " + out.message(),
                out.message().contains("the server never sent the finish"));
        assertFalse("and it must not claim the count froze, because it did not: " + out.message(),
                out.message().contains("the count has not moved"));
    }

    /**
     * The OTHER way to lose the key, where vanilla's coupling does hold and the use really does end.
     *
     * <p>Renamed from "a key cleared by a screen": that was the wrong mechanism. A screen gates
     * vanilla's stop branch off entirely (see the test above), so it is the case where the use
     * survives. The case where losing the key genuinely ends the use is focus loss while in-game --
     * {@code Minecraft.java:1467-1469} clears every binding, and only then, since
     * {@code unPressAllKeys} has exactly that one caller and it is guarded on
     * {@code inGameHasFocus}. Keeping the two apart is the point: the caller's next move differs
     * (nothing to do, versus a use still running that it no longer controls).
     */
    @Test
    public void aKeyClearedByFocusLossEndsTheUseAndSaysSo() {
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
                // Focus lost: the bindings are wiped and vanilla's own coupling still applies, so
                // advanceGameTick ends the use on the next step.
                act.useKeyDown = false;
            }
        }
        assertTrue("must terminate", out != null && out.terminal());
        assertFalse("must not report success for a hold that was wiped: " + out.message(), out.ok());
        assertTrue("must name the cleared key: " + out.message(),
                out.message().contains("use key was cleared"));
        assertTrue("and must say the use ended with it, rather than leaving the caller to wonder: "
                + out.message(), out.message().contains("the use has ended with it"));
        assertFalse("this path must NOT claim the use is still running -- that is the screen case: "
                + out.message(), out.message().contains("STILL RUNNING"));
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
