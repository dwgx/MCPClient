package net.marcloud.mcp.core.drivers.act;

/**
 * Pure state machine for the single-shot interactions: {@link InteractIntent.Kind#USE},
 * {@link InteractIntent.Kind#PLACE}, {@link InteractIntent.Kind#ATTACK} and
 * {@link InteractIntent.Kind#RELEASE}. Ticked by the INTERACT applier through an
 * {@link ActActuator}; it never marshals threads.
 *
 * <p>Most of these interactions complete (or fail) in a single tick — there is no
 * multi-tick progress like digging. The two that are not say so here rather than
 * surprising the applier:
 *
 * <ul>
 *   <li><b>PLACE</b> → {@link ActActuator#rightClickBlock} against the target face at
 *       the hit offset. No block target → honest fail.
 *   <li><b>USE</b> → {@code rightClickBlock} if a block target is set, else
 *       {@link ActActuator#useItemInAir()}.
 *   <li><b>ATTACK</b> → reach-check the entity, then {@link ActActuator#attackEntity}
 *       + {@link ActActuator#swing()}. No target / out of reach → fail with NO
 *       actuator call (never send an attack we know the game will reject). In
 *       {@link InteractIntent.AttackMode#CRIT} it first WAITS for the falling instant
 *       {@link CritWindow} names, which is the only multi-tick part of a swing.
 *   <li><b>RELEASE</b> → {@link ActActuator#releaseUseKey()}, then confirm on the next
 *       tick that vanilla ended the use rather than inferring it from the write.
 * </ul>
 */
public final class InteractController {

    /**
     * Ticks a {@link InteractIntent.AttackMode#CRIT} attack will wait for the falling instant.
     *
     * <p>A human's critical hit is a jump off something and a swing at the bottom of the arc, and
     * the arc is short: the window in which vanilla's conjunction holds lasts about a tick. Twenty
     * ticks is one second, and a jump's own rise is about 12, so it is long enough for the jump the
     * caller asked the MOVE slot to make and short enough that a crit aimed at a wall fails while
     * the caller is still watching.
     *
     * <p>It bounds WAITING, not the attack: the swing goes the first tick the window is open, and a
     * caller whose window is open on arrival spends one tick.
     */
    static final int CRIT_WAIT_TICKS = 20;

    private final InteractIntent intent;
    private boolean done;
    private int critTicksWaited;
    private int releaseTicks;
    /** Why the last tick passed without swinging, so the refusal can name the live reason. */
    private String critBlockedBy = "";

    public InteractController(InteractIntent intent) {
        this.intent = intent;
    }

    /** True once a terminal outcome has been produced. */
    public boolean isDone() {
        return done;
    }

    /**
     * Advance one tick against {@code act}.
     *
     * <p>Terminal in a single step for every kind except a {@code CRIT} attack and a {@code RELEASE},
     * both of which return {@code running()} while they wait. The applier keeps ticking a slot whose
     * outcome is not terminal, so no other change was needed to make them multi-tick.
     */
    public ActOutcome tick(ActActuator act) {
        if (!act.inWorld()) {
            return finish(ActOutcome.failed("not in world", ActOutcome.READ_DIRECTLY));
        }
        return switch (intent.kind()) {
            case PLACE -> place(act);
            case USE -> use(act);
            case ATTACK -> attack(act);
            case RELEASE -> release(act);
            default -> finish(ActOutcome.failed("interact kind " + intent.kind()
                    + " is not handled by InteractController", ActOutcome.READ_DIRECTLY));
        };
    }

    /**
     * Let go, and confirm vanilla let go.
     *
     * <p>Confirmation costs a tick and is the whole point. At the instant of the release the two
     * available readings are indistinguishable: {@code releaseUseKey} writes a key binding, and
     * vanilla's own stop branch ({@code Minecraft.java:2118-2122}) runs later in the same tick, so
     * {@code isUsingItem()} still answers true either way. The first tick therefore reports progress
     * and the second asks the question that has an answer, rather than inferring the release from
     * the key this class just wrote — which would be the controller confirming its own write.
     *
     * <p>Failing a release that did not take is deliberate. A block still up because something else
     * holds the key is a state the caller must hear about, and "released" to a player who is still
     * blocking is worse than silence.
     */
    private ActOutcome release(ActActuator act) {
        if (releaseTicks == 0) {
            if (!act.releaseUseKey()) {
                return finish(ActOutcome.failed("the use key could not be released, so vanilla's use key "
                        + "still reads down and whatever was being held is still being held",
                        ActOutcome.READ_DIRECTLY));
            }
            releaseTicks = 1;
            // READ_DIRECTLY, and the restraint is the point: the sentence names what this
            // controller DID and explicitly declines to report the release as confirmed, because at
            // this instant vanilla's own stop branch has not run yet.
            return ActOutcome.running("use key released; waiting for vanilla to end the use before "
                    + "reporting it as released", ActOutcome.READ_DIRECTLY);
        }
        if (act.isUsingItem()) {
            return finish(ActOutcome.failed("the use key is up but vanilla is STILL using an item, so "
                    + "the use did not end. Its stop branch is gated behind currentScreen == null || "
                    + "allowUserInput (Minecraft.java:1829), and allowUserInput defaults false, so a "
                    + "screen that cleared the key also stops vanilla ending the use -- close the "
                    + "screen, or read act_status: the item is still being used",
                    ActOutcome.READ_DIRECTLY));
        }
        // The confirmation the extra tick was bought for: `isUsingItem()` read false on a LATER tick
        // than the release, so this is an observation of vanilla's own stop branch having run, not
        // an inference from the key this class wrote.
        return finish(ActOutcome.done("released the use; vanilla is no longer using an item",
                ActOutcome.READ_DIRECTLY));
    }

    private ActOutcome place(ActActuator act) {
        if (!intent.hasBlock()) {
            return finish(ActOutcome.failed("place needs a target block face",
                    ActOutcome.READ_DIRECTLY));
        }
        ActActuator.Face face = ActActuator.Face.fromIndex(intent.face());
        boolean ok = act.rightClickBlock(intent.blockX(), intent.blockY(), intent.blockZ(),
                face, intent.hitX(), intent.hitY(), intent.hitZ());
        if (ok) {
            // READ_DIRECTLY, and the WORDING is what earns it: "placed/activated AGAINST" claims the
            // click was issued at these coordinates, which is exactly what the actuator's return
            // value reports. It does NOT claim a block appeared -- the same discipline
            // RouteExecutor.placedSince applies when it goes back and reads the cell, and the reason
            // this sentence can be OBSERVED where "placed a block at" could not.
            return finish(ActOutcome.done("placed/activated against ("
                    + intent.blockX() + "," + intent.blockY() + "," + intent.blockZ() + ")",
                    ActOutcome.READ_DIRECTLY));
        }
        // DERIVED_FROM_READS: the refusal compares what the click asked for against the coordinates
        // the sentence prints, and vanilla refusing out-of-reach placements means a false here does
        // not name which of several causes applied.
        return finish(ActOutcome.failed("place rejected at ("
                + intent.blockX() + "," + intent.blockY() + "," + intent.blockZ() + ")",
                ActOutcome.DERIVED_FROM_READS));
    }

    private ActOutcome use(ActActuator act) {
        boolean ok;
        String where;
        if (intent.hasBlock()) {
            ActActuator.Face face = ActActuator.Face.fromIndex(intent.face());
            ok = act.rightClickBlock(intent.blockX(), intent.blockY(), intent.blockZ(),
                    face, intent.hitX(), intent.hitY(), intent.hitZ());
            where = "on block (" + intent.blockX() + "," + intent.blockY() + "," + intent.blockZ() + ")";
        } else {
            ok = act.useItemInAir();
            where = "in air";
        }
        // DERIVED_FROM_READS: "used item" and "use rejected" are both verdicts drawn from the
        // actuator's boolean, and the sentence's `where` clause is chosen by the branch rather than
        // read. Nothing stale is involved, but a verdict is a conclusion.
        return finish(ok ? ActOutcome.done("used item " + where, ActOutcome.DERIVED_FROM_READS)
                : ActOutcome.failed("use rejected " + where, ActOutcome.DERIVED_FROM_READS));
    }

    private ActOutcome attack(ActActuator act) {
        if (intent.entityId() < 0) {
            return finish(ActOutcome.failed("attack needs a target entity",
                    ActOutcome.READ_DIRECTLY));
        }
        double[] target = act.entityEyePos(intent.entityId());
        if (target == null) {
            // READ_CAME_BACK_EMPTY, and this is the design's 2.C #15 on the entity seam:
            // `entityEyePos` returns null for an id the client does not hold, which is the same
            // shape as `blockAt` returning null for an unloaded chunk. "The entity is gone" is a
            // statement about what this seam produced, and a caller that reads it as "it is dead"
            // would be reading a fact about the client's world rather than about the world.
            return finish(ActOutcome.failed("attack target entity " + intent.entityId() + " is gone",
                    ActOutcome.READ_CAME_BACK_EMPTY));
        }
        double[] eye = act.eyePos();
        if (eye != null) {
            double dx = target[0] - eye[0];
            double dy = target[1] - eye[1];
            double dz = target[2] - eye[2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > act.reachDistance()) {
                // Design 2.B #13, at the site that makes the claim, and the grade is the one the
                // design names. The CLIENT's pre-check is `getBlockReachDistance()` = 4.5 while the
                // SERVER's rule is the square of `canEntityBeSeen ? 9.0 : 36.0` (EntityCombat:45-47
                // into EntityView:18-20), so the client's own distance is four times conservative
                // when the server can see the player and exactly half when it cannot. The check is
                // INFERRED: an arithmetic comparison over two this-tick position reads against a
                // client-side constant standing in for a server-side rule this client cannot see.
                //
                // Fail WITHOUT calling attackEntity -- do not send an attack the game rejects.
                return finish(ActOutcome.failed("entity " + intent.entityId() + " is out of reach",
                        ActOutcome.DERIVED_FROM_READS));
            }
        }

        // After the reach check and before the swing, because a swing costs the attack cooldown and
        // a reach failure is free. A target that has walked away must not also be spent.
        if (intent.attackMode() == InteractIntent.AttackMode.CRIT) {
            ActOutcome verdict = awaitCritWindow(act);
            if (verdict != null) {
                return verdict;
            }
        }

        boolean ok = act.attackEntity(intent.entityId());
        if (!ok) {
            return finish(ActOutcome.failed("attack on entity " + intent.entityId() + " was refused",
                    ActOutcome.READ_DIRECTLY));
        }
        act.swing();
        // The plain attack is READ_DIRECTLY -- the click was issued and the actuator says so. The
        // CRIT arm is the design's 2.B #10 and it is the interesting one: `CritWindow.open` returns
        // true when nothing the CLIENT can see forbids a crit, which its own javadoc states "is NOT
        // 'a crit will happen'" (three of vanilla's seven terms are off this seam entirely). So the
        // swing is built on a belief the class declares incomplete, and the sentence says so in
        // words -- which is exactly the prose this grade makes machine-readable.
        return finish(intent.attackMode() == InteractIntent.AttackMode.CRIT
                ? ActOutcome.done("swung at entity " + intent.entityId() + " while falling, which is "
                        + "the body state a critical hit needs. The 1.5x itself is the SERVER's to "
                        + "apply (EntityPlayer:1335) and no packet reports it back, so this is a "
                        + "timed swing, NOT a confirmed crit", ActOutcome.DERIVED_FROM_READS)
                : ActOutcome.done("attacked entity " + intent.entityId(),
                        ActOutcome.READ_DIRECTLY));
    }

    /**
     * Advance the crit wait; true the tick the window is open.
     *
     * <p>Reads the body fresh every tick rather than deciding once, because the whole technique is
     * a moving target: the caller is usually mid-jump when it submits, and the window opens a few
     * <p>Returns null the tick the swing may go, and the OUTCOME otherwise -- both the progress
     * and the terminal refusal. Returning a bare boolean had the caller overwrite the refusal with
     * its own progress line, so the slot carried on ticking a controller that had already failed
     * and a bounded wait never actually ended.
     */
    private ActOutcome awaitCritWindow(ActActuator act) {
        String disproof = CritWindow.firstDisproof(act);
        if (disproof == null) {
            return null;
        }
        critBlockedBy = disproof;
        critTicksWaited++;
        if (critTicksWaited >= CRIT_WAIT_TICKS) {
            // Refuse rather than swing anyway: the swing is the expensive half, and vanilla's
            // conjunction is the only thing that makes this attack worth waiting for.
            //
            // READ_DIRECTLY, and this is the strongest case for it in the class. `critBlockedBy`
            // was set THIS TICK from `CritWindow.firstDisproof`, which disproves vanilla's
            // conjunction by naming the first term that fails -- a live read of `onGround`,
            // `fallDistance`, `onClimbable` or `inWater`, with no derivation and nothing stale. The
            // sentence reports which term failed and this controller is certain of it.
            return finish(ActOutcome.failed("no critical hit after waiting " + CRIT_WAIT_TICKS
                    + " ticks -- " + critBlockedBy + ". " + CritWindow.description()
                    + ". Nothing was swung: to do this, put the jump on the MOVE slot first and then "
                    + "attack as the body starts down, or use plain 'attack' if a normal hit is enough",
                    ActOutcome.READ_DIRECTLY));
        }
        // DERIVED_FROM_READS for the same reason, one step weaker: the disproof is a this-tick read,
        // but the sentence pairs it with a tick count and a progress claim about a window that has
        // not opened. "Waiting 4/20 ticks for a critical hit: on the ground" is a statement about
        // this controller's patience, not about a body state anyone observed standing still.
        return ActOutcome.running("waiting " + critTicksWaited + "/" + CRIT_WAIT_TICKS
                + " ticks for a critical hit: " + critBlockedBy, ActOutcome.DERIVED_FROM_READS);
    }

    private ActOutcome finish(ActOutcome outcome) {
        this.done = outcome.terminal();
        return outcome;
    }
}
