package net.marcloud.mcp.core.drivers.act;

/**
 * Pure state machine that SUSTAINS a use across ticks: eating, drawing a bow, blocking with a
 * sword. Ticked by the INTERACT applier through an {@link ActActuator}; it never marshals threads.
 *
 * <p>Shaped after {@link DigController}, this package's proven durable behaviour -- per-tick pump,
 * poll for completion, fail honestly on no progress, respect vanilla's own limits rather than
 * hammering. {@link InteractController} could not host this: every one of its branches goes through
 * {@code finish()} and it never returns {@code running()}, so it is single-shot by construction.
 *
 * <p><b>The gap this closes.</b> {@code Minecraft.java:2118-2122} calls
 * {@code playerController.onStoppedUsingItem} on ANY tick where
 * {@code gameSettings.keyBindUseItem.isKeyDown()} is false. A one-shot {@code sendUseItem} therefore
 * starts a use that vanilla cancels within a couple of ticks -- measured after commit 52647ad, the
 * use count fell from 32 to 0 in about eight ticks and food never rose. So the pump here is not
 * progress like {@code pumpDig}: it is {@link ActActuator#holdUseKey()}, re-asserted every tick,
 * keeping vanilla's own key convinced. Everything else follows from that.
 *
 * <p>States: STARTING (assert the key, start the use, confirm something is actually in use) →
 * HOLDING (re-assert every tick, poll for the ending rule) → RELEASING (key released, confirm
 * vanilla let go) → COMPLETE / FAILED / CANCELLED.
 *
 * <p><b>Two hazards, handled here.</b>
 *
 * <ul>
 *   <li><b>A GUI wipes the key.</b> {@code KeyBinding.unPressAllKeys} clears every binding whenever
 *       a screen opens ({@code Minecraft.java:1469} via {@code displayGuiScreen}). So the hold
 *       re-asserts each tick, and reads {@link ActActuator#useKeyHeld()} BEFORE re-asserting: a key
 *       we asserted last tick that now reads up was cleared by something else, and that ends the
 *       hold FAILED with the screen named as the likely cause. Silently re-asserting would hide a
 *       use vanilla has already stopped.
 *   <li><b>Movement is decalibrated while using.</b> {@code EntityPlayerSP:788-792} scales
 *       {@code moveStrafe}/{@code moveForward} to 0.2x whenever {@code isUsingItem()}, which throws
 *       off {@code NavController}'s steering and {@code MoveApplier}'s stuck test. Not fixed here,
 *       and not silently absorbed either: the STARTING message says so, so a caller reading
 *       {@code act_status} while their walk crawls has the reason in front of them.
 * </ul>
 */
public final class HoldController {

    /**
     * Ticks a bow must be drawn before RELEASE fires anything.
     *
     * <p>{@code ItemBow.onPlayerStoppedUsing} computes {@code f = (t^2 + 2t)/3} where {@code t} is
     * draw ticks / 20, and returns without creating an arrow when {@code f < 0.1}. Solving gives
     * 2.80 ticks, so 2 ticks yields 0.070 (nothing) and 3 ticks yields 0.108 (an arrow). The same
     * formula reaches {@code f == 1.0} at exactly 20 ticks, which is full charge.
     */
    public static final int BOW_MIN_CHARGE_TICKS = 3;

    /** Draw ticks at which a bow reaches full charge, by the same formula. */
    public static final int BOW_FULL_CHARGE_TICKS = 20;

    /**
     * Largest initial use count still treated as self-terminating.
     *
     * <p>Vanilla's durations are not a spectrum, they are two clusters: every consumable is short
     * (food 32 ticks, potions and the rest in the same range) and the held-indefinitely items use one
     * sentinel, 72000. 200 sits in the empty middle, so an {@link InteractIntent.HoldMode#UNTIL_DONE}
     * on a bow or a sword is rejected on its first tick with a real number in the message instead of
     * holding for an hour.
     */
    public static final int SELF_TERMINATING_MAX_COUNT = 200;

    /**
     * Ticks past the item's own duration to wait for the server's finish before failing.
     *
     * <p>Not a blanket timeout -- the deadline is the ITEM's duration plus this, because the client
     * cannot end a use by itself: {@code EntityPlayer.onUpdate:286} only calls
     * {@code onItemUseFinish} when {@code !worldObj.isRemote}, so the client's count runs past zero
     * into negatives and the use ends when status id 9 arrives. Two seconds of slack covers a round
     * trip; if the client is still using well past that, the server never agreed the use finished and
     * saying so is more useful than holding.
     */
    public static final int SERVER_FINISH_SLACK_TICKS = 40;

    /**
     * How far below zero the count may already be for a cleared use to still count as completed.
     *
     * <p>On the completing tick the sample is one step stale -- this controller reads at the top of
     * the tick, {@code EntityPlayer.onUpdate} decrements after it, and the server's status arrives
     * later still -- so a genuine completion can be observed with a small positive count. An
     * interruption (a hotbar switch clearing the use via {@code onUpdate:293}) leaves tens of ticks
     * on the clock, far outside this band.
     */
    private static final int COMPLETION_COUNT_SLACK = 3;

    private enum State { STARTING, HOLDING, RELEASING }

    private final InteractIntent.HoldMode mode;
    private final int holdTicks;

    private State state = State.STARTING;
    private boolean done;
    private boolean cancelRequested;

    /** Ticks the use key has been asserted, i.e. how long the hold has lasted. */
    private int heldTicks;
    /** Vanilla's count on the first HOLDING tick -- the item's own declared duration. */
    private int initialCount;
    /** The most recent count seen while the use was live, kept for the interrupted/completed test. */
    private int lastCount;
    /**
     * Hotbar slot the use started in, so a switch is DETECTED rather than inferred from the clock.
     *
     * <p>The count band alone cannot carry that judgement. Its reasoning was that an interruption
     * leaves tens of ticks on the clock, far outside the band -- true for a switch in the middle of a
     * meal, false for one in its last few ticks, where a 32-tick eat sits at 3, 2, 1, 0 and an
     * interruption is indistinguishable from finishing. That is a false SUCCESS in roughly a
     * four-tick window per meal, and the caller then believes hunger was restored when the stack was
     * pulled away: the dangerous direction of this error.
     */
    private int initialSlot = -1;
    /** Set the tick the key was found cleared, so the NEXT tick can observe what vanilla did. */
    private boolean keyLost;
    /** Draw ticks vanilla had counted at the moment of release, for the RELEASING message. */
    private int drawnAtRelease;

    public HoldController(InteractIntent intent) {
        this.mode = intent.holdMode() == null ? InteractIntent.HoldMode.UNTIL_DONE : intent.holdMode();
        this.holdTicks = intent.holdTicks();
    }

    /** Request cancellation; the next {@link #tick} releases the key and ends CANCELLED. */
    public void requestCancel() {
        this.cancelRequested = true;
    }

    /** Ticks the use key has been asserted so far (for status/tests). */
    public int heldTicks() {
        return heldTicks;
    }

    /** True once a terminal outcome has been produced. */
    public boolean isDone() {
        return done;
    }

    /**
     * Lift the use key if this controller still has it asserted, and mark the hold finished.
     *
     * <p>For the case where the controller is DISCARDED rather than ticked to a conclusion: the
     * applier rebinding to a fresh intent, or otherwise dropping its reference. Everything else this
     * class owns is its own fields, but the key is a static {@code KeyBinding} in the client, so an
     * abandoned assertion outlives the object -- vanilla keeps restarting the use
     * ({@code Minecraft.java:2158}) with nothing driving it, and the slot that could cancel no longer
     * holds this controller.
     *
     * <p>Deliberately NOT a {@code tick} and deliberately no outcome: the caller is not asking the
     * hold to conclude, it is asking it to let go of what belongs to the game. Idempotent, and a
     * no-op once terminal, because every terminal path already released.
     */
    public void releaseIfHolding(ActActuator act) {
        if (done) {
            return;
        }
        // Through finish() so there is ONE place that decides what "let go" means, and it asks the
        // actuator rather than inferring from heldTicks: whether the key is down is a fact the client
        // holds, while a tick count is only a proxy for it.
        //
        // CANCELLED rather than a new outcome state: from this controller's side being rebound IS a
        // cancellation, and the outcome is discarded by the caller anyway -- inventing a fifth state
        // for a value nobody reads would be vocabulary without a consumer.
        finish(act, ActOutcome.cancelled(
                "hold cancelled after " + heldTicks + " ticks: the interact slot was rebound"));
    }

    /** Advance one tick against {@code act}. */
    public ActOutcome tick(ActActuator act) {
        if (done) {
            return ActOutcome.done("already finished");
        }
        if (cancelRequested) {
            // Release rather than just walking away: a hold left asserted would keep the player
            // eating or blocking with nothing driving it, and for a bow the release IS the shot, so
            // the cancel must go through vanilla's stop path to end the draw.
            act.releaseUseKey();
            return finish(act, ActOutcome.cancelled("hold cancelled after " + heldTicks + " ticks"));
        }
        if (!act.inWorld()) {
            return finish(act, ActOutcome.failed("not in world"));
        }
        return switch (state) {
            case STARTING -> start(act);
            case HOLDING -> hold(act);
            case RELEASING -> confirmRelease(act);
        };
    }

    private ActOutcome start(ActActuator act) {
        if (mode == InteractIntent.HoldMode.THEN_RELEASE && holdTicks <= 0) {
            return finish(act, ActOutcome.failed("hold mode THEN_RELEASE needs holdTicks >= 1, got "
                    + holdTicks + "; a bow needs at least " + BOW_MIN_CHARGE_TICKS
                    + " draw ticks to fire anything"));
        }
        if (!act.holdUseKey()) {
            // The write itself could not be made -- no client, or the use binding is missing from
            // KeyBinding's static keyCode hash. Retrying does not fix either, so fail on tick one
            // rather than pumping a key that is not there.
            return finish(act, ActOutcome.failed("could not assert vanilla's use key, so a hold is not "
                    + "possible; nothing was started"));
        }
        heldTicks = 1;

        // A use may already be running when the hold arrives (the human is holding the button, or a
        // previous USE intent just started one). Adopting it is right: the caller asked for the use
        // to be SUSTAINED, and starting a second one would double-consume.
        if (!act.isUsingItem()) {
            if (!act.useItemInAir()) {
                act.releaseUseKey();
                return finish(act, ActOutcome.failed("use rejected in air, so there is nothing to hold "
                        + "(empty hand, no arrows for a bow, or already-full hunger)"));
            }
            if (!act.isUsingItem()) {
                // The stack changed but no use is in progress: an instant item -- a snowball, an
                // ender pearl. The side effect HAPPENED, which is why this says so rather than
                // pretending nothing did, but there is no state to hold and the caller asked for a
                // hold, so this is not the success they requested.
                act.releaseUseKey();
                return finish(act, ActOutcome.failed("the item used instantly and has no use duration to "
                        + "hold; the use did happen, but HOLD is for food, a bow or blocking"));
            }
        }

        initialCount = act.itemInUseCount();
        lastCount = initialCount;

        if (mode == InteractIntent.HoldMode.UNTIL_DONE && initialCount > SELF_TERMINATING_MAX_COUNT) {
            // A bow or a blocking sword: 72000 ticks, so "until done" would hold for an hour. Fail
            // now with the real duration in the message, and release so the draw does not persist
            // unattended. At one tick of draw a bow is far below BOW_MIN_CHARGE_TICKS, so the
            // release fires nothing.
            act.releaseUseKey();
            return finish(act, ActOutcome.failed("this item does not self-terminate: vanilla gave the use "
                    + initialCount + " ticks, so UNTIL_DONE would hold indefinitely -- use "
                    + "THEN_RELEASE with a tick count instead (a bow fires on release)"));
        }

        initialSlot = act.heldSlot();
        state = State.HOLDING;
        return ActOutcome.running("holding the use key, use count " + initialCount
                + "; note vanilla scales movement to 0.2x while an item is in use "
                + "(EntityPlayerSP:788-792), so a walk running alongside this will be slow");
    }

    private ActOutcome hold(ActActuator act) {
        // Read before re-asserting. After re-assertion the read is our own write and says nothing.
        if (!act.useKeyHeld()) {
            // ASK whether the use actually ended; do not infer it from the key. The first version
            // reported "vanilla has already stopped the use", and that is exactly backwards in the
            // most common way to lose the key -- a screen opening. Vanilla's stop branch
            // (Minecraft.java:2118-2122) lives inside "if (currentScreen == null ||
            // currentScreen.allowUserInput)" at Minecraft.java:1829, and allowUserInput is a bare
            // field defaulting false that ONLY GuiInventory and GuiContainerCreative set. So with
            // chat, the pause menu, a chest or a furnace open, the very screen that cleared the key
            // also gates off the code that would have ended the use: the meal keeps ticking and a bow
            // stays drawn, then fires whenever the screen closes. Telling the caller the use had
            // stopped meant telling it the opposite of the truth on the path it will hit most.
            if (!keyLost) {
                // Give vanilla one tick to react before saying which ending this was, because at this
                // instant the two are indistinguishable: we read at the top of the tick and vanilla's
                // stop branch runs later in it, so isUsingItem() is still true either way. Waiting one
                // tick turns a guess into an observation -- the same trade confirmRelease makes.
                // Stop re-asserting meanwhile: re-pressing the key would fight whatever cleared it.
                keyLost = true;
                return ActOutcome.running("the use key was cleared after " + heldTicks
                        + " ticks; waiting one tick to see whether vanilla ends the use");
            }
            if (act.isUsingItem()) {
                return finish(act, ActOutcome.failed("the use key was cleared after " + heldTicks
                        + " ticks but the use is STILL RUNNING, so this hold no longer controls it. "
                        + "Vanilla's own stop branch is gated behind currentScreen == null || "
                        + "allowUserInput (Minecraft.java:1829), so a screen that clears the key also "
                        + "stops vanilla ending the use -- the item keeps being used, and a drawn bow "
                        + "will fire when that screen closes. Close the screen and read act_status, or "
                        + "submit a fresh hold to take the use back over"));
            }
            return finish(act, ActOutcome.failed("the use key was cleared after " + heldTicks
                    + " ticks and the use has ended with it -- most likely the window lost focus "
                    + "while in-game (Minecraft.java:1467-1469 clears every binding, but only when "
                    + "the game had focus)"));
        }

        if (!act.isUsingItem()) {
            // Vanilla ended the use, and which ending it was decides whether the caller believes the
            // food was eaten. Two signals, in order of how much they actually prove:
            //
            // A hotbar switch is OBSERVED, not inferred. It is the mechanism the failure message
            // names, and checking it directly is what closes the window the count band leaves open:
            // in the last few ticks of a meal an interruption and a completion both leave a small
            // count, and picking "completed" there is a false success in the direction that matters.
            int slotNow = act.heldSlot();
            boolean switched = initialSlot >= 0 && slotNow != initialSlot;
            // Only then the clock, for interruptions with no slot change (the stack ran out, was
            // dropped, the player died).
            boolean ranOut = !switched && lastCount <= COMPLETION_COUNT_SLACK;
            act.releaseUseKey();
            if (switched) {
                return finish(act, ActOutcome.failed("the use ended after " + heldTicks
                        + " ticks because the held slot changed from " + initialSlot + " to " + slotNow
                        + ", which clears vanilla's use -- interrupted, not finished, whatever the "
                        + "remaining count (" + lastCount + ") suggests"));
            }
            if (!ranOut) {
                return finish(act, ActOutcome.failed("the use ended after " + heldTicks + " ticks with "
                        + lastCount + " ticks still on its clock, so it was interrupted rather than "
                        + "finished -- something took the item away mid-use"));
            }
            if (mode == InteractIntent.HoldMode.THEN_RELEASE) {
                // Asked to hold N ticks and vanilla finished early: honest success, but the caller's
                // release never happened, and for a bow that is the difference between a shot and a
                // meal, so the message must not read like a release.
                return finish(act, ActOutcome.done("the use completed on its own after " + heldTicks
                        + " ticks, before the requested " + holdTicks
                        + "; no release was needed"));
            }
            return finish(act, ActOutcome.done("use completed after " + heldTicks + " ticks of holding"));
        }

        if (mode == InteractIntent.HoldMode.THEN_RELEASE && heldTicks >= holdTicks) {
            return beginRelease(act);
        }

        int deadline = initialCount + SERVER_FINISH_SLACK_TICKS;
        if (mode == InteractIntent.HoldMode.UNTIL_DONE && heldTicks > deadline) {
            act.releaseUseKey();
            return finish(act, ActOutcome.failed("still using after " + heldTicks + " ticks, but vanilla "
                    + "gave this use only " + initialCount + " ticks -- the server never sent the "
                    + "finish (status id 9), so the use is not going to complete"));
        }

        if (!act.holdUseKey()) {
            return finish(act, ActOutcome.failed("lost the ability to assert the use key after "
                    + heldTicks + " ticks; the hold cannot continue"));
        }
        heldTicks++;
        lastCount = act.itemInUseCount();
        return ActOutcome.running("holding the use key, " + heldTicks + " ticks, use count "
                + lastCount + (mode == InteractIntent.HoldMode.THEN_RELEASE
                        ? " (releasing at " + holdTicks + ")" : ""));
    }

    /**
     * Stop asserting the key, which is what fires a bow.
     *
     * <p>Nothing else is called. The arrow is created in {@code ItemBow.onPlayerStoppedUsing},
     * reached from {@code PlayerControllerMP.onStoppedUsingItem}, which vanilla itself invokes at
     * {@code Minecraft.java:2118-2122} on the next tick where the key reads up -- the very branch
     * that made a one-shot use uncancellable-by-design and is now the mechanism. Calling
     * {@code onStoppedUsingItem} directly as well would fire the release twice through two paths,
     * and the RELEASE_USE_ITEM packet with it.
     */
    private ActOutcome beginRelease(ActActuator act) {
        // Vanilla's own baseline, not ours. ItemBow.java:32 charges on
        // getMaxItemUseDuration(stack) - timeLeft, i.e. ticks since the DRAW began -- while the first
        // version subtracted from initialCount, the count observed when this controller ADOPTED the
        // use. Those agree only when the controller also started it. Adopt a draw a human already
        // began and ours reads short, so a 17-tick draw could be reported as 2 and described as
        // firing nothing while vanilla loosed a near-full arrow. A wrong number stated confidently
        // is worse than no number, because the caller acts on it.
        //
        // Falls back to our own baseline only if the item cannot be asked, so an unavailable duration
        // degrades to the old approximation rather than to zero, which would read as "no draw".
        int maxDuration = act.maxItemUseDuration();
        int baseline = maxDuration > 0 ? maxDuration : initialCount;
        // Sampled fresh, not from lastCount. lastCount is one tick stale by the time the release tick
        // runs, and the draw count is exactly what decides whether an arrow exists: reporting one
        // tick short would put a 3-tick draw (an arrow) below BOW_MIN_CHARGE_TICKS (no arrow).
        drawnAtRelease = baseline - act.itemInUseCount();
        if (!act.releaseUseKey()) {
            return finish(act, ActOutcome.failed("held " + heldTicks + " ticks but the use key could not "
                    + "be released, so vanilla will not end the use"));
        }
        state = State.RELEASING;
        return ActOutcome.running("released the use key after " + heldTicks
                + " ticks (" + drawnAtRelease + " draw ticks), waiting for vanilla to end the use");
    }

    /**
     * Confirm vanilla actually let go, one tick after the release.
     *
     * <p>The extra tick buys an observation rather than an assumption: the release is only real if
     * {@code isUsingItem()} has gone false, and vanilla's stop branch runs later in the tick than
     * this controller does.
     */
    private ActOutcome confirmRelease(ActActuator act) {
        int drawn = drawnAtRelease;
        if (act.isUsingItem()) {
            return finish(act, ActOutcome.failed("released the use key after " + heldTicks
                    + " ticks but the player is still using the item, so vanilla did not end the use "
                    + "-- a bow would not have fired"));
        }
        String charge = drawn < BOW_MIN_CHARGE_TICKS
                ? "; below the " + BOW_MIN_CHARGE_TICKS + " draw ticks a bow needs to fire anything"
                : drawn >= BOW_FULL_CHARGE_TICKS
                        ? "; a bow would be at full charge (" + BOW_FULL_CHARGE_TICKS + "+ ticks)"
                        : "";
        return finish(act, ActOutcome.done("held " + heldTicks + " ticks then released, " + drawn
                + " draw ticks counted by vanilla" + charge));
    }

    /**
     * Produce a terminal outcome, guaranteeing the use key is not left asserted.
     *
     * <p>The release lives HERE rather than at each of the sixteen terminal sites because the key is
     * the one thing this controller owns that outlives it: {@code KeyBinding.pressed} sits in a
     * static binding, so a path that ends without lifting it leaves the client holding right-click
     * with nothing driving the hold -- vanilla restarts the use every tick
     * ({@code Minecraft.java:2158}) and, since the assertion survives world teardown, it is still
     * down when the player next spawns. Two paths shipped with exactly that hole (losing the world
     * mid-hold, and losing the ability to re-assert the key), and auditing sixteen call sites to keep
     * them all correct is the kind of discipline that fails silently on the seventeenth.
     *
     * <p>Guarded on {@code useKeyHeld()} so the paths that already released -- cancel, and the
     * THEN_RELEASE conclusion where the release IS the shot -- are not double-counted, and so a
     * failure before the key was ever asserted does not report a release that never happened.
     */
    private ActOutcome finish(ActActuator act, ActOutcome out) {
        if (out.terminal() && act.useKeyHeld()) {
            act.releaseUseKey();
        }
        done = out.terminal();
        return out;
    }
}
