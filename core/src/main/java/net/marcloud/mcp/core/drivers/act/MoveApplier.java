package net.marcloud.mcp.core.drivers.act;

/**
 * The {@link ActSlot#MOVE} applier: owns the slot's lifecycle and reports what the intent actually
 * achieved. The locomotion itself is applied by {@link ActMovementInput}, which reads the
 * {@link MoveIntentView} on the game thread every tick.
 *
 * <p>A {@link MoveIntent#durationTicks()} of {@code <= 0} means "hold until cancelled or replaced":
 * the slot stays ACTIVE indefinitely. A positive duration completes the slot once that many ticks
 * have been applied, after which {@link ActMovementInput} sees {@code moveActive()==false} and
 * reverts to vanilla.
 *
 * <p><b>Why it reads the world back.</b> This used to report {@code "moving (tick N/M)"} -- a
 * statement about its own bookkeeping, identical for a player crossing open ground and a player
 * pressed against a wall. Measured on a live client, those are very different: an open-loop intent
 * holds a straight line on flat ground (8.408 blocks travelled, 0.04 degrees off the facing yaw)
 * and travels nothing at all into a wall, and {@code act_status} called both of them "moving".
 * Reading {@link ActActuator#position()} makes the slot answer the question a caller actually has,
 * which is whether the intent accomplished anything.
 *
 * <p>Displacement rather than velocity: velocity separates the two states too (measured, the Z
 * component reads 0.09 walking against 0.0 jammed) but it answers "how fast right now" when the
 * question is "did this get anywhere", and it is not on this seam. Displacement since the intent
 * became ACTIVE is also the term a stuck test needs.
 *
 * <p>The actuator is optional. A null one degrades to the old tick-counting message rather than
 * failing, because a status line is not worth breaking locomotion over, and the applier is
 * constructed in {@code McpCore} where the actuator exists anyway.
 */
public final class MoveApplier implements ActApplier {

    /** Ticks of zero displacement while ACTIVE before the slot calls itself stuck. */
    private static final int STUCK_TICKS = 3;

    /**
     * Below this, a tick counts as no movement, in blocks.
     *
     * <p>Not zero: a player settling onto ground or brushing a wall drifts by tiny amounts, and
     * calling that progress would make the stuck test never fire. Walking covers about 0.2 blocks
     * per tick (measured: 4.2 blocks/second), so this is two orders of magnitude below a real step.
     */
    private static final double MOVED_EPSILON = 0.002;

    /**
     * The decision-to-fingertip delay, in ticks; negative until the walk first asks.
     *
     * <p><b>Why this field is here and was not on {@link NavController}.</b> The delay models
     * the gap between a person <i>deciding</i> to put a body in motion and their finger arriving
     * on the key. A decision belongs to a walk, not to a block: nobody re-decides to walk the
     * eighth block of a straight corridor, they decided once and kept going. The delay used to
     * be a per-instance field of {@code NavController}, and {@code RouteExecutor.steeringFor}
     * mints a fresh one <b>per move</b> -- so a per-instance delay is a per-BLOCK delay. Measured
     * on the wire: a 20-block route emitted roughly 20 runs of zero displacement, each about
     * 5.5 ticks of standing still, separated by one block of walking. That is a metronome at a
     * 1.25-block period, and it is a behavioural pathology independent of any anti-cheat: the
     * player visibly stutters at every single block.
     *
     * <p>This applier is the owner because its lifetime is the walk. It binds on intent identity
     * ({@code boundTo}), so one draw is one decision, which is exactly what the field's own
     * documentation always claimed: "one draw per intent", "a new intent earns a new reaction
     * time". The code did not match its contract; the contract was right and the owner was wrong.
     *
     * <p><b>Where the wait is spent.</b> The applier withholds the axes rather than the
     * controller, so the machine keeps running and keeps reporting while the body stands still.
     * That is what makes the hazard still readable during the pause: {@code NavController} scans
     * on its first tick, and the caller polls {@code act_status} throughout. A caller that wants
     * to abort a walk into lava still gets its window, which is the cheapest the walk will ever
     * offer.
     */
    /**
     * Ticks of wait still to be spent on THIS intent's reaction delay, counting down to zero.
     *
     * <p>A countdown and not the draw itself, which is what the wait loop needs: the branch is
     * {@code if (reactionTicks > 0) { reactionTicks--; publish nothing; }}, so the field is
     * decremented once per held tick and reaches zero exactly when the walk may move. Confusing
     * the two is not a style question -- an earlier version of this change reused one field for
     * both, so {@link #drawnReactionDelay()} reported a number that fell by one every tick of the
     * wait. The full suite caught it, and it is the reason the draw is a separate field below.
     */
    private int reactionTicks = -1;

    /**
     * The delay this intent drew, in ticks. Stable for the whole walk.
     *
     * <p>Package-private for the same reason it was on {@code NavController}: the property worth
     * pinning is that this is <b>stable across ticks</b>, and that cannot be checked through the
     * published axes because the observable consequence is a distribution shift of one tick out
     * of five -- a change a statistical test would need hundreds of samples to call.
     */
    private int reactionDelay = -1;

    int drawnReactionDelay() {
        if (reactionDelay < 0) {
            reactionDelay = drawReactionDelay();
        }
        return reactionDelay;
    }

    /**
     * The floor of the delay, in ticks. Below this a key press is not a reaction any more.
     *
     * <p>4 ticks == 200 ms. The floor is cited rather than guessed: Woods et al. 2015
     * (doi 10.3389/fnhum.2015.00131, n = 1469) reports that it "excluded response latencies less
     * than 110 ms and greater than 1000 ms", so its response WINDOW is 110-1000 ms; its own SDT
     * is 131 ms. 4 ticks is well above that window, and deliberately NOT the 109 ms of the
     * literature's own floor: an agent is not an expert, and a number at the fast tail is as much
     * a tell as one at the slow end.
     */
    static final int REACTION_FLOOR_TICKS = 4;

    /**
     * The top of the band: the unskilled end of it, and the ceiling the draw is clipped to.
     */
    private static final int REACTION_CEILING_TICKS = 8;

    /**
     * The scale of the delay's right tail, in ticks.
     *
     * <p>2.0 ticks = 100 ms, and it is set by where the MEAN lands rather than by fitting a
     * published spread. The temptation was Woods et al. 2015's intrasubject SD of 40.0 ms
     * (doi 10.3758/s13428-020-01388-2 is the saccade paper; the reaction one is
     * doi 10.3389/fnhum.2015.00131, n = 1469), which would be 0.8 ticks -- but the band is 4..8
     * with an SD of 1.29 ticks, so matching 0.8 would make the delay MORE regular than a person's
     * own, and a too-regular delay is the same tell as no delay at all. 2.0 puts the mean at
     * 5.54 ticks (277 ms) and leaves the SD at 1.18 -- still wider than a measured human's own
     * trial-to-trial spread.
     */
    static final double REACTION_TAIL_TICKS = 2.0D;

    /**
     * One decision-to-fingertip delay, in ticks, and the shape of the draw is the point.
     *
     * <p><b>What this buys, in one sentence: the walk-start delay is a right-skewed draw with a
     * hard physiological floor, not a uniform band, so the distribution a caller could measure
     * across many walks has the shape a human latency distribution has.</b>
     *
     * <p><b>Why the shape was a defect.</b> ADR-0005 §3 lists the uniform interval as an open
     * item and §6 calls it "the worst choice" -- correctly, and without needing any literature:
     * {@code 4 + rand(5)} is not a sample from any distribution that exists. It is symmetric, so
     * its skewness is exactly zero, and it puts 20% of its mass on the FASTEST possible reaction
     * and 20% on the slowest, which is the shape of a machine enumerating a range.
     *
     * <p><b>Why a half-normal on a floor.</b> A latency is a duration, so it cannot be negative,
     * and human reaction times have a hard lower bound set by the refractory period. A latency is
     * therefore "a floor plus a non-negative, long-tailed excess", and the one-parameter
     * half-normal {@code |N(0, sigma)|} is the smallest model that says exactly that: zero
     * probability below the floor, a spike AT it, and a tail above it. It is a named
     * distribution fitted at its floor and its scale, not an interval someone typed.
     *
     * <p>Measured cost: <b>zero ticks.</b> The support is still exactly 4..8, so every existing
     * guarantee holds unchanged -- one draw per intent (so a cancelled and re-submitted walk is
     * still not faster than the first, which is the property that earns the delay its place), the
     * first key still lands on the tick after the draw, and the walk still starts by tick 8. The
     * distribution is 19.9 / 35.0 / 24.1 / 13.1 / 7.9 per cent across 4..8, with SD 1.18 ticks
     * (59 ms) against the 1.29 (65 ms) a uniform band of the same width has.
     *
     * <p><b>Why this cost is allowed</b> (ADR-0005 classifies every input-layer cost). It is not
     * "because it looks human" -- that framing is the banned one, and the reaction-time figures
     * that once justified it did not survive checking. The property it actually buys is
     * measurable and testable: <b>a cancelled and re-submitted walk is not faster than the
     * original</b>. Without a per-intent draw, a caller could cancel and re-submit to skip the
     * pause, and "faster on the second try" is itself an observable irregularity -- cheaper to
     * prevent than to explain afterwards.
     *
     * <p>What the delay does NOT buy: any resemblance to a measured human reaction time. A
     * reaction time no person has is the single loudest thing about synthetic input, and unlike
     * an axis magnitude it cannot be explained away as "the agent is a machine".
     */
    static int drawReactionDelay() {
        double tail = Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextGaussian())
                * REACTION_TAIL_TICKS;
        int drawn = REACTION_FLOOR_TICKS + (int) Math.round(tail);
        return drawn > REACTION_CEILING_TICKS ? REACTION_CEILING_TICKS : drawn;
    }

    private final ActActuator actuator;

    private double[] origin;
    private double[] last;
    private int stillTicks;
    private ActIntent boundTo;

    /** The machine currently driving the MOVE slot: a NavController, a route executor, or none. */
    private LocomotionController nav;

    /** Lifecycle only; the status line degrades to tick counting and nav is unavailable. */
    public MoveApplier() {
        this(null, null);
    }

    public MoveApplier(ActActuator actuator) {
        this(actuator, null);
    }

    /**
     * @param runtime where computed axes are published; without it a {@link NavIntent} cannot drive
     *                input, so nav fails honestly rather than walking nowhere in silence
     */
    public MoveApplier(ActActuator actuator, ActRuntime runtime) {
        this(actuator, runtime, null);
    }

    /**
     * @param routeFactory builds the machine that executes a {@link RouteIntent}. Injected rather
     *                     than constructed here because the planner package depends on this one, so
     *                     naming its executor would close a package cycle -- {@code McpCore} sees
     *                     both sides and supplies it. Null means routing is unavailable, and a
     *                     RouteIntent then fails saying so instead of silently doing nothing.
     */
    public MoveApplier(ActActuator actuator, ActRuntime runtime,
                       java.util.function.Function<RouteIntent, LocomotionController> routeFactory) {
        this.actuator = actuator;
        this.runtime = runtime;
        this.routeFactory = routeFactory;
    }

    private final ActRuntime runtime;
    private final java.util.function.Function<RouteIntent, LocomotionController> routeFactory;

    @Override
    public SlotRecord apply(SlotRecord current) {
        if (current.intent() instanceof NavIntent ni) {
            return driveLocomotion(current, () -> new NavController(
                    ni.targetX(), ni.targetY(), ni.targetZ(), ni.timeoutTicks()));
        }
        if (current.intent() instanceof RouteIntent ri) {
            if (routeFactory == null) {
                reset();
                return current.withPhase(ActPhase.FAILED,
                        "this applier was built without a route factory, so it cannot plan a route. "
                        + "Accepting the intent and doing nothing would look like a route that never "
                        + "moved, which is the harder failure to diagnose");
            }
            return driveLocomotion(current, () -> routeFactory.apply(ri));
        }
        if (!(current.intent() instanceof MoveIntent mi)) {
            reset();
            publish(null);
            return current.withPhase(ActPhase.FAILED, "MOVE slot given a non-move intent");
        }
        // Nothing published for raw axes: ActRuntime reads them from the intent, which is their
        // single source of truth for the intent's whole lifetime.
        // A fresh submit restarts the measurement: displacement is per-intent, so a replaced intent
        // must not inherit the previous one's origin. The test is ActRuntime.sameIntent, the same
        // one the locomotion path uses and for the same reason -- though for a raw MoveIntent it
        // resolves to identity, because nothing ever republishes a MoveIntent (there is no tactic to
        // stamp onto one), so a caller submitting an identical key press twice in a row is still
        // two decisions and still gets its own measurement.
        if (!ActRuntime.sameIntent(boundTo, current.intent())) {
            boundTo = current.intent();
            origin = read();
            last = origin;
            stillTicks = 0;
        }
        if (current.cancelRequested()) {
            String moved = travelled();
            reset();
            return current.withPhase(ActPhase.CANCELLED,
                    "movement cancelled after " + current.ticksActive() + " ticks" + moved);
        }

        double[] now = read();
        double step = distance(last, now);
        last = now;
        if (step < MOVED_EPSILON) {
            stillTicks++;
        } else {
            stillTicks = 0;
        }

        long tick = current.lastAppliedTick();
        int activeAfter = current.ticksActive() + 1;
        int duration = mi.durationTicks();
        boolean against = actuator != null && actuator.collidedHorizontally();

        if (duration > 0 && activeAfter >= duration) {
            // The jam term belongs on the terminal message too. Without it a bounded move of one to
            // three ticks that walked straight into a wall completed with "moved 0.00 blocks" and
            // never mentioned the wall: the zero was there, but the caller had to infer the cause,
            // and the stuck branch below could not reach a short intent at all because it needs
            // STUCK_TICKS of stillness first. Reported on contact rather than on a tick count here,
            // since an intent that ENDS flush against a wall is worth saying whatever its length.
            String moved = travelled() + (against ? ", against a wall" : "");
            SlotRecord done = current.markActive(tick, "moved for " + activeAfter + " ticks" + moved)
                    .withPhase(ActPhase.COMPLETE,
                            "movement complete after " + activeAfter + " ticks" + moved);
            reset();
            return done;
        }

        // A jam is the one locomotion failure invisible any other way, and collidedHorizontally is
        // already the boolean for it. Reported rather than failed: the caller decides whether to
        // turn, jump or give up, and an intent that fails itself would take that choice away.
        //
        // Still-ticks AND contact, because either alone lies: a player can stand still for reasons
        // that are not a wall (mid-air, sneaking into a corner it is not touching), and can be in
        // contact while sliding along a surface perfectly productively.
        boolean stuck = stillTicks >= STUCK_TICKS && against;
        String state = stuck
                ? "stuck against a wall for " + stillTicks + " ticks"
                : "moving (tick " + activeAfter + (duration > 0 ? "/" + duration : "") + ")";
        return current.markActive(tick, state + travelled());
    }

    /**
     * Drive a {@link NavIntent} through a {@link NavController}.
     *
     * <p>The controller is cached against intent identity, the same convention the rest of the
     * package uses, so a resubmit starts a fresh walk while a held intent keeps its progress. Its
     * axes are published every tick because they change every tick -- that is the whole difference
     * between stating a destination and stating an input.
     */
    /**
     * Drive any {@link LocomotionController} for one tick.
     *
     * <p>One body for both nav and routing, because the applier's part is identical: bind on a fresh
     * intent, tick, publish the axes, funnel a terminal outcome into the slot. The alternative was a
     * near-copy per machine, and a near-copy is how this repo's block-name rule reached six
     * implementations with three different answers.
     *
     * <p>{@code make} is a supplier rather than an instance so construction happens only on a FRESH
     * intent -- building one per tick would restart the machine every tick and it would never make
     * progress, which is the same identity rule the look and hold channels already obey.
     */
    private SlotRecord driveLocomotion(SlotRecord current,
                                       java.util.function.Supplier<LocomotionController> make) {
        if (actuator == null || runtime == null) {
            reset();
            return current.withPhase(ActPhase.FAILED,
                    "locomotion needs an actuator and a runtime to publish through; this applier "
                    + "was built without them, and walking nowhere in silence would be worse");
        }
        // "The same walk", not "the same object". This applier republishes the intent every tick
        // to stamp the chosen tactic onto it, and withTactic returns a NEW RouteIntent, so an
        // identity test would answer "fresh intent" on every single tick of a routed walk: the
        // machine would be rebuilt per tick, the reaction draw re-rolled, the frozen lane bearing
        // lost and the wedge-recovery budget reset, and the walk would never move. The key is
        // ActRuntime.sameIntent, which compares the GOAL for a route and identity for everything
        // else -- so a caller replacing a running walk with a different one still gets a fresh
        // delay, and a per-tick republication of the same goal does not.
        if (!ActRuntime.sameIntent(boundTo, current.intent())) {
            boundTo = current.intent();
            nav = make.get();
            // A fresh intent is a fresh DECISION, and a decision earns a fresh delay. Drawn here
            // rather than per machine, because the machine may be replaced many times inside one
            // intent (RouteExecutor builds a NavController per move) and every one of those
            // replacements is bookkeeping, not a decision.
            //
            // Drawn UNCONDITIONALLY, and that word is load-bearing. Calling the lazy accessor here
            // reuses whatever the previous walk left behind whenever that walk did not reach a
            // terminal outcome -- a caller that replaced a still-running intent, which is a
            // normal thing to do, then got the previous walk's exact onset latency. Two walks
            // with the same number is a constant reaction time, which is a louder tell than any
            // single value being wrong; the twoWalksInARowDoNotShareOneDrawnDelay test measured
            // 200 of 200 pairs identical before this line was fixed.
            reactionDelay = drawReactionDelay();
            reactionTicks = reactionDelay;
        }
        if (current.cancelRequested()) {
            nav.requestCancel();
        }

        ActOutcome out = nav.tick(actuator);
        if (out.terminal()) {
            publish(null);
            LocomotionController finished = nav;
            // Stamped BEFORE reset(), and off `finished` rather than `nav`, because reset() drops
            // the machine. This is the tick whose tactic is the walk's only summary: the terminal
            // one carries the lane the body died on and the ARRIVED_AFTER_A_LANE / LIMITS_REACHED
            // distinction, and a record that lost it here would leave the caller with a failure and
            // no account of what it cost.
            SlotRecord done = stamp(current, finished);
            reset();
            // The hazard goes into the slot on the terminal path as well as the running one. A walk
            // that ended in lava is exactly the walk whose warning mattered, and a terminal status
            // line that says only "gave up" leaves the caller with a failure and no cause -- which
            // is the shape audit 2.3 was written against.
            //
            // And the GRADE goes in beside it, which is the same argument one level up: the
            // terminal line is the one a caller reads most, so it is the one where dropping the
            // grade costs the most. A route refusal that counted 412 unread cells reaches this
            // method as a genuine Belief.UNKNOWN on the outcome, and before this call the record
            // published UNGRADED for it -- the same value it publishes for every line no site has
            // examined. The refusal's whole point is that it is a statement about THIS CLIENT, and
            // a caller reading act_status could not tell that apart from a line nobody looked into.
            return done.markActive(current.lastAppliedTick(), out.message(), out.hazard())
                    .withPhase(out.state(), out.message() + " (" + finished.ticks() + " ticks)")
                    .withBelief(out.belief())
                    // And the COUNT travels the same two lines, because a count that stops at the
                    // outcome is the same defect the grade had: a caller that can read the grade and
                    // not the number learns THAT the search could not see enough and still not HOW
                    // MUCH, which is a caller forced back to substring-matching the sentence.
                    .withUnreadCells(out.unreadCells());
        }

        // The walk is standing still for its reaction time. Withhold the AXES, not the tick: the
        // machine above has already run, so the position is being read, the hazard is already
        // scanned and reported, and arrival is still being tested. Only the keys are held back,
        // which is what a person is doing -- deciding, then moving.
        //
        // The wait is spent BEFORE anything is published rather than by calling stop() on the
        // controller, and that is the whole point: stop() would zero the axes and reset nothing
        // about the machine, which is right, but the decision of WHEN to hold the keys belongs to
        // the thing that knows when the walk began. RouteExecutor walks twenty moves under one
        // intent, and a per-machine pause stopped the body twenty times.
        if (reactionTicks > 0) {
            reactionTicks--;
            // Withhold the DIRECTION keys and nothing else.
            //
            // Forward and strafe are the decision: a person picks a heading and presses, and the
            // press is what the latency delays. Sneak and sprint are modifiers the old pause also
            // published, and releasing the sneak key and re-pressing it is a bigger tell than the
            // pause it interrupts (Entity.moveEntity:626-662 reads it as an edge guard).
            //
            // JUMP is passed through, and that is a correction rather than a convenience. The
            // delay used to live on NavController, which is the GROUND WALKER, so it never
            // touched a swimmer. Hoisting it to the applier widened it to every kind of
            // locomotion, and holding jump back for 5 ticks makes a body in water SINK:
            // vanilla's swim-up is jump held (EntityLivingBase:2010-2013 into
            // updateAITick:1591), not a key pressed once, so there is no onset to delay and no
            // pause to spend. Withholding it broke WaterIsSwumAndTheDrowningWarningSurvives
            // -- a capability regression bought for a cosmetic one, which ADR-0005 forbids.
            publish(new ActRuntime.LocomotionAxes(0f, 0f, nav.jump(),
                    creeping(current, nav), gaitOf(current, Gait.SPRINT)));
            // The machine's own message is kept and PREFIXED rather than replaced, because it is
            // the only thing in the system that knows how far is left, and "reacting" with no
            // distance is a strictly worse status line than the one it replaces. A caller
            // watching a walk toward lava needs both facts: the walk has not started, and there
            // are 18 blocks of lava between here and the goal.
            //
            // The grade AND the count ride the reaction line too, because this sentence QUOTES the
            // machine's own -- "reacting, first step at tick 1 of 5 -- <machine's sentence>". A
            // quoted claim inherits the quoted one's evidence, and dropping the grade here would let
            // a walk's REUSED_CELL or READ_CAME_BACK_EMPTY answer be laundered into nothing on
            // exactly the ticks where a caller is deciding whether to cancel.
            return current.markActive(current.lastAppliedTick(),
                    String.format(java.util.Locale.ROOT,
                            "reacting, first step at tick %d of %d -- %s",
                            current.ticksActive() + 1, drawnReactionDelay() + 1, out.message()),
                    out.hazard())
                    .withBelief(out.belief())
                    .withUnreadCells(out.unreadCells());
        }
        // Sprint stays off: vanilla derives it from moveForward in onLivingUpdate and scales
        // movement while an item is in use, so mixing it in here would make the controller's own
        // step measurements depend on state it does not own. Jump, by contrast, is the controller's
        // to decide: it is the only axis whose need is a fact about the ROUTE (a one-block rise
        // that 0.6 of step height cannot walk), and one of the two machines that arrive here has no
        // other way to ask for it.
        publish(new ActRuntime.LocomotionAxes(nav.forward(), nav.strafe(), nav.jump(),
                creeping(current, nav), gaitOf(current, Gait.SPRINT)));
        // The grade rides the running path too, and for the reason the terminal one does rather
        // than a copy of it: a caller polling act_status during a walk is reading a claim it may
        // act on, and "moving, 18 blocks to go" over terrain this client could not read is a
        // different sentence from the same one over terrain it could. Grading only the terminal
        // line would leave the poll -- the path a caller uses to decide whether to cancel -- with
        // nothing to weigh the claim by.
        return stamp(current, nav).markActive(current.lastAppliedTick(), out.message(),
                out.hazard()).withBelief(out.belief()).withUnreadCells(out.unreadCells());
    }

    /**
     * This tick's record carrying the tactic the machine just chose: a fresh intent when there is
     * something to say, and the record untouched when there is not.
     *
     * <p><b>Per tick, and the granularity is the contract rather than a preference.</b>
     * {@link MoveTactic} promises that its axes are the axes the controller published <i>on the
     * same tick</i>, and a record that disagrees with the fingers is documented as worse than no
     * record at all. Per decision point would leave the intent holding a tactic from three ticks
     * ago while the body is on the fortieth; per move is coarser still and has the same defect. So
     * the invariant worth having is the one this makes true: <b>whenever a tactic is present, it
     * describes the axes published by the same {@code apply} call.</b>
     *
     * <p><b>That is why the reaction-wait branch above does not stamp.</b> The machine has run and
     * holds a tactic, but this applier is deliberately withholding the direction keys, so the axes
     * reaching the body are zero while the controller's own are a bearing. Stamping there would put
     * a record beside the fingers saying otherwise, which is the exact failure the granularity
     * argument exists to prevent. Leaving the intent alone there is not a gap: the wait only ever
     * occupies the first ticks of a walk, where the intent is still the caller's own and carries
     * no tactic -- and "nothing has been pressed yet" is exactly what null says.
     *
     * <p>Only a {@link RouteIntent} is stamped. A {@link NavIntent} has no field to stamp into, and
     * adding one changes a different record's published shape rather than answering this one; a
     * point walk's decision stays readable through {@link LocomotionController#tactic()} on the
     * machine itself.
     */
    private static SlotRecord stamp(SlotRecord current, LocomotionController machine) {
        MoveTactic chosen = machine == null ? null : machine.tactic();
        if (chosen == null || !(current.intent() instanceof RouteIntent route)) {
            return current;
        }
        return current.withIntent(route.withTactic(chosen));
    }

    /**
     * Whether the caller asked to hold the sneak key, independent of what the steering is doing.
     *
     * <p>It used to be published as a hardcoded {@code false}, which made {@code MoveIntent.sneak}
     * a field that exists, is parsed, is validated, and reaches nothing. Vanilla reads that same
     * input for two things that are not the walk: the slower gait
     * ({@code EntityPlayerSP:692-698}) and the EDGE GUARD at {@code Entity.moveEntity:626}, which
     * is the only thing stopping a sneaking player from walking off a ledge. A caller asking to
     * creep along a cliff edge had no way to say so, and the game was told the key was up.
     *
     * <p>Read from the intent rather than taken from the nav because {@link NavController} has no
     * sneak of its own to override.
     *
     * <p><b>One read for every intent in the slot, not one per kind.</b> Raw axes are the obvious
     * case and were the only one, which is why {@link NavIntent} and {@link RouteIntent} could ask
     * for a creep and be silently ignored -- the exact defect this method existed to fix, one level
     * up. A destination and a key press are different ways of naming the same gait, and both end up
     * here as a boolean the body reads; splitting the read per intent type is how a capability ends
     * up working for the form someone tested and silently absent for the two nobody did.
     *
     * <p>Sprint stays {@link MoveIntent}-only, deliberately. Vanilla derives it from
     * {@code moveForward} every tick in {@code onLivingUpdate} and scales movement by whether an
     * item is in use, so a route cannot own it without the controller's own step measurements
     * depending on state it does not hold. Sneak has no such second meaning: 1.8.9 applies it to
     * the input directly, and {@code EntityPlayerSP.isSneaking():684-687} is
     * {@code movementInput.sneak && !sleeping} -- the key and nothing else.
     *
     * <p>The gait is named rather than passed as a {@code Predicate}, because the first version of
     * this widened the predicate and then asked {@code key == MoveIntent::sneak} which question it
     * had been handed. Method references are not interned: a non-capturing reference is only
     * guaranteed to be equal to another instance if they come from the SAME expression, so that
     * comparison would have silently answered {@code false} for every call and the creep would
     * never have reached the body. Naming the gait makes the question answerable at all.
     */
    private enum Gait {
        /** The vanilla sneak key: slower, and the only thing that stops a walk at a brink. */
        SNEAK,
        /** The sprint key, which {@link MoveIntent} alone can own. */
        SPRINT
    }

    private static boolean gaitOf(SlotRecord slot, Gait gait) {
        ActIntent intent = slot.intent();
        if (intent instanceof MoveIntent mi) {
            return gait == Gait.SNEAK ? mi.sneak() : mi.sprint();
        }
        if (gait != Gait.SNEAK) {
            return false;
        }
        if (intent instanceof NavIntent ni) {
            return ni.sneak();
        }
        return intent instanceof RouteIntent ri && ri.creeping();
    }

    /**
     * Whether the sneak key goes down this tick: what the caller asked for, OR what the machine
     * says about the ground it is standing on.
     *
     * <p>The two are different questions and both are needed, which is why this is not just
     * {@link #gaitOf}. A {@link RouteIntent} is asked "creep all of it?" and a caller who says no
     * still gets creeps on the moves that end at a brink, because {@code Move.creep()} is a fact
     * about a cell the planner read and the caller never saw. A {@link NavIntent} is a point with
     * no plan, so there is nothing to know and the caller's answer is the whole answer.
     *
     * <p>ORed rather than one overriding the other, and specifically NOT allowed to be overridden
     * by a caller's {@code sneak:false}: "do not creep" cannot mean "walk off the cliff I asked you
     * to reach", and a planner that inferred creeps a caller did not know about has to be able to
     * hold the key without being contradicted. The cost of that is that a caller cannot force a
     * full-speed walk along a brink -- which is the one thing it could not do safely anyway.
     */
    private boolean creeping(SlotRecord current, LocomotionController machine) {
        return gaitOf(current, Gait.SNEAK) || (machine != null && machine.creeping());
    }

    private void publish(ActRuntime.LocomotionAxes next) {
        if (runtime != null) {
            runtime.publishAxes(next);
        }
    }

    private double[] read() {
        return actuator == null ? null : actuator.position();
    }

    /** {@code ", moved N.NN blocks"}, or empty when there is nothing trustworthy to say. */
    private String travelled() {
        if (origin == null || last == null) {
            return "";
        }
        return String.format(java.util.Locale.ROOT, ", moved %.2f blocks", distance(origin, last));
    }

    private static double distance(double[] a, double[] b) {
        if (a == null || b == null) {
            return 0.0;
        }
        // Horizontal only: falling is not progress toward a destination, and counting it would make
        // a player dropping down a shaft look like it was walking.
        double dx = b[0] - a[0];
        double dz = b[2] - a[2];
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void reset() {
        boundTo = null;
        nav = null;
        origin = null;
        last = null;
        stillTicks = 0;
        // reactionTicks and reactionDelay are deliberately NOT cleared here, and the reasoning is
        // the same as the paragraph NavController used to carry: the bind above draws
        // UNCONDITIONALLY on every fresh intent, so a reset here would be a second source of
        // truth about when a walk begins. Clearing them bought nothing and cost a real defect --
        // a version that cleared the countdown but left the draw in place had every walk still
        // paying a pause, so "does it pause again" stayed green while 200 of 200 consecutive
        // walks came out with the SAME onset latency. The bind is the only place that draws.
    }
}
