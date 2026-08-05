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
        // must not inherit the previous one's origin. Identity is the same test LookApplier uses.
        if (boundTo != current.intent()) {
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
        if (boundTo != current.intent()) {
            boundTo = current.intent();
            nav = make.get();
        }
        if (current.cancelRequested()) {
            nav.requestCancel();
        }

        ActOutcome out = nav.tick(actuator);
        if (out.terminal()) {
            publish(null);
            LocomotionController finished = nav;
            reset();
            return current.markActive(current.lastAppliedTick(), out.message())
                    .withPhase(out.state(), out.message() + " (" + finished.ticks() + " ticks)");
        }
        // Sprint stays off: vanilla derives it from moveForward in onLivingUpdate and scales
        // movement while an item is in use, so mixing it in here would make the controller's own
        // step measurements depend on state it does not own.
        publish(new ActRuntime.LocomotionAxes(nav.forward(), nav.strafe(), false, false, false));
        return current.markActive(current.lastAppliedTick(), out.message());
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
    }
}
