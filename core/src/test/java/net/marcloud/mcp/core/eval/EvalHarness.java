package net.marcloud.mcp.core.eval;

import java.util.Locale;

import net.marcloud.mcp.core.drivers.act.ActActuator;
import net.marcloud.mcp.core.drivers.act.ActIntent;
import net.marcloud.mcp.core.drivers.act.ActMovementInput;
import net.marcloud.mcp.core.drivers.act.ActPhase;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.drivers.act.ActSlot;
import net.marcloud.mcp.core.drivers.act.ActTickLoop;
import net.marcloud.mcp.core.drivers.act.InteractApplier;
import net.marcloud.mcp.core.drivers.act.LocomotionController;
import net.marcloud.mcp.core.drivers.act.LookApplier;
import net.marcloud.mcp.core.drivers.act.MoveApplier;
import net.marcloud.mcp.core.drivers.plan.Planner;
import net.marcloud.mcp.core.drivers.plan.RouteExecutor;
import net.marcloud.mcp.core.drivers.plan.Stance;
import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.event.events.TickEvent;
import net.minecraft.util.MovementInput;

/**
 * The real actuation stack, wired to {@link SimWorld} and driven one tick at a time.
 *
 * <p><b>Nothing here re-implements a controller.</b> Every moving part is the production class the
 * live client runs: {@link ActRuntime} for slot state, {@link ActTickLoop} for the per-tick
 * dispatch, {@link MoveApplier}/{@link LookApplier}/{@link InteractApplier} to drive the
 * controllers, {@link Planner} and {@link RouteExecutor} for routing, and {@link ActMovementInput}
 * as the only thing standing between the runtime's axes and the player's body. The wiring is
 * {@code McpCore}'s wiring with the game handle swapped for the simulator, which is the whole
 * reason the seam exists: a regression in the real controller has to show up HERE, and it cannot
 * if the eval owns a second copy of the arithmetic.
 *
 * <p><b>What is genuinely absent, and why it is not a reimplementation.</b> Three things the live
 * path reaches for are not in the game-thread chain and are supplied from outside it:
 * {@code MovementInputFromOptions} (keyboard state, which no agent has), the
 * {@code GameAccess}/{@code World} handle that {@code RoutePlanning} plans against, and the
 * {@code EventBus}. Each is replaced by the thing it stands for -- a {@link MovementInput} that
 * reports no keys down, a {@link Planner} built on the same {@link SimWorld} the player stands in,
 * and a direct {@link ActTickLoop#onTick} call. The route factory in particular is the ONE place
 * the eval supplies a policy: {@code McpCore} builds it from the SERVER world, and the simulator
 * has one world, so the eval plans against the same {@link SimWorld} the player walks in. That is
 * the same view a single-player client has, which is the honest analogue.
 *
 * <p><b>Tick order is the real one.</b> {@code Minecraft.runTick} advances the clock and publishes
 * {@link TickEvent} at the runTick seam, and the appliers run on the game thread from that
 * subscription; the player then integrates its body in {@code onLivingUpdate}. So each
 * {@link #tick()} is: advance the clock, drive every slot, then integrate the body. Reverse that
 * and the axes a controller publishes would be read one tick late, which is precisely the class of
 * bug this harness exists to catch and would hide.
 *
 * <p><b>One world, one run.</b> The harness never re-rolls: {@link #tick()} is the only thing that
 * advances anything, and a task that fails fails on the world it actually built. See
 * {@code _scratch/eval/EvalRunner} for the runner that refuses to re-roll.
 */
public final class EvalHarness {

    private final SimWorld world;
    private final GameClock clock;
    private final ActRuntime runtime;
    private final ActTickLoop loop;
    private final MovementInput input;

    /** The plan the most recent {@code RouteIntent} produced, for a task that wants to inspect it. */
    private Planner.Plan lastPlan;

    public EvalHarness(SimWorld world) {
        this.world = world;
        // A private clock, never GameClock.INSTANCE: the singleton is process-wide and a sibling
        // test's ticks would move this harness's effective-tick gate.
        this.clock = new GameClock();
        this.runtime = new ActRuntime(clock);
        this.loop = new ActTickLoop(runtime);
        // No keyboard. ActMovementInput still delegates to this first, so the override path being
        // measured is the same one that runs on a live client with the keys up.
        this.input = new ActMovementInput(new MovementInput(), runtime);

        runtime.registerApplier(ActSlot.MOVE, new MoveApplier(world, runtime, this::routeFor));
        runtime.registerApplier(ActSlot.LOOK, new LookApplier(world));
        runtime.registerApplier(ActSlot.INTERACT, new InteractApplier(world));
    }

    /**
     * The route factory {@code McpCore} supplies, pointed at the simulator.
     *
     * <p>Same {@link Planner} and same {@link RouteExecutor}; the only substitution is the
     * {@code BlockView} the search reads, and the simulator is one. A planning failure arrives as a
     * refusing machine for the same reason it does live -- the caller learns from the slot, not from
     * an exception.
     */
    private LocomotionController routeFor(net.marcloud.mcp.core.drivers.act.RouteIntent ri) {
        Stance start = world.stance();
        Stance goal = new Stance(ri.targetX(), ri.targetY(), ri.targetZ());
        lastPlan = new Planner(world).plan(start, goal);
        if (!lastPlan.found()) {
            return refusal("no route from " + start + " to " + goal + ": " + lastPlan.failure());
        }
        return new RouteExecutor(lastPlan, ri.blockBudget());
    }

    /** The last plan a route intent produced, or null if none has. */
    public Planner.Plan lastPlan() {
        return lastPlan;
    }

    // ===== driving =====

    /**
     * One game tick, in vanilla's order: the clock, then the appliers, then the body.
     *
     * <p>The body integration is last and it is the load-bearing part: {@link SimWorld#tick} calls
     * {@code input.updatePlayerMoveState()} itself, so the axes the applier published this tick are
     * what the body reads this tick.
     */
    public void tick() {
        long id = clock.advance();
        loop.onTick(new TickEvent(id));
        world.tick(input);
    }

    /**
     * One tick in which the intent is submitted <b>mid-tick</b>, the way a worker thread's
     * {@code act_set} really lands.
     *
     * <p>This exists because {@link #tick()} cannot exercise the effective-tick gate at all.
     * {@code ActRuntime.submit} sets {@code effectiveTick = lastCompletedTick() + 1}, and a
     * between-ticks submit therefore names the very next tick as its effective one -- there is no
     * window before it to be early or late in, and a task asserting on one asserts nothing. The
     * gate is not idle, though: its own doc says it exists so "an intent submitted from a worker
     * thread mid-tick always begins on a clean tick boundary -- never half-applied inside the tick
     * it arrived". That is the ordering reproduced here, and it is the ordering a real
     * {@code act_set} from any thread but the game thread produces.
     *
     * <p>So: the clock advances (naming tick {@code id}), the intent is submitted while the game
     * thread is inside that tick and before the appliers run, then the appliers run for {@code id}.
     * {@code stepSlot} sees {@code id < effectiveTick == id + 1} and leaves the slot IDLE, so the
     * body sees neutral axes on that tick and the intent's from the next one on.
     *
     * @return the stored record, so a task can read the effective tick it was given
     */
    public net.marcloud.mcp.core.drivers.act.SlotRecord tickSubmittingMidTick(ActIntent intent) {
        long id = clock.advance();
        net.marcloud.mcp.core.drivers.act.SlotRecord rec = runtime.submit(intent);
        loop.onTick(new TickEvent(id));
        world.tick(input);
        return rec;
    }

    /** {@link #tick()} {@code n} times. */
    public void ticks(int n) {
        for (int i = 0; i < n; i++) {
            tick();
        }
    }

    /**
     * Tick until {@code slot} reaches a terminal phase, or {@code maxTicks} have passed.
     *
     * <p>Returns the ticks actually spent, which is why no caller asserts on it:
     * {@code NavController}'s reaction delay is a {@code ThreadLocalRandom} draw of 4..8 ticks, so
     * a tick count is not a reproducible fact. Assert on where the player ENDED.
     *
     * @return true when the slot became terminal inside the budget
     */
    public boolean runUntil(ActSlot slot, int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            if (runtime.record(slot).phase().isTerminal()) {
                return true;
            }
            tick();
        }
        return runtime.record(slot).phase().isTerminal();
    }
    /**
     * Tick until {@code done} answers true, or {@code maxTicks} have passed.
     *
     * <p>The predicate is evaluated BEFORE each tick, so a task whose condition already holds
     * spends zero ticks.
     *
     * @return whether the predicate became true inside the budget
     */
    public boolean run(int maxTicks, java.util.function.BooleanSupplier done) {
        for (int i = 0; i < maxTicks; i++) {
            if (done.getAsBoolean()) {
                return true;
            }
            tick();
        }
        return done.getAsBoolean();
    }

    /** Advance {@code maxTicks} ticks, for a task that only wants time to pass. */
    public boolean run(int maxTicks) {
        return run(maxTicks, () -> false);
    }


    /** Tick until the MOVE slot is terminal or the budget runs out. */
    public boolean runMove(int maxTicks) {
        return runUntil(ActSlot.MOVE, maxTicks);
    }

    /** Tick until the INTERACT slot is terminal or the budget runs out. */
    public boolean runInteract(int maxTicks) {
        return runUntil(ActSlot.INTERACT, maxTicks);
    }

    // ===== the runtime's own face =====

    public SimWorld world() {
        return world;
    }

    public ActRuntime runtime() {
        return runtime;
    }

    public GameClock clock() {
        return clock;
    }

    /** The movement input the body is reading, for a task that inspects the override itself. */
    public MovementInput input() {
        return input;
    }

    public ActPhase phase(ActSlot slot) {
        return runtime.record(slot).phase();
    }
    /** The phase of one slot, for a task's report line. Alias of {@link #phase}. */
    public ActPhase phaseOf(ActSlot slot) {
        return runtime.record(slot).phase();
    }


    /** Submit any intent to its own slot, exactly as {@code act_set} would. */
    public void submit(ActIntent intent) {
        runtime.submit(intent);
    }

    public void cancel(ActSlot slot) {
        runtime.cancel(slot);
    }

    /** The actuator every controller in this harness is driven against. */
    public ActActuator actuator() {
        return world;
    }

    // ===== helpers for a task's own assertions =====

    /**
     * Distance from the player to a block's CENTRE, in blocks.
     *
     * <p>Horizontal-only by default, because that is what almost every arrival question is: a walk
     * that reports arrival has arrived in the plane, and comparing the wrong axis would make a
     * player standing on the right block at the wrong height look arrived.
     */
    public double distanceTo(double x, double y, double z) {
        return world.distanceTo(x, y, z);
    }

    /** Horizontal distance from the player to a point, in blocks. */
    public double horizontalDistanceTo(double x, double z) {
        return world.horizontalDistanceTo(x, z);
    }

    /** The feet block the player occupies right now. */
    public Stance stance() {
        return world.stance();
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "EvalHarness[tick=%d %s move=%s interact=%s]",
                clock.tickId(), world, phase(ActSlot.MOVE), phase(ActSlot.INTERACT));
    }

    /**
     * A machine that does nothing and says why, matching {@code RoutePlanning}'s refusal shape.
     *
     * <p>Duplicated rather than reached for because {@code RoutePlanning.refusal} is private and
     * making it public would be an edit to a production class for a test's convenience. The shape
     * is the contract that matters: a terminal outcome on the slot, so the caller reads it from
     * {@code act_status} by the same path whether the plan failed or the walk did.
     */
    private static LocomotionController refusal(String why) {
        return new LocomotionController() {
            @Override
            public net.marcloud.mcp.core.drivers.act.ActOutcome tick(ActActuator act) {
                return net.marcloud.mcp.core.drivers.act.ActOutcome.failed("route not planned: " + why);
            }

            @Override
            public float forward() {
                return 0f;
            }

            @Override
            public float strafe() {
                return 0f;
            }

            @Override
            public boolean jump() {
                return false;
            }

            @Override
            public int ticks() {
                return 0;
            }

            @Override
            public void requestCancel() {
                // already terminal on its first tick
            }
            @Override
            public boolean creeping() {
                return false;
            }

            @Override
            public net.marcloud.mcp.core.drivers.act.MoveTactic tactic() {
                // A refusal never reads the world, never steers, and never publishes an axis, so
                // there is no tactic to report. Null says exactly that.
                return null;
            }
        };
    }
}
