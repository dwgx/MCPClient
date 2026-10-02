package net.marcloud.mcp.core.eval;

import java.util.List;

import net.marcloud.mcp.core.drivers.act.ActActuator.Face;
import net.marcloud.mcp.core.drivers.act.ActPhase;
import net.marcloud.mcp.core.drivers.act.ActSlot;
import net.marcloud.mcp.core.drivers.act.InteractIntent;
import net.marcloud.mcp.core.drivers.act.MoveIntent;
import net.marcloud.mcp.core.drivers.act.NavHazard;
import net.marcloud.mcp.core.drivers.act.NavIntent;
import net.marcloud.mcp.core.drivers.act.RouteIntent;
import net.marcloud.mcp.core.drivers.act.SlotRecord;
import net.marcloud.mcp.core.drivers.craft.Craft;
import net.marcloud.mcp.core.drivers.craft.CraftController;
import net.marcloud.mcp.core.drivers.craft.CraftOutcome;
import net.marcloud.mcp.core.drivers.craft.CraftWindow;
import net.marcloud.mcp.core.drivers.craft.RecipeView;
import net.marcloud.mcp.core.drivers.plan.Move;
import net.marcloud.mcp.core.drivers.plan.Planner;
import net.marcloud.mcp.core.drivers.plan.Stance;
import net.marcloud.mcp.core.drivers.plan.RouteExecutor;
import net.marcloud.mcp.core.drivers.world.EntityCombat;

/**
 * The tasks, in increasing difficulty, each scored as a fact about the world.
 *
 * <p><b>Every verdict below is a world fact.</b> Position in the simulated world, the block
 * identity at a cell, the contents of an inventory slot, an entity's health, the player's health,
 * a container slot's contents on each side of the window. Never {@code ActOutcome.message()},
 * never an {@code isError} flag, never "the controller said it succeeded". A tool that fails
 * honestly is a working tool, and scoring its honesty as a failure is how a gate gets tuned until
 * it reports whatever the author wanted.
 *
 * <p>The one exception is deliberate and is stated where it is used: a hazard is asserted on the
 * typed {@link NavHazard} FIELD rather than on the sentence that renders it, because
 * {@code NavHazard} exists precisely so a caller can branch on a value instead of parsing prose.
 * Asserting on {@code record.hazard()} is asserting on data, and it is the same class of fact as
 * a position -- it is the only honest way to ask "did the controller see the ocean".
 *
 * <p><b>Determinism.</b> One world is built per task, from a fixed declarative description, and it
 * is never re-rolled: a task that fails fails, and the runner reports it rather than trying
 * again. The one source of nondeterminism in the whole stack is {@code NavController}'s reaction
 * delay, a {@code ThreadLocalRandom} draw of 4..8 ticks. That is why no task here asserts a tick
 * COUNT -- every locomotion task asserts an ENDPOINT, and a task that needs "a few ticks" says
 * "until the slot is terminal" and caps it.
 *
 * <p><b>What each task is for.</b> The list is chosen to cover the paths that regress SILENTLY,
 * because a path that fails loudly does not need a gate: navigation steering (a mirrored axis
 * walks confidently the wrong way), the input layer's effective-tick gate (an intent that starts
 * one tick early is invisible to every endpoint), inventory transfer and crafting (a window that
 * clicks and does nothing), the hazard report (the two live deaths), and the placement chain (a
 * bridge that is planned and not built).
 */
public final class EvalSuite {

    private EvalSuite() {
    }

    /** One task's name, its outcome, and the fact that decided it. */
    public record Result(String id, boolean pass, String fact) {
    }

    /** A task: an id, what it asks, and the body that answers with a fact. */
    public interface Task {
        String id();

        Result run();
    }

    /** Every task, easiest first. The order is the difficulty order and is load-bearing. */
    public static List<Task> all() {
        return List.of(
                new T01HoldForwardMovesNorth(),
                new T02WalkToCoordinate(),
                new T03WalkFromEveryHeading(),
                new T04InputIsNeutralUntilTheEffectiveTick(),
                new T05WalkIntoWallStaysOnTheNearSide(),
                new T06StepUpARouteEmitsStepUp(),
                new T07DigBreaksTheBlockAndYieldsTheDrop(),
                new T08StepUpIsWalkedByTheJumpAxis(),
                new T09BridgeAGapByPlacingBlocks(),
                new T10SurvivableDropIsTakenAndFatalOneIsReported(),
                new T11ContainerSlotMoveIsVisibleOnBothSides(),
                new T12CraftInThePlayersOwnTwoByTwo(),
                new T13CraftOnABenchFromTheRealRecipeTable(),
                new T14LowHealthSurvivesALongRoute(),
                new T15EngageAnEntityInsideTheAcceptanceBound(),
                new T16HeldUseRunsOutAndAnInterruptedOneSpendsNothing(),
                new T17ARefusedCraftReportsFailureAndInventsNothing(),
                new T18MineIronOreFromNothing(),
                new T19PrepareBeforePointingAtIt(),
                new T20TheDirectLineIsLava(),
                new T21CountBeforeYouSpend(),
                new T22TheBenchHasToExistFirst(),
                new T23TheDropThatNeverLanded(),
                new T24TheNightIsSomethingATaskCanAssert());
    }

    // ===== helpers shared by the tasks =====

    private static boolean terminal(ActSlot slot, EvalHarness h) {
        return h.runtime().record(slot).phase().isTerminal();
    }

    private static boolean complete(ActSlot slot, EvalHarness h) {
        return h.runtime().record(slot).phase() == ActPhase.COMPLETE;
    }

    /** A flat plain of stone at y=63, so stances are at y=64, with the player facing north. */
    private static SimWorld plain() {
        // plain(y, ...) lays the floor at y-1, so plain(64) floors at 63 and standOn(0,64,0) is
        // exactly on it. An earlier version said plain(63) with standOn(0,64,0), which put the
        // player one block in the air; it fell a block before it walked, and two tasks then
        // failed for a reason that had nothing to do with either controller.
        return new SimWorld().plain(64, "stone", -24, 24, -24, 24).standOn(0, 64, 0).facing(0f);
    }

    /** How many moves of a kind the last plan emitted, for a task that is about the SEARCH. */
    private static long movesOfKind(Planner.Plan plan, Move.Kind kind) {
        return plan.moves().stream().filter(m -> m.kind() == kind).count();
    }

    private static String kindsOf(Planner.Plan plan) {
        StringBuilder sb = new StringBuilder();
        for (Move m : plan.moves()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(m.kind()).append(m.placeCell() != null ? "@" + m.placeCell() : "");
        }
        return sb.length() == 0 ? "(no moves)" : sb.toString();
    }

    // ===== T01 =====

    /** A raw forward walk: the input layer, the applier, and the physics, and nothing else. */
    static final class T01HoldForwardMovesNorth implements Task {
        @Override
        public String id() {
            return "T01 hold_forward_moves_north";
        }

        @Override
        public Result run() {
            SimWorld w = plain();
            EvalHarness h = new EvalHarness(w);
            h.runtime().submitMove(new MoveIntent(1f, 0f, false, false, false, 40));
            double startZ = w.posZ();
            h.run(120, () -> terminal(ActSlot.MOVE, h));
            double moved = w.posZ() - startZ;
            // "> 4 blocks", not "more than 7.99 of an 8.0 budget". A threshold that only a
            // rounding accident can decide is not a property of anything: it can fail because the
            // walk stopped one hundredth short and says nothing about whether steering works. The
            // two facts that DO carry information are the sign -- north, not a mirrored south --
            // and that the body covered real ground at all. So that is what is asserted, and the
            // measured distance is printed so a reader can judge it instead of being told it failed.
            boolean pass = moved > 4.0D && w.posY() == 64.0D && w.onGround();
            return new Result(id(), pass, String.format(
                    "walked %.2f blocks north, feet at y=%.2f, onGround=%s, %s",
                    moved, w.posY(), w.onGround(), h.phaseOf(ActSlot.MOVE)));
        }
    }

    // ===== T02 =====

    /** A closed-loop walk to a coordinate. This is the task a broken nav controller fails. */
    static final class T02WalkToCoordinate implements Task {
        @Override
        public String id() {
            return "T02 walk_to_coordinate_arrives";
        }

        @Override
        public Result run() {
            SimWorld w = plain();
            EvalHarness h = new EvalHarness(w);
            double tx = 0.5D;
            double tz = -14.5D;
            h.runtime().submitNav(new NavIntent(tx, 64.0D, tz, 400));
            h.run(500, () -> terminal(ActSlot.MOVE, h));
            double dist = w.horizontalDistanceTo(tx, tz);
            // NavController.ARRIVE_EPSILON is 0.6 and private; the bound here is 1.0 so the
            // assertion is the property "arrived at the place" rather than a copy of a constant
            // that a later tuning change would silently falsify. The feet must also still be on
            // the ground: a walk that "arrived" by falling into the target cell has not arrived.
            boolean pass = dist <= 1.0D && w.onGround() && w.posY() == 64.0D
                    && complete(ActSlot.MOVE, h);
            return new Result(id(), pass, String.format(
                    "ended %.2f blocks from (%.1f,%.1f) at (%.2f,%.2f,%.2f) onGround=%s, %s",
                    dist, tx, tz, w.posX(), w.posY(), w.posZ(), w.onGround(),
                    h.phaseOf(ActSlot.MOVE)));
        }
    }

    // ===== T03 =====

    /**
     * The same walk from eight headings.
     *
     * <p>One heading is not enough: a controller that steers correctly facing north and backwards
     * facing south is mirrored, and a single task would score it as a pass. This is the task that
     * catches the axis-sign defect the repo has already paid for once.
     */
    static final class T03WalkFromEveryHeading implements Task {
        @Override
        public String id() {
            return "T03 walk_arrives_from_every_heading";
        }

        @Override
        public Result run() {
            StringBuilder failures = new StringBuilder();
            int bad = 0;
            for (int k = 0; k < 8; k++) {
                float yaw = k * 45f;
                SimWorld w = plain().facing(yaw);
                EvalHarness h = new EvalHarness(w);
                double tx = 0.5D;
                double tz = -12.5D;
                h.runtime().submitNav(new NavIntent(tx, 64.0D, tz, 400));
                h.run(500, () -> terminal(ActSlot.MOVE, h));
                double dist = w.horizontalDistanceTo(tx, tz);
                if (dist > 1.0D || !complete(ActSlot.MOVE, h)) {
                    bad++;
                    if (failures.length() > 0) {
                        failures.append("; ");
                    }
                    failures.append(String.format("yaw=%.0f ended %.2f away at (%.2f,%.2f) %s",
                            yaw, dist, w.posX(), w.posZ(), h.phaseOf(ActSlot.MOVE)));
                }
            }
            return new Result(id(), bad == 0, bad == 0
                    ? "arrived within 1.0 block from all 8 headings"
                    : bad + " of 8 headings failed: " + failures);
        }
    }

    // ===== T04 =====

    /**
     * The input layer's own timing, which no other task isolates.
     *
     * <p>Every other locomotion task would still pass if the intent took effect one tick early or
     * one tick late, because a walk that starts a tick sooner arrives a tick sooner and the
     * endpoint is identical. So this task asserts the thing directly: before the effective tick
     * the body must see NEUTRAL axes, and from the effective tick it must see the intent's.
     *
     * <p>It reads {@code SimWorld.inputTrace()} -- what the input layer actually handed the body
     * each tick -- rather than what was submitted or what the runtime believed. That distinction
     * is the whole task: the bug this catches is a slot that reports ACTIVE while nothing consumes
     * its axes, which is silent on every status line.
     */
    static final class T04InputIsNeutralUntilTheEffectiveTick implements Task {
        @Override
        public String id() {
            return "T04 input_is_neutral_until_the_effective_tick";
        }

        @Override
        public Result run() {
            SimWorld w = plain();
            EvalHarness h = new EvalHarness(w);
            // The submit happens INSIDE the tick, after the clock names it and before the
            // appliers run for it. That ordering is the whole gate: ActRuntime sets
            // effectiveTick = lastCompletedTick() + 1, so a submit between ticks names the very
            // next tick as its effective one and there is no window to be early or late in --
            // an earlier version of this task submitted there, computed "one tick before" and got
            // that same tick, and asserted a live axis was neutral. The gate was correct and the
            // task was not measuring it.
            SlotRecord rec = h.tickSubmittingMidTick(new MoveIntent(1f, 0f, false, false, false, 20));
            long submitTick = rec.effectiveTick() - 1;
            long effective = rec.effectiveTick();
            // The tick the intent arrived on must be neutral, and the next one must not be.
            List<float[]> trace = w.inputTrace();
            float[] onArrival = trace.isEmpty() ? null : trace.get(trace.size() - 1);
            boolean neutralOnArrival = onArrival != null
                    && onArrival[0] == 0f && onArrival[1] == 0f;
            h.ticks(2);
            trace = w.inputTrace();
            int effectiveIndex = (int) (effective - 1);
            float[] onEffective = effectiveIndex < trace.size() ? trace.get(effectiveIndex) : null;
            boolean liveOnEffective = onEffective != null
                    && (onEffective[0] != 0f || onEffective[1] != 0f);
            boolean pass = neutralOnArrival && liveOnEffective;
            return new Result(id(), pass, String.format(
                    "submitted during tick %d, effectiveTick=%d; body saw neutral axes on the arrival"
                            + " tick=%s and the intent's on the effective tick=%s",
                    submitTick, effective, neutralOnArrival, liveOnEffective));
        }
    }

    // ===== T05 =====

    /**
     * A wall the player cannot pass.
     *
     * <p>The pass condition has two halves and both matter. The slot must report a FAILURE, and
     * the player must still be on the near side. A controller that "arrives" by clipping through
     * geometry scores the first half and fails the second, which is why both are asserted.
     */
    static final class T05WalkIntoWallStaysOnTheNearSide implements Task {
        @Override
        public String id() {
            return "T05 walk_into_wall_fails_without_crossing_it";
        }

        @Override
        public Result run() {
            SimWorld w = plain();
            w.box(-4, 64, -8, 4, 66, -8, "stone");
            EvalHarness h = new EvalHarness(w);
            h.runtime().submitNav(new NavIntent(0.5D, 64.0D, -14.5D, 300));
            h.run(400, () -> terminal(ActSlot.MOVE, h));
            boolean failed = h.phaseOf(ActSlot.MOVE) == ActPhase.FAILED;
            // The wall's near face is z = -8.0; a body 0.6 wide standing on the near side cannot
            // have its centre beyond it.
            boolean stayedNear = w.posZ() > -8.0D;
            boolean pass = failed && stayedNear;
            return new Result(id(), pass, String.format(
                    "slot %s; player at z=%.2f (near face z=-8.0, %s)",
                    h.phaseOf(ActSlot.MOVE), w.posZ(),
                    stayedNear ? "never crossed" : "PASSED THROUGH THE WALL"));
        }
    }

    // ===== T06 =====

    /**
     * A route over a one-block rise, asserted on the SEARCH's output.
     *
     * <p>The only task that scores the plan rather than the world, and it is here because the
     * step-up is the one move kind whose execution depends on a second system: the jump axis.
     * {@code NavController} asks for a jump only within {@code STEP_UP_RANGE} of the step, from
     * the ground, for a destination exactly one block up -- and if the planner stopped emitting
     * {@link Move.Kind#STEP_UP} the route would quietly become a walk that never arrives, which
     * is a different failure with the same symptom.
     */
    static final class T06StepUpARouteEmitsStepUp implements Task {
        @Override
        public String id() {
            return "T06 route_over_a_rise_plans_a_step_up";
        }

        @Override
        public Result run() {
            SimWorld w = plain();
            // A one-block ledge at z=-6, and the plain continues past it at y=64.
            w.box(-4, 64, -6, 4, 64, -6, "stone");
            EvalHarness h = new EvalHarness(w);
            h.submit(new net.marcloud.mcp.core.drivers.act.RouteIntent(0, 64, -9, 4));
            h.runMove(400);
            Planner.Plan plan = h.lastPlan();
            boolean found = plan != null && plan.found();
            long stepUps = plan == null ? 0 : movesOfKind(plan, Move.Kind.STEP_UP);
            boolean pass = found && stepUps >= 1;
            return new Result(id(), pass, plan == null
                    ? "no plan was produced"
                    : String.format("plan found=%s with %d STEP_UP move(s): %s",
                            plan.found(), stepUps, kindsOf(plan)));
        }
    }

    // ===== T07 =====

    /**
     * A dig, scored on the block being GONE and the drop being the item VANILLA gives.
     *
     * <p><b>Stone with an iron pickaxe, not dirt.</b> This task used to dig dirt, on the reasoning
     * that dirt's drop is dirt so the substrate's answer and the real answer coincide. They do
     * coincide -- which is exactly why it was the wrong block. Dirt cannot tell a working drop
     * model from a block-to-item lookup, so the task passed over the thing it existed to check and
     * a substrate that asked {@code Item.getItemFromBlock} would have scored the same. Stone is the
     * block that separates them: {@code BlockStone.getItemDropped:48-51} returns
     * <b>cobblestone</b>, and a substrate that asks the wrong question returns <b>stone</b>.
     *
     * <p>Both halves of vanilla's drop rule are asserted, because the second is the one that is
     * silently wrong most often. {@code Block.getDrops} is only reached when the breaker can
     * harvest the block, so bare hands on stone yield <b>nothing at all</b> -- the block breaks
     * and the inventory stays empty, and that is the correct answer, not a failure to yield.
     */
    static final class T07DigBreaksTheBlockAndYieldsTheDrop implements Task {
        @Override
        public String id() {
            return "T07 dig_yields_the_drop_vanilla_gives";
        }

        @Override
        public Result run() {
            SimWorld w = plain();
            w.put(0, 64, -3, "stone");
            w.give(0, "iron_pickaxe", 1);
            w.setHeldSlot(0);
            EvalHarness h = new EvalHarness(w);
            h.runtime().submitInteract(InteractIntent.dig(0, 64, -3, 2));
            h.runInteract(400);
            boolean gone = w.blockAt(0, 64, -3) == null;
            int cobble = w.count("cobblestone");
            int stone = w.count("stone");
            // "stone" here is the BLOCK, which is not an item and so is always 0 -- the block
            // identity is asserted through blockAt, not through the inventory.
            boolean correctDrop = cobble == 1;
            boolean noBlockItem = stone == 0;
            boolean pass = gone && correctDrop && noBlockItem;
            return new Result(id(), pass, String.format(
                    "stone with an iron pickaxe: blockAt(0,64,-3)=%s, cobblestone=%d,"
                            + " stone-as-item=%d, inventory=%s, %s",
                    String.valueOf(w.blockAt(0, 64, -3)), cobble, stone, w.describeInventory(),
                    h.phaseOf(ActSlot.INTERACT)));
        }
    }

    // ===== T08 =====

    /**
     * The step-up, executed: the jump AXIS has to be asked for, and the route has to finish.
     *
     * <p>Two facts. The route the planner emits here is WALK x5, STEP_UP onto the ledge,
     * <b>DROP back off it</b>, WALK -- so the correct ending is y=64, one cell further along. An
     * earlier version demanded {@code posY() == 65}, which asserts a height the plan explicitly
     * asks the player to leave again; the route was right and the assertion was wrong.
     *
     * <p><b>The first fact is read from {@code inputTrace}, and it has to be.</b> The obvious
     * proxy -- "the body ended up above the step" -- was tried and is not evidence of anything:
     * measured with {@code RouteExecutor.jump()} forced to {@code false}, this world still lifts
     * the player to y=65.12, because the collision resolver raises a body that overlaps a block
     * while descending. That proxy passed with the jump axis removed. A task that cannot tell the
     * jump apart from the substrate's own step is not testing the jump, so the assertion is on
     * what the input layer actually handed the body -- the third element of every trace entry --
     * and the substrate divergence is recorded in {@link SimWorld#KNOWN_GAPS}.
     *
     * <p>The second fact is the endpoint the route's last move names, so a route that walked
     * around the ledge cannot pass by arriving somewhere else.
     */
    static final class T08StepUpIsWalkedByTheJumpAxis implements Task {
        @Override
        public String id() {
            return "T08 step_up_asks_for_the_jump_and_ends_on_the_far_cell";
        }

        @Override
        public Result run() {
            SimWorld w = plain();
            w.box(-4, 64, -6, 4, 64, -6, "stone");
            EvalHarness h = new EvalHarness(w);
            h.submit(new RouteIntent(0, 64, -8, 4));
            h.runMove(600);
            Planner.Plan plan = h.lastPlan();
            Stance s = w.stance();
            // What the INPUT LAYER handed the body: a jump axis was published on some tick.
            int jumpsPublished = 0;
            double peakY = 0.0D;
            List<float[]> trace = w.inputTrace();
            for (int i = 0; i < trace.size(); i++) {
                if (trace.get(i)[2] != 0f) {
                    jumpsPublished++;
                }
                double[] p = w.posTrace().get(i);
                peakY = Math.max(peakY, p[1]);
            }
            boolean askedForJump = jumpsPublished > 0;
            // And ended in the cell the route PROMISES: the destination of the plan's own last
            // move, at that move's height. Read off the plan rather than written as a literal, so
            // the row keeps testing the contract if the terrain or the goal ever moves.
            //
            // The previous form of this assertion was a distance check, `horizontalDistanceTo(0.5,
            // -7.5) <= RouteExecutor.ARRIVE_TOLERANCE`, and the comment above it argued that this
            // was one notch more lenient than the exact cell on purpose. Measured over 40 runs it
            // was not a notch. Every one of them ended a FULL CELL short -- stance z=-7 against a
            // plan naming z=-8 -- and every one of them passed, because 0.53 is inside 0.7. A
            // tolerance wide enough to admit a neighbouring cell cannot tell "arrived" from
            // "stopped early", and a task that cannot tell those two apart is not measuring
            // arrival. The exact cell is what the route now verifies and what a player would call
            // having got there.
            Stance promised = promisedEndOf(plan);
            boolean inPromisedCell = promised != null
                    && s.x() == promised.x() && s.y() == promised.y() && s.z() == promised.z();
            boolean arrived = inPromisedCell;
            boolean grounded = w.onGround();
            boolean pass = askedForJump && arrived && grounded;
            return new Result(id(), pass, String.format(
                    "the input layer published a jump axis on %d tick(s) (peak y=%.2f); ended in"
                            + " cell (%d,%d,%d) at (%.2f,%.2f,%.2f) onGround=%s; the plan's last move"
                            + " names (%s); plan=%s",
                    jumpsPublished, peakY, s.x(), s.y(), s.z(), w.posX(), w.posY(), w.posZ(),
                    w.onGround(), promised == null ? "no plan" : promised.toString(),
                    plan == null ? "(no plan)" : kindsOf(plan)));
        }
    }

    /**
     * The cell a plan says the body will finish in: the destination of its last move.
     *
     * <p>Null when there is no plan or the plan has no moves, because "arrived" is a claim about a
     * route and a task that scores arrival without one is scoring the body's habits.
     */
    private static Stance promisedEndOf(Planner.Plan plan) {
        if (plan == null || plan.moves().isEmpty()) {
            return null;
        }
        return plan.moves().get(plan.moves().size() - 1).to();
    }

    // ===== T09 =====

    /**
     * A gap with no way around, crossed by placing blocks.
     *
     * <p>The terrain is a trench that spans the whole plain, so the search has no detour to
     * prefer and {@link Move.Kind#BRIDGE} is the only move that reaches the far side. A shorter
     * gap would be detoured -- the planner prices a bridge at six walks on purpose -- and the
     * task would then be testing the cost function rather than the placement chain.
     *
     * <p>Scored on three independent world facts: the plan asked for bridges, a block now exists
     * in the cell that had no floor, and the player is standing on the far side. A placement that
     * was issued and refused fails all three, which is the honest outcome -- a refused placement
     * is a real failure on a live client, not a rounding error.
     */
    static final class T09BridgeAGapByPlacingBlocks implements Task {
        @Override
        public String id() {
            return "T09 cross_a_trench_by_placing_a_bridge";
        }

        @Override
        public Result run() {
            SimWorld w = plain();
            // A trench three blocks wide and as deep as the plain is tall, with no floor at all
            // under it: nothing to walk on, nothing to fall onto, and no line around it.
            for (int x = 5; x <= 7; x++) {
                for (int z = -24; z <= 24; z++) {
                    for (int y = 50; y <= 66; y++) {
                        w.remove(x, y, z);
                    }
                }
            }
            w.give(0, "cobblestone", 8);
            w.setHeldSlot(0);
            EvalHarness h = new EvalHarness(w);
            h.submit(new net.marcloud.mcp.core.drivers.act.RouteIntent(10, 64, 0, 8));
            h.runMove(900);
            Planner.Plan plan = h.lastPlan();
            long bridges = plan == null ? 0 : movesOfKind(plan, Move.Kind.BRIDGE);
            // The player must be standing on the far side, on ground, in the destination cell.
            boolean across = w.posX() > 7.5D && w.horizontalDistanceTo(10.5D, 0.5D) <= 1.0D;
            boolean grounded = w.onGround() && w.posY() == 64.0D;
            int spent = countCobbleInTrench(w);
            boolean pass = bridges >= 1 && across && grounded && spent >= 1;
            return new Result(id(), pass, String.format(
                    "plan had %d BRIDGE move(s) [%s]; ended at (%.2f,%.2f,%.2f) onGround=%s;"
                            + " %d cobblestone block(s) now stand in the trench; inventory=%s; %s",
                    bridges, plan == null ? "no plan" : kindsOf(plan),
                    w.posX(), w.posY(), w.posZ(), w.onGround(), spent, w.describeInventory(),
                    h.phaseOf(ActSlot.MOVE)));
        }

        /** How many of the trench cells now hold the block that was placed, by block identity. */
        private static int countCobbleInTrench(SimWorld w) {
            int n = 0;
            for (int x = 5; x <= 7; x++) {
                for (int y = 50; y <= 66; y++) {
                    if ("cobblestone".equals(w.blockAt(x, y, 0))) {
                        n++;
                    }
                }
            }
            return n;
        }
    }

    // ===== T10 =====

    /**
     * The hazard distinction: a survivable drop is taken, a fatal line is reported.
     *
     * <p><b>This is the task that matters most in the whole suite</b>, because it is the one whose
     * absence has a body count. A straight line into deep water drowned the player on a live
     * client, and a straight line off a 30-block cliff killed them, and in both cases
     * {@code act_status} said "walking" the whole way down.
     *
     * <ul>
     *   <li><b>A survivable drop is WALKED, not vetoed.</b> A two-block step down costs no
     *       fall damage in vanilla ({@code EntityLivingBase:233} charges {@code ceil(distance-3)}),
     *       so a controller that refused it would be refusing to walk downhill. The property
     *       asserted is the one the controller actually promises -- it is a report, never a
     *       veto -- so: the feet end two blocks lower, grounded, at the target, and the slot
     *       reached COMPLETE rather than FAILED.</li>
     *   <li><b>A fatal line is REPORTED and then not walked.</b> {@code NavController} is
     *       documented as never steering, so it does not refuse -- it publishes a typed
     *       {@link NavHazard} and keeps walking. The capability that saved the live session is
     *       that a CALLER can read the warning and cancel. So this task plays the caller: it
     *       watches {@code record.hazard()}, cancels the moment the warning is readable, and
     *       asserts the player is still on the near side and still alive. Both halves are world
     *       facts; the hazard read is a typed field, not a sentence.</li>
     * </ul>
     *
     * <p><b>One thing is reported rather than asserted, deliberately.</b> The survivable half
     * prints whatever hazard the scan published, because
     * {@code NavController.scanHazard:461} samples exactly ONE cell below the feet: a step down
     * of ANY depth therefore reads as open air for as long as the lower plain lasts, and once
     * that run passes {@code SAFE_DROP} it is reported as {@code DEEP_DROP}. A two-block step
     * down is charged no damage by the game, so that warning is a false positive. It is NOT
     * asserted either way here -- pinning it as expected would bless it, and asserting its
     * absence would make this task permanently red over a production defect this harness is not
     * allowed to fix. It is a finding, and it is printed on every run.
     */
    static final class T10SurvivableDropIsTakenAndFatalOneIsReported implements Task {
        @Override
        public String id() {
            return "T10 survivable_drop_walked_fatal_line_warned";
        }

        /** One half's verdict plus what it actually saw, so a pass can still report a finding. */
        private record Half(boolean pass, String fact) {
        }

        @Override
        public Result run() {
            Half survivable = survivableDropIsTaken();
            Half fatal = fatalLineIsReportedAndCancelled();
            boolean pass = survivable.pass() && fatal.pass();
            return new Result(id(), pass,
                    "survivable: " + survivable.fact() + " | fatal: " + fatal.fact());
        }

        private static Half survivableDropIsTaken() {
            SimWorld w = plain();
            // A two-block step DOWN, which means a floor two blocks lower -- not the absence of
            // one. This terrain used to remove y=63,62,61 at z=-7 and put one block at (0,61,-10):
            // a single empty slot with a stray block further on, which is not a step down at all.
            // The walk then ended at y=64.06 and the task reported that a survivable drop "was not
            // taken", describing its own geometry rather than anything the controller did.
            for (int x = -24; x <= 24; x++) {
                for (int z = -7; z >= -24; z--) {
                    w.remove(x, 63, z);
                    w.put(x, 61, z, "stone");
                }
            }
            EvalHarness h = new EvalHarness(w);
            h.runtime().submitNav(new NavIntent(0.5D, 64.0D, -14.5D, 400));
            h.runMove(500);
            double dist = w.horizontalDistanceTo(0.5D, -14.5D);
            // Feet land in the cell whose floor top is y=62, so y between 62 and 64 is two down.
            boolean descended = w.posY() > 61.9D && w.posY() < 64.0D;
            boolean grounded = w.onGround();
            boolean arrived = dist <= 1.0D;
            // "Not vetoed": the slot ended COMPLETE. A controller that refused the descent would
            // end FAILED with the player still up on the ledge.
            boolean notVetoed = h.phaseOf(ActSlot.MOVE) == ActPhase.COMPLETE;
            NavHazard seen = h.runtime().record(ActSlot.MOVE).hazard();
            String observed = String.format(
                    "dropped to y=%.2f grounded=%s %.2f blocks from the target, slot %s; scan"
                            + " reported %s", w.posY(), w.onGround(), dist,
                    h.phaseOf(ActSlot.MOVE), seen == null ? "nothing" : seen.kind());
            if (!descended) {
                return new Half(false, observed + " -- the two-block step down was NOT taken");
            }
            if (!grounded) {
                return new Half(false, observed + " -- still airborne after the drop");
            }
            if (!arrived) {
                return new Half(false, observed + " -- never reached the far side");
            }
            if (!notVetoed) {
                return new Half(false, observed + " -- the slot did not COMPLETE, so the walk was"
                        + " not taken through to the far side");
            }
            return new Half(true, observed);
        }

        /** The lake is placed ACROSS the walk line, not beside it. */
        private static Half fatalLineIsReportedAndCancelled() {
            SimWorld w = plain();
            // The walk runs north->south along x=0.5 from z=0.5 to z=14.5. This lake used to be
            // laid at x=6..24, z=0 -- one row of water off to the EAST of a walk that never
            // leaves x=0.5, so the line into it was clear and no hazard was ever correct. A
            // scenario defect that reads exactly like a missing safety feature.
            for (int x = -24; x <= 24; x++) {
                for (int z = 10; z <= 24; z++) {
                    for (int y = 58; y <= 68; y++) {
                        w.put(x, y, z, "water");
                    }
                }
            }
            EvalHarness h = new EvalHarness(w);
            h.runtime().submitNav(new NavIntent(0.5D, 64.0D, 20.5D, 400));
            NavHazard seen = null;
            for (int i = 0; i < 400; i++) {
                NavHazard hazard = h.runtime().record(ActSlot.MOVE).hazard();
                if (hazard != null) {
                    seen = hazard;
                    break;
                }
                h.tick();
                if (h.phaseOf(ActSlot.MOVE).isTerminal()) {
                    break;
                }
            }
            if (seen == null) {
                return new Half(false, "no hazard was ever published for a straight line into a"
                        + " lake; the walk ran to " + h.phaseOf(ActSlot.MOVE) + " at (%.2f,%.2f,%.2f)"
                        .formatted(w.posX(), w.posY(), w.posZ()));
            }
            if (seen.kind() != NavHazard.Kind.WATER) {
                return new Half(false, "the hazard on a water line was reported as " + seen.kind()
                        + ": " + seen.describe());
            }
            // The warning is only actionable if it arrives while there is still walking left, and
            // the signed distance is the field that says so.
            if (seen.blocksAhead() <= 0) {
                return new Half(false, "the warning arrived with the hazard already behind the"
                        + " player: " + seen.describe());
            }
            String warned = String.format("published %s %.2f blocks ahead at tick %d of the walk",
                    seen.kind(), seen.blocksAhead(), h.clock().tickId());
            // Now play the caller: cancel on the warning, which is what a model reading
            // act_status would do, and check the player never got in.
            h.runtime().cancel(ActSlot.MOVE);
            h.run(80, () -> terminal(ActSlot.MOVE, h));
            double zAfterCancel = w.posZ();
            h.ticks(20);
            boolean dry = w.posZ() < 10.0D && !"water".equals(w.blockAt(
                    (int) Math.floor(w.posX()), (int) Math.floor(w.posY()), (int) Math.floor(w.posZ())));
            boolean alive = w.health() > 0.0D;
            String stopped = String.format("%s; cancelled at z=%.2f and still dry 20 ticks later"
                    + " at z=%.2f, health %.1f", warned, zAfterCancel, w.posZ(), w.health());
            if (!dry) {
                return new Half(false, stopped + " -- the walk continued into the water anyway");
            }
            if (!alive) {
                return new Half(false, stopped + " -- the player died on a warned line");
            }
            return new Half(true, stopped);
        }
    }

    // ===== T11 =====

    /**
     * A container move, observed on BOTH sides of the window.
     *
     * <p>The point is not that an item moved. It is that the client's copy and the server's copy
     * are two arrays and a task can tell them apart: this moves a stack through the real
     * {@link CraftWindow} click path and then reads the destination slot twice, once as the client
     * sees it and once as the server has it. A window that reported a move the server never
     * applied is the desync that {@code CraftController}'s SETTLING state exists to catch, and it
     * is invisible to every tool that reads one array.
     *
     * <p>It also pins the {@code ContainerPlayer} layout, which is why the destination is chosen
     * deliberately: container slot 5 in the player's own window is the first ARMOUR slot, not
     * main inventory 0. A window that computed its storage region as {@code 1 + w * w} would put
     * the item somewhere real and wrong.
     */
    static final class T11ContainerSlotMoveIsVisibleOnBothSides implements Task {
        @Override
        public String id() {
            return "T11 container_move_agrees_on_both_sides";
        }

        @Override
        public Result run() {
            SimWorld w = new SimWorld();
            SimCraftWindow win = SimCraftWindow.playerWindow(w).carrying(0, "dirt", 0, 5);
            // The source slot is FOUND, not computed. This task used to click
            // storageSlots()[0] and storageSlots()[1] and expect the dirt, having put the dirt in
            // mainInventory[0] -- which is the hotbar, and the hotbar sits at the END of the
            // container's slot list (36..44) and at the START of the array. So it clicked two empty
            // main-inventory slots and read the emptiness as a failed move.
            //
            // Looking the slot up by content is the point: a hardcoded index encodes the layout a
            // second time, in the one place a reader is least likely to check it against the real
            // ContainerPlayer, and it fails silently -- an empty slot looks like a refused click.
            int source = slotHolding(win, "dirt");
            int[] storage = win.storageSlots();
            int dest = -1;
            if (source >= 0) {
                for (int slot : storage) {
                    if (slot != source && win.stackAt(slot) == null) {
                        dest = slot;
                        break;
                    }
                }
            }
            if (source < 0 || dest < 0) {
                return new Result(id(), false, "the stack is not reachable through the container's"
                        + " storage region at all; storageSlots() cannot see mainInventory[0]");
            }
            boolean picked = win.click(source, CraftWindow.LEFT);
            boolean placed = win.click(dest, CraftWindow.LEFT);
            var clientDest = win.stackAt(dest);
            var serverDest = win.serverStackAt(dest);
            var clientSource = win.stackAt(source);
            boolean bothSides = clientDest != null && serverDest != null
                    && clientDest.count() == 5 && serverDest.count() == 5;
            boolean emptied = clientSource == null && win.serverStackAt(source) == null;
            // The window's storage IS the player's own inventory array, so the world must agree --
            // and which array index that is depends on the slot the move landed in, so it is read
            // back through the window rather than assumed.
            boolean worldAgrees = w.count("dirt") == 5 && win.cursor() == null && w.cursor() == null;
            boolean pass = picked && placed && bothSides && emptied && worldAgrees;
            return new Result(id(), pass, String.format(
                    "container slots %d->%d; client dest=%s server dest=%s; client source=%s;"
                            + " the world's own inventory still holds %d dirt; cursor=%s; %s",
                    source, dest, clientDest, serverDest, clientSource, w.count("dirt"), w.cursor(),
                    win.droppedClicks() == 0 ? "no clicks dropped" : win.droppedClicks() + " dropped"));
        }

        /**
         * The container slot whose storage currently holds {@code item}, or -1.
         *
         * <p>Scans the storage region the window itself reports, so the mapping from container
         * slot to {@code mainInventory} index is the window's business and this task never has to
         * restate it.
         */
        private static int slotHolding(SimCraftWindow win, String item) {
            for (int slot : win.storageSlots()) {
                var held = win.stackAt(slot);
                // Held.item() is already namespace-stripped (CraftInventory.Held.normalise), so
                // this is an exact match on "dirt", not "minecraft:dirt".
                if (held != null && item.equals(held.item())) {
                    return slot;
                }
            }
            return -1;
        }
    }

    // ===== T12 =====

    /**
     * A craft in the player's own 2x2 grid, on the real recipe table.
     *
     * <p>Scored on four independent world facts: the output is in the inventory, the ingredients
     * are gone, the matrix is clear, and the cursor is empty. Any one alone can be true of a
     * failure -- a craft that reported success while the planks are still in the bar has spent
     * nothing, and a craft that put the output on the cursor loses it on the next window close.
     *
     * <p>The recipe comes from {@link Craft#recipesFor} over the real
     * {@code CraftingManager} table, and the output appears only when
     * {@code findMatchingRecipe} accepts the arrangement the controller actually chose. Four
     * planks in a 2x2 is a crafting table and four in a row is not, so a controller that filled
     * the wrong squares gets nothing here rather than passing on a hand-written rule.
     */
    static final class T12CraftInThePlayersOwnTwoByTwo implements Task {
        @Override
        public String id() {
            return "T12 craft_in_the_2x2_yields_and_spends";
        }

        @Override
        public Result run() {
            SimWorld w = new SimWorld();
            SimCraftWindow win = SimCraftWindow.playerWindow(w).carrying(0, "planks", 0, 4);
            RecipeView recipe = firstRecipe("crafting_table");
            if (recipe == null) {
                return new Result(id(), false, "the real recipe table has no crafting_table recipe");
            }
            if (recipe.width() > 2 || recipe.height() > 2) {
                return new Result(id(), false, "crafting_table needs a "
                        + recipe.width() + "x" + recipe.height() + " grid and this window is 2x2");
            }
            CraftController c = new CraftController(recipe);
            CraftOutcome out = drive(c, win, 400);
            int tables = w.count("crafting_table");
            int planks = w.count("planks");
            boolean matrixClear = "(clear)".equals(win.matrixContents());
            boolean cursorEmpty = win.cursor() == null;
            boolean pass = out != null && out.ok() && tables == 1 && planks == 0
                    && matrixClear && cursorEmpty && win.resultClicks() == 1;
            return new Result(id(), pass, String.format(
                    "%s: crafting_table=%d planks=%d matrix=%s cursor=%s resultClicks=%d"
                            + " serverAgrees=%s",
                    out == null ? "no outcome" : (out.ok() ? "DONE" : "NOT-DONE"), tables, planks, win.matrixContents(),
                    win.cursor(), win.resultClicks(), win.serverStoredCount("crafting_table", 0)));
        }
    }

    // ===== T13 =====

    /**
     * A 3x3 craft on a bench, which is a different container with a different slot layout.
     *
     * <p>The point is the layout, not the recipe. {@code ContainerWorkbench} has no armour slots
     * and its matrix is nine cells where the player's window has four, so a window that derived
     * its storage region as {@code 1 + w * w} and then read main inventory from there happens to
     * be right for the bench and wrong for the player -- and vice versa for the matrix. Running
     * both containers is what pins both.
     */
    static final class T13CraftOnABenchFromTheRealRecipeTable implements Task {
        @Override
        public String id() {
            return "T13 craft_on_a_bench_3x3_yields_and_spends";
        }

        @Override
        public Result run() {
            SimWorld w = new SimWorld();
            SimCraftWindow win = SimCraftWindow.bench(w).carrying(0, "cobblestone", 0, 8);
            RecipeView recipe = firstRecipe("furnace");
            if (recipe == null) {
                return new Result(id(), false, "the real recipe table has no furnace recipe");
            }
            if (win.gridWidth() < 3) {
                return new Result(id(), false, "the bench window reports a "
                        + win.gridWidth() + "x" + win.gridWidth() + " grid");
            }
            CraftController c = new CraftController(recipe);
            CraftOutcome out = drive(c, win, 400);
            int furnaces = w.count("furnace");
            int cobble = w.count("cobblestone");
            boolean matrixClear = "(clear)".equals(win.matrixContents());
            boolean cursorEmpty = win.cursor() == null;
            boolean pass = out != null && out.ok() && furnaces == 1 && cobble == 0
                    && matrixClear && cursorEmpty && win.resultClicks() == 1;
            return new Result(id(), pass, String.format(
                    "%s: furnace=%d cobblestone=%d matrix=%s cursor=%s clicks=%d",
                    out == null ? "no outcome" : (out.ok() ? "DONE" : "NOT-DONE"), furnaces, cobble,
                    win.matrixContents(), win.cursor(), win.clicks()));
        }
    }

    // ===== T14 =====

    /**
     * A long route taken from low health, with the player still alive at the end.
     *
     * <p>Low health is not decoration: it is the state every one of the two live deaths ended in,
     * and a controller that kept walking a fatal line took a nearly-dead player the rest of the
     * way. The route here is a long walk across open ground -- the shape of a journey -- and the
     * assertion is that the player arrives, grounded, at the same health, which is what "the
     * agent can still do the job when it is nearly dead" means as a world fact.
     */
    static final class T14LowHealthSurvivesALongRoute implements Task {
        @Override
        public String id() {
            return "T14 low_health_survives_a_long_route";
        }

        @Override
        public Result run() {
            SimWorld w = plain().atHealth(3.0D);
            EvalHarness h = new EvalHarness(w);
            h.runtime().submitNav(new NavIntent(0.5D, 64.0D, -20.5D, 600));
            h.runMove(700);
            double dist = w.horizontalDistanceTo(0.5D, -20.5D);
            boolean arrived = dist <= 1.0D;
            boolean alive = w.health() > 0.0D && w.health() == 3.0D;
            boolean grounded = w.onGround() && w.posY() == 64.0D;
            boolean pass = arrived && alive && grounded;
            return new Result(id(), pass, String.format(
                    "ended %.2f blocks short at (%.2f,%.2f,%.2f) onGround=%s, health=%.1f (was 3.0), %s",
                    dist, w.posX(), w.posY(), w.posZ(), w.onGround(), w.health(),
                    h.phaseOf(ActSlot.MOVE)));
        }
    }

    // ===== T15 =====

    /**
     * Engaging a mob, and the reach bound being the reason it works.
     *
     * <p>Two halves, and the second is the one that matters. {@link EntityCombat#serverAccepts} is
     * the real server-side test -- {@code distSq < 36} with line of sight, {@code distSq < 9}
     * without -- and an attack past it is not a weak attack, it is a packet the server discards.
     * So the task walks to inside the bound, lands hits, and separately confirms that the same
     * attack issued from beyond the bound lands nothing at all. A controller that ignored the
     * bound would pass the first half and fail the second, which is the only way to tell the
     * difference between "reached the mob" and "the server accepted the swing".
     */
    static final class T15EngageAnEntityInsideTheAcceptanceBound implements Task {
        @Override
        public String id() {
            return "T15 engage_inside_the_bound_and_not_outside_it";
        }

        @Override
        public Result run() {
            String inside = insideTheBound();
            String outside = beyondTheBound();
            boolean pass = inside == null && outside == null;
            return new Result(id(), pass, "inside: " + (inside == null ? "ok" : inside)
                    + " | outside: " + (outside == null ? "ok" : outside));
        }

        /** @return null when the mob was reached and hurt, else why not */
        private static String insideTheBound() {
            SimWorld w = plain();
            int mob = w.spawn("zombie", 0.5D, 64.0D, -3.5D, 20.0D);
            w.give(0, "iron_sword", 1);
            w.setHeldSlot(0);
            EvalHarness h = new EvalHarness(w);
            // Walk to inside the server's own reach, then swing.
            h.runtime().submitNav(new NavIntent(0.5D, 64.0D, -1.5D, 300));
            h.runMove(300);
            double[] eye = w.eyePos();
            double[] mobEye = w.entityEye(mob);
            if (eye == null || mobEye == null) {
                return "the mob or the player's eye could not be read";
            }
            double distSq = sq(eye[0] - mobEye[0]) + sq(eye[1] - mobEye[1]) + sq(eye[2] - mobEye[2]);
            boolean insideBound = EntityCombat.serverAccepts(distSq, true);
            double before = w.entityHealth(mob);
            h.runtime().submitInteract(InteractIntent.attack(mob));
            h.runInteract(60);
            double after = w.entityHealth(mob);
            boolean hit = w.entityHits(mob) >= 1 && after < before;
            if (!insideBound) {
                return String.format("the walk finished at distSq=%.2f, outside the server's own"
                        + " acceptance bound (< %.1f), so nothing here would be accepted",
                        distSq, sq(EntityCombat.reachRadius(true)));
            }
            if (!hit) {
                return String.format("inside the bound (distSq=%.2f) but no hit landed: hits=%d"
                        + " health %.1f -> %.1f, slot %s", distSq, w.entityHits(mob), before, after,
                        h.phaseOf(ActSlot.INTERACT));
            }
            return null;
        }

        /** @return null when the out-of-reach attack correctly landed nothing, else why not */
        private static String beyondTheBound() {
            SimWorld w = plain();
            int mob = w.spawn("zombie", 0.5D, 64.0D, -14.5D, 20.0D);
            w.give(0, "iron_sword", 1);
            w.setHeldSlot(0);
            EvalHarness h = new EvalHarness(w);
            double[] eye = w.eyePos();
            double[] mobEye = w.entityEye(mob);
            double distSq = sq(eye[0] - mobEye[0]) + sq(eye[1] - mobEye[1]) + sq(eye[2] - mobEye[2]);
            if (EntityCombat.serverAccepts(distSq, true)) {
                return String.format("the mob spawned at distSq=%.2f, which is INSIDE the bound, so"
                        + " this half of the task is not testing what it claims", distSq);
            }
            h.runtime().submitInteract(InteractIntent.attack(mob));
            h.runInteract(60);
            // Nothing landed: the reach check refuses BEFORE any attack is sent, so the entity is
            // untouched. A controller that swung anyway would show hits > 0 here.
            if (w.entityHits(mob) != 0 || w.entityHealth(mob) != 20.0D) {
                return String.format("an attack from distSq=%.2f -- past the server's bound of %.1f"
                        + " -- landed anyway: hits=%d health=%.1f", distSq,
                        sq(EntityCombat.reachRadius(true)), w.entityHits(mob), w.entityHealth(mob));
            }
            return null;
        }

        private static double sq(double v) {
            return v * v;
        }
    }

    // ===== T16 =====

    /**
     * A sustained use, scored on the two things a meal changes.
     *
     * <p>This task exists because of the use CLOCK, and its history is the reason. Vanilla counts
     * an in-progress use down inside the player ({@code EntityPlayer.onUpdate:286}), so the player
     * owns it. A harness that ticked the act layer without ticking the player gave
     * {@code HoldController} a world where {@code itemInUseCount()} sat at 32 forever -- and
     * {@code HoldController} responded exactly as a good controller should: it watched the count
     * fail to move for dozens of ticks and refused, naming the reason and quoting
     * {@code Minecraft.isGamePaused}. The controller was right and the world was wrong, and the
     * result was a test that could not run. {@code SimWorld.tick} now advances the use, because
     * the thing that accrues time for the player is the player.
     *
     * <p>Two halves, and the second is the one that distinguishes a real hold from a start:
     *
     * <ul>
     *   <li><b>A held use RUNS OUT.</b> Bread eaten at 10 HP restores the item's own
     *       {@code healAmount} and is spent out of the hotbar. Both are read off the world.</li>
     *   <li><b>An interrupted use SPENDS NOTHING.</b> A screen opening calls
     *       {@code KeyBinding.unPressAllKeys}, so a hold that asserted once and was never
     *       re-asserted is a use vanilla has already stopped. The bread stays and the health bar
     *       does not move -- and that is the correct outcome, not a failure to eat.</li>
     * </ul>
     */
    static final class T16HeldUseRunsOutAndAnInterruptedOneSpendsNothing implements Task {
        @Override
        public String id() {
            return "T16 held_use_runs_out_and_an_interrupted_one_spends_nothing";
        }

        @Override
        public Result run() {
            Half finished = aHeldUseRunsOut();
            Half interrupted = anInterruptedUseSpendsNothing();
            boolean pass = finished.pass() && interrupted.pass();
            return new Result(id(), pass,
                    "held to the end: " + finished.fact()
                            + " | interrupted: " + interrupted.fact());
        }

        private record Half(boolean pass, String fact) {
        }

        private static Half aHeldUseRunsOut() {
            SimWorld w = plain().atHealth(10.0D);
            w.give(0, "bread", 2);
            w.setHeldSlot(0);
            EvalHarness h = new EvalHarness(w);
            h.runtime().submitInteract(InteractIntent.holdUntilDone());
            h.runInteract(200);
            // The heal is the ITEM's own number, read from the registered ItemFood rather than
            // typed into the assertion: a task that hardcoded a heal amount would pass on the
            // wrong food and fail on a rebalance. What is asserted is that health ROSE and the
            // bread was SPENT; both values are printed.
            // (Measured here: bread takes 10.0 -> 15.0. That number is reported, not pinned.)
            int bread = w.count("bread");
            boolean ate = w.health() > 10.0D;
            boolean spent = bread == 1;
            boolean complete = h.phaseOf(ActSlot.INTERACT) == ActPhase.COMPLETE;
            String fact = String.format("2 bread -> %d, health 10.0 -> %.1f, slot %s",
                    bread, w.health(), h.phaseOf(ActSlot.INTERACT));
            if (!ate) {
                return new Half(false, fact + " -- the meal neither healed nor spent the bread");
            }
            if (!spent) {
                return new Half(false, fact + " -- the bread was not spent");
            }
            if (!complete) {
                return new Half(false, fact + " -- the hold did not reach COMPLETE");
            }
            return new Half(true, fact);
        }

        private static Half anInterruptedUseSpendsNothing() {
            SimWorld w = plain().atHealth(10.0D);
            w.give(0, "bread", 2);
            w.setHeldSlot(0);
            EvalHarness h = new EvalHarness(w);
            h.runtime().submitInteract(InteractIntent.holdUntilDone());
            h.run(6);
            // The world eats the key, exactly as Minecraft.displayGuiScreen ->
            // KeyBinding.unPressAllKeys does. The controller can only notice by reading
            // useKeyHeld() at the top of a later tick, which is what makes this a test of the
            // controller rather than of the harness's own bookkeeping.
            w.guiOpened();
            h.run(120, () -> terminal(ActSlot.INTERACT, h));
            int bread = w.count("bread");
            boolean untouched = bread == 2 && w.health() == 10.0D;
            String fact = String.format("a screen opened mid-use: %d bread left, health %.1f,"
                    + " slot %s", bread, w.health(), h.phaseOf(ActSlot.INTERACT));
            if (!untouched) {
                return new Half(false, fact + " -- an interrupted use consumed bread or healed,"
                    + " and an interrupted use does neither in vanilla");
            }
            return new Half(true, fact);
        }
    }

    // ===== T17 =====

    /**
     * A craft the SERVER refuses.
     *
     * <p>This is the task that gives {@code CraftController}'s SETTLING and CONFIRMING states any
     * reason to exist, and nothing else in the suite did. Every other craft task uses a window
     * that agrees with every click, so the controller's whole verify-after-the-fact half ran
     * against a world where verification could not fail. A controller with the confirm step
     * deleted passed all of them: the client applied its own optimistic clicks, the matrix came
     * out empty because that is what it had optimistically done, and the output appeared because
     * the client believed it had been given one. Verified: that mutant survived until this task
     * existed.
     *
     * <p>The window here refuses from the FIRST click and then resyncs, which is the shape of a
     * mismatched window id: the client keeps applying locally, the server applies nothing, and
     * the client's own view is the only thing that looks like progress.
     *
     * <p><b>One stated exception to "world facts only".</b> The load-bearing world fact here is
     * that the player does NOT end up holding a {@code crafting_table} the server never granted.
     * But a controller that invents one in its own report while the world holds none is exactly
     * the failure being hunted -- "reports healthy while being wrong", the shape
     * {@code SETTLING} was written for -- so the controller's terminal verdict is asserted too.
     * It is a typed boolean on a result record, never a parsed message, and it is the SECOND half
     * of the assertion rather than a substitute for the first.
     */
    static final class T17ARefusedCraftReportsFailureAndInventsNothing implements Task {
        @Override
        public String id() {
            return "T17 a_refused_craft_reports_failure_and_invents_nothing";
        }

        @Override
        public Result run() {
            RecipeView recipe = firstRecipe("crafting_table");
            if (recipe == null) {
                return new Result(id(), false, "the real recipe table has no crafting_table recipe");
            }
            Verdict placementRefused = runAgainst(
                    "the server refused every click", recipe, win -> win.refuseFrom(1, 1));
            Verdict takeRefused = runAgainst(
                    "the server applied the ingredients and refused the TAKE", recipe,
                    win -> win.refuseResultTake(1));
            boolean pass = placementRefused.pass() && takeRefused.pass();
            return new Result(id(), pass,
                    "placement refused: " + placementRefused.fact()
                            + " | take refused: " + takeRefused.fact());
        }

        private record Verdict(boolean pass, String fact) {
        }

        /** Arms {@code arm}, drives one craft, and scores the two things that must both hold. */
        private static Verdict runAgainst(String what, RecipeView recipe,
                                         java.util.function.UnaryOperator<SimCraftWindow> arm) {
            SimWorld w = new SimWorld();
            SimCraftWindow win = arm.apply(SimCraftWindow.playerWindow(w).carrying(0, "planks", 0, 4));
            CraftOutcome out = drive(new CraftController(recipe), win, 400);
            int tables = w.count("crafting_table");
            String fact = String.format("%s: terminal=%s ok=%s; the player holds %d crafting_table;"
                            + " %d click(s) dropped by the server; matrix=%s; outcome=%s",
                    what, out != null && out.terminal(), out != null && out.ok(), tables,
                    win.droppedClicks(), win.matrixContents(),
                    out == null ? "none" : out.message());
            if (out == null || !out.terminal()) {
                return new Verdict(false, fact + " -- never reached a terminal outcome");
            }
            if (tables != 0) {
                return new Verdict(false, fact + " -- the player holds an output the server never"
                        + " granted, so the craft is a fiction");
            }
            if (out.ok()) {
                return new Verdict(false, fact + " -- the controller reported SUCCESS for a craft"
                        + " the server refused");
            }
            return new Verdict(true, fact);
        }
    }

    // ===== T18 =====

    /**
     * The first task in this suite that is a game rather than a capability.
     *
     * <p><b>The goal, and nothing else.</b> "Break the iron ore." The task body below does not say
     * how. It does not name wood, planks, a crafting table, a bench, a pickaxe, cobblestone or
     * sticks -- not one of those words appears outside the policy's own trace. It builds a world
     * in which the goal is possible and hands a {@link GoalPolicy} the goal string, and the policy
     * works out the rest from the recipe table and the world as it stands.
     *
     * <p><b>Why this shape and not a longer scripted chain.</b> Seventeen tasks each name the
     * controller they want, which is exactly why seventeen green tasks add up to no evidence that
     * the agent can play: a scripted chain of nine steps is still nine independent assertions, and
     * it passes whether or not the pieces fit. The step that matters -- noticing that bare hands
     * cannot harvest iron ore, and going to build something that can, then discovering that costs
     * a bench, which costs planks, which cost a log -- is a step no scripted task can contain,
     * because a script has to know the answer before it runs. Here the answer is derived per run.
     *
     * <p><b>Why "iron ore" is the goal.</b> It chains the most capabilities the substrate can
     * honestly support, and every link is a real one: the harvest gate (bare hands on iron ore drop
     * <em>nothing</em> -- measured, not assumed), the item registry (what can harvest it), the
     * recipe table (a pickaxe is planks and sticks), the player's own 2x2 grid, a block PLACED into
     * the world and then RIGHT-CLICKED open as a 3x3 bench, the hotbar (a tool in slot 9 is not
     * held and does not work), and finally navigation, digging and drops. Change the goal to
     * anything else and a different chain comes out; the depth is the recipe table's, not this
     * file's.
     *
     * <p><b>Scored on the world, in three independent facts.</b> The ore cell is air, the player
     * holds the ore, and the player is alive. A chain that fabricated any of them fails. Nothing
     * here reads the policy's own opinion of its success -- it reports the trace, and the trace is
     <em>printed</em> rather than asserted, because a trace is evidence and an assertion on prose
     * is not.
     *
     * <p><b>What this task CANNOT show, stated here because the alternative is a lie about
     * coverage.</b> {@code SimWorld} has no mob AI, no lighting and no network, so this is not
     * "play a match" and nothing here should be quoted as such:
     * <ul>
     *   <li><b>No threat, so no decision.</b> There is nothing in this world to fight, avoid or
     *       prepare for. The "decide whether to fight" shape is untestable here and this task does
     *       not pretend to cover it; T15 measures an attack's reach bound and nothing more.</li>
     *   <li><b>No light, so no caves and no night.</b> Every step happens on an open plain in
     *       full daylight. Darkness-gated navigation and torch placement are uncovered.</li>
     *   <li><b>No network, so no other player and no server disagreement.</b> The bench opens
     *       locally; a real one is a packet and a screen. See the container-opening entry in
     *       {@code SimWorld.KNOWN_GAPS}.</li>
     *   <li><b>No furnace, no smelting.</b> The goal stops at the raw ore deliberately: iron ore
     *       drops ore, and turning it into an ingot needs a furnace this substrate has no window
     *       for. A goal phrased "make an iron ingot" would fail on the substrate rather than on
     *       the agent, which is the one failure mode a measurement must not have.</li>
     *   <li><b>It is a policy, not a model.</b> A pass says the controllers compose. It does not
     *       say a language model would have produced this plan, and this suite contains no
     *       evidence either way about that.</li>
     * </ul>
     *
     * <p><b>One run, one world, no re-roll.</b> A failing chain is a finding and is reported as
     * one. Nothing here is retried until it goes green.
     */
    static final class T18MineIronOreFromNothing implements Task {
        @Override
        public String id() {
            return "T18 break_the_iron_ore_starting_from_nothing";
        }

        /** Where the ore is. Chosen far enough out that reaching it is a real walk. */
        private static final int ORE_X = 10;
        private static final int ORE_Y = 64;
        private static final int ORE_Z = -12;

        @Override
        public Result run() {
            SimWorld w = new SimWorld().plain(64, "stone", -24, 24, -24, 24).standOn(0, 64, 0)
                    .facing(0f).atHealth(20.0D);
            // The vein. Bare hands on it drop nothing at all, which is the whole point: the goal
            // is unreachable until the agent has built something, and nothing tells it that but
            // the harvest gate.
            w.box(ORE_X, ORE_Y, ORE_Z, ORE_X, ORE_Y, ORE_Z, "iron_ore");
            // Raw material within walking distance, so the chain is a matter of sequencing and
            // not of exploration. A tree is the only wood source, exactly as in vanilla.
            //
            // <b>A 2x2 trunk, because that is what an oak is.</b> The first version of this was a
            // single 1x1 column, which is a shape Minecraft does not generate -- and it is
            // unmineable above the first block: dig the bottom log and the one over it has no
            // standable cell beside it, so the chain died on geometry that exists only in a
            // test fixture. A 2x2 trunk gives every log in the column a neighbour to stand beside,
            // which is what makes an oak fellable at all.
            w.box(4, 64, -4, 5, 67, -3, "log");

            EvalHarness h = new EvalHarness(w);
            GoalPolicy policy = new GoalPolicy(w, h);

            // The goal, verbatim, and the whole of what the policy is told.
            boolean got = policy.obtain("iron_ore");

            boolean oreIsGone = w.blockAt(ORE_X, ORE_Y, ORE_Z) == null;
            boolean holdingOre = w.count("iron_ore") > 0;
            boolean alive = w.health() > 0.0D;
            boolean pass = got && oreIsGone && holdingOre && alive;

            String verdict = pass
                    ? "the goal was reached: the ore cell is air and the player holds the ore"
                    : "the goal was NOT reached: " + describeFailure(oreIsGone, holdingOre, alive);
            return new Result(id(), pass, String.format(
                    "%s. policy reported %s. ore cell (%d,%d,%d)=%s; iron_ore=%d; health=%.1f;"
                            + " inventory=%s; end pos=(%.2f,%.2f,%.2f). chain: %s",
                    verdict, got ? "success" : "failure", ORE_X, ORE_Y, ORE_Z,
                    String.valueOf(w.blockAt(ORE_X, ORE_Y, ORE_Z)), w.count("iron_ore"), w.health(),
                    w.describeInventory(), w.posX(), w.posY(), w.posZ(), policy.traceSummary()));
        }

        /** Which world fact is missing, so a failure names the step rather than the task. */
        private static String describeFailure(boolean oreIsGone, boolean holdingOre, boolean alive) {
            List<String> missing = new java.util.ArrayList<>();
            if (!oreIsGone) {
                missing.add("the ore block is still standing");
            }
            if (!holdingOre) {
                missing.add("the player holds no ore");
            }
            if (!alive) {
                missing.add("the player died");
            }
            return missing.isEmpty() ? "the policy gave up without the world disagreeing"
                    : String.join(" and ", missing);
        }
    }

    // ===== T19 =====

    /**
     * Preparation under a constraint, at the SECOND gate.
     *
     * <p><b>The goal, and nothing else.</b> {@code "gold_ore"}. Not one word of the plan is in this
     * task body: not gold, not pickaxe, not iron, not ingot, not smelting. The world is built so the
     * goal is reachable in principle and a {@link GoalPolicy} is handed the string.
     *
     * <p><b>Why this is not T18 again.</b> T18 measures ONE escalation: bare hands cannot harvest,
     * so a tool is built. This measures the escalation T18's world made unreachable -- the tool
     * this world can afford is not good enough for the goal. Asked of the real registry rather than
     * assumed, gold ore is harvested by exactly two items, {@code iron_pickaxe} and
     * {@code diamond_pickaxe}; a wooden or stone pickaxe is refused by {@code canHarvestBlock}. So
     * the first gate (needs a tool) and the second gate (needs a BETTER tool) are both real, in
     * that order, and the run has to survive the first to discover the second. The world supplies
     * the tool's LAST INGREDIENT, in the form the substrate can carry, so the chain gets all the way
     * to the craft rather than dying on "nothing here drops iron".
     *
     * <p><b>That ingredient is an INGOT, and the first version of this world put a VEIN there and
     * so could never finish.</b> An {@code iron_ore} block is one step short of the last ingredient:
     * {@code iron_pickaxe} wants three {@code iron_ingot}, and 1.8.9 has no recipe that turns ore
     * into an ingot. {@code Craft.recipesFor("iron_ingot")} offers exactly one way and it is
     * {@code iron_block}, whose own bill is nine ingots -- so the recipe graph walks into a cycle
     * and the policy correctly, permanently, reports iron_ingot out of reach. Measured, not assumed:
     * the failing run's own trace ends "no tool that harvests gold_ore could be built from what this
     * world offers", having cost both registry candidates and walked the iron_ingot/iron_block
     * cycle in between.
     *
     * <p>So what the row was measuring was the SUBSTRATE, not the agent -- the one thing this suite
     * exists to refuse. {@code SimWorld.KNOWN_GAPS} says a furnace "returns a plain placement
     * refusal" and only a crafting table opens, and T18's own list of what it cannot show puts it in
     * writing: "No furnace, no smelting... A goal phrased 'make an iron ingot' would fail on the
     * substrate rather than on the agent, which is the one failure mode a measurement must not
     * have." T19 asked for exactly that goal. The world is therefore stocked with the ingots
     * themselves -- the last ingredient, in the form this substrate can carry -- which is the same
     * move as the pre-placed bench: fix the fixture so the fixture stops being the thing under test.
     *
     * <p><b>The stakes are unchanged by that swap, which is why this is a fixture fix rather than a
     * softer task.</b> Both gates remain the registry's own answers: nothing in hand harvests gold
     * ore, so a tool is needed ({@code canHarvestBlock}), and the only two items in the whole
     * registry that harvest it are {@code iron_pickaxe} and {@code diamond_pickaxe}, so the tool has
     * to be that tier. What the run still has to derive for itself -- bench, planks, sticks, the
     * 3x3, the hotbar, the walk, the dig -- is every step T19 was written to measure. Only the smelt
     * is gone, and the smelt was never in the goal string.
     *
     * <p><b>The bench is pre-placed, deliberately, and so is the iron: both are world state like any
     * other.</b> T18 spends most of its ticks proving a block can be placed and opened. This task is
     * not about that, and a world that made the agent build its own bench would re-measure T18 and
     * bury the escalation it is here for. The task says nothing about either.
     *
     * <p><b>Scored on the world.</b> The gold cell is air, the player holds gold ore, the player is
     * alive. The policy's own verdict is printed and never asserted, for the reason the whole suite
     * has: a controller that reports success while the ore is still in the ground is the failure
     * this suite exists to catch.
     */
    static final class T19PrepareBeforePointingAtIt implements Task {
        @Override
        public String id() {
            return "T19 prepare_before_you_point_at_it";
        }

        private static final int GOLD_X = 10;
        private static final int GOLD_Y = 64;
        private static final int GOLD_Z = 6;

        @Override
        public Result run() {
            SimWorld w = new SimWorld().plain(64, "dirt", -20, 20, -20, 20).standOn(0, 64, 0)
                    .facing(0f).atHealth(20.0D);
            w.put(2, 64, 2, "crafting_table");
            // Wood and stone, all inside the 20-block search radius the policy looks over.
            w.box(-8, 64, 4, -3, 64, 5, "log");
            w.box(6, 64, -6, 7, 64, -5, "stone");
            // The last ingredient of the only tool this world can afford, GIVEN rather than mined.
            // An iron_ore vein cannot stand here: nothing in 1.8.9 smelts it, and the ingot is what
            // iron_pickaxe actually costs, so a vein makes the goal unreachable no matter what the
            // policy decides. See the class doc for the measurement that faulted.
            w.give(0, "iron_ingot", 3);
            // The goal, and the only gold in the world.
            w.put(GOLD_X, GOLD_Y, GOLD_Z, "gold_ore");

            EvalHarness h = new EvalHarness(w);
            GoalPolicy policy = new GoalPolicy(w, h);
            boolean got = policy.obtain("gold_ore");

            boolean gone = w.blockAt(GOLD_X, GOLD_Y, GOLD_Z) == null;
            boolean holding = w.count("gold_ore") > 0;
            boolean alive = w.health() > 0.0D;
            boolean pass = got && gone && holding && alive;
            return new Result(id(), pass, String.format(java.util.Locale.ROOT,
                    "%s. policy reported %s. gold cell (%d,%d,%d)=%s; gold_ore=%d; iron_ingot=%d;"
                            + " in hand=%s; health=%.1f; inventory=%s; end pos=(%.2f,%.2f,%.2f);"
                            + " chain: %s",
                    pass ? "the goal was reached"
                            : "the goal was NOT reached: " + describe(gone, holding, alive),
                    got ? "success" : "failure", GOLD_X, GOLD_Y, GOLD_Z,
                    String.valueOf(w.blockAt(GOLD_X, GOLD_Y, GOLD_Z)), w.count("gold_ore"),
                    w.count("iron_ingot"), w.slotName(w.heldSlot()), w.health(),
                    w.describeInventory(), w.posX(), w.posY(), w.posZ(), policy.traceSummary()));
        }

        private static String describe(boolean gone, boolean holding, boolean alive) {
            List<String> missing = new java.util.ArrayList<>();
            if (!gone) {
                missing.add("the gold ore is still standing, so it was never broken");
            }
            if (!holding) {
                missing.add("the player holds no gold ore");
            }
            if (!alive) {
                missing.add("the player died");
            }
            return missing.isEmpty() ? "the policy gave up without the world disagreeing"
                    : String.join(" and ", missing);
        }
    }

    // ===== T20 =====

    /**
     * A route that must not be taken.
     *
     * <p><b>The goal, and nothing else.</b> {@code "cobblestone"}. The task body names no route, no
     * hazard, no detour and no controller.
     *
     * <p><b>Why the world is built the way it is.</b> A band of lava four blocks wide stands between
     * the player and the only stone in the world, and it reaches the northern edge of the land, so
     * there is exactly one way past it: south, then east. The straight line from the spawn to the
     * outcrop crosses the band; the route that works does not.
     *
     * <p><b>Why the judgement has to be about the PATH and not the destination.</b> A
     * {@code RouteIntent} is planned, and {@code NeighborGen} refuses a lava cell outright while
     * offering a swim for a water cell, so a planned route cannot walk into the band however it is
     * asked. {@code walk_straight} steers by bearing and never plans, drives the body into whatever
     * is on the line, and reports the lava afterwards as a {@link NavHazard}. The two agree on the
     * destination and disagree about the journey, so "the player holds cobblestone" alone would be
     * a green row measuring nothing. What is asserted instead is that no tick of the walk ever had
     * the body inside a lava cell -- read off the world's own position trace, not off any
     * controller's opinion -- and the straight-line distance and the cells actually walked are
     * printed side by side, so a run that detoured and a run that clipped a lava lip are visibly
     * different rows rather than the same word.
     *
     * <p><b>The pickaxe is given, and that is the isolation.</b> The only variable in this run is
     * the route. A world that also made the agent build a tool would re-measure T19 and bury the
     * hazard behind forty ticks of crafting.
     */
    static final class T20TheDirectLineIsLava implements Task {
        @Override
        public String id() {
            return "T20 the_direct_line_is_lava";
        }

        private static final int SPAWN_X = -8;
        private static final int SPAWN_Z = -8;
        private static final int LAVA_X0 = 4;
        private static final int LAVA_X1 = 7;
        private static final int LAVA_Z0 = -24;
        private static final int LAVA_Z1 = 0;
        private static final int ORE_X = 9;
        private static final int ORE_Y = 64;
        private static final int ORE_Z = 2;

        @Override
        public Result run() {
            SimWorld w = new SimWorld().plain(64, "dirt", -24, 23, LAVA_Z0, 20)
                    .standOn(SPAWN_X, 64, SPAWN_Z).facing(0f).atHealth(20.0D);
            // The band reaches the northern edge of the land on purpose. Past it there is no floor
            // at all, so the planner cannot slip around the end and the southern detour is the
            // only route there is.
            w.box(LAVA_X0, 64, LAVA_Z0, LAVA_X1, 64, LAVA_Z1, "lava");
            w.box(ORE_X, ORE_Y, ORE_Z, ORE_X + 1, ORE_Y, ORE_Z + 1, "stone");
            w.give(0, "stone_pickaxe", 1);

            EvalHarness h = new EvalHarness(w);
            GoalPolicy policy = new GoalPolicy(w, h);
            boolean got = policy.obtain("cobblestone");

            boolean gone = w.blockAt(ORE_X, ORE_Y, ORE_Z) == null;
            boolean holding = w.count("cobblestone") > 0;
            boolean alive = w.health() > 0.0D;
            int inLava = cellsOccupiedBy(w, "lava", LAVA_X0, 64, LAVA_Z0, LAVA_X1, 64, LAVA_Z1);
            int lineBlocked = lineCellsHolding(w, "lava", SPAWN_X, SPAWN_Z, ORE_X, ORE_Z, 64);
            double straight = Math.hypot(ORE_X - SPAWN_X, ORE_Z - SPAWN_Z);
            int walked = cellsWalked(w);
            // The self-check is in the pass condition on purpose. Without it, an edit that moved the
            // band a block sideways would leave a green row that had quietly stopped testing
            // anything, because a straight line across clear ground is also "no ticks in lava".
            boolean pass = got && gone && holding && alive && inLava == 0 && lineBlocked > 0;
            return new Result(id(), pass, String.format(java.util.Locale.ROOT,
                    "%s. policy reported %s. outcrop cell (%d,%d,%d)=%s; cobblestone=%d;"
                            + " health=%.1f; ticks with the body in a lava cell=%d; the straight line"
                            + " is %.1f blocks and crosses %d lava cell(s), while the walk changed"
                            + " cell %d time(s); end pos=(%.2f,%.2f,%.2f); chain: %s",
                    pass ? "the goal was reached and the walk went around a lethal straight line"
                            : "the goal was NOT reached: " + describe(gone, holding, alive, inLava,
                                    lineBlocked),
                    got ? "success" : "failure", ORE_X, ORE_Y, ORE_Z,
                    String.valueOf(w.blockAt(ORE_X, ORE_Y, ORE_Z)), w.count("cobblestone"),
                    w.health(), inLava, straight, lineBlocked, walked, w.posX(), w.posY(), w.posZ(),
                    policy.traceSummary()));
        }

        private static String describe(boolean gone, boolean holding, boolean alive, int inLava,
                int lineBlocked) {
            List<String> missing = new java.util.ArrayList<>();
            if (!gone) {
                missing.add("the stone was never broken");
            }
            if (!holding) {
                missing.add("the player holds no cobblestone");
            }
            if (!alive) {
                missing.add("the player died");
            }
            if (inLava > 0) {
                missing.add("the walk spent " + inLava + " tick(s) inside the lava band");
            }
            if (lineBlocked == 0) {
                missing.add("the straight line from the spawn to the outcrop crosses no lava at"
                        + " all, so this world no longer asks the question the task exists for");
            }
            return missing.isEmpty() ? "the policy gave up without the world disagreeing"
                    : String.join(" and ", missing);
        }
    }

    // ===== T21 =====

    /**
     * Tool economy: two ways to the same block, and the agent has to count rather than merely
     * arrive.
     *
     * <p><b>The goal, and nothing else.</b> {@code "cobblestone"}. The world is stocked so that BOTH
     * a wooden and a stone pickaxe can be built -- logs for one, a stone outcrop for the other, and
     * a bench already standing, so the only thing separating them is the raw material.
     *
     * <p><b>Why reaching the goal is not the measurement.</b> Either tool ends with the player
     * holding cobblestone, so "it got the cobble" is a row that cannot tell a counted choice from a
     * lucky one. What separates them is what the WORLD looks like afterwards: the cheap route
     * spends logs, the expensive route digs the outcrop apart. So the assertion is on the damage --
     * zero outcrop cells gone, at least one log gone -- counted off the block grid rather than off
     * anything the policy said. An agent that built the stone pickaxe, dug three cobblestone with
     * it and reached the identical goal FAILS this task, and it should: it solved the problem and
     * missed the question.
     *
     * <p><b>What the world is measuring underneath.</b> {@code GoalPolicy.acquireAToolFor} walks
     * the item registry, and registry order for stone begins at {@code iron_pickaxe} -- an item
     * this world cannot build at any price, because its ingredients want a furnace. An agent that
     * took the first registry hit would spend its run failing on iron and would never reach the
     * choice at all. That ordering is a fact about this repo, not a guess, and the trace prints the
     * ranking so a reader can see which branch ran.
     */
    static final class T21CountBeforeYouSpend implements Task {
        @Override
        public String id() {
            return "T21 count_before_you_spend";
        }

        static final int STONE_X0 = 5;
        static final int STONE_X1 = 8;
        static final int STONE_Z0 = -6;
        static final int STONE_Z1 = -4;
        static final int LOG_X0 = -8;
        static final int LOG_X1 = -3;
        static final int LOG_Z0 = 6;
        static final int LOG_Z1 = 7;

        @Override
        public Result run() {
            SimWorld w = buildWorld();
            int stoneBefore = countBlocks(w, "stone", STONE_X0, 64, STONE_Z0, STONE_X1, 64, STONE_Z1);
            int logsBefore = countBlocks(w, "log", LOG_X0, 64, LOG_Z0, LOG_X1, 64, LOG_Z1);

            EvalHarness h = new EvalHarness(w);
            GoalPolicy policy = new GoalPolicy(w, h);
            boolean got = policy.obtain("cobblestone");

            int stoneAfter = countBlocks(w, "stone", STONE_X0, 64, STONE_Z0, STONE_X1, 64, STONE_Z1);
            int logsAfter = countBlocks(w, "log", LOG_X0, 64, LOG_Z0, LOG_X1, 64, LOG_Z1);
            int stoneSpent = stoneBefore - stoneAfter;
            int logsSpent = logsBefore - logsAfter;
            int held = w.count("cobblestone");
            boolean holding = held > 0;
            boolean alive = w.health() > 0.0D;
            // The economy claim, on the block grid: the outcrop was spent no more than the goal
            // itself consumed, and a tool was built out of the cheap material.
            //
            // The first version of this said `stoneSpent == 0`, which is arithmetically
            // unsatisfiable next to `holding`: cobblestone's ONLY source in this world is the
            // outcrop, so the row demanded the goal and zero of the goal's only source at once. It
            // was a broken measurement rather than a strict one, and it would have failed a run that
            // did everything right.
            //
            // The property replaces the number: the outcrop must not have been spent BUILDING A
            // TOOL. A cheap run digs one cell and holds one cobblestone; an expensive run digs that
            // one plus the three a stone pickaxe costs, and is four against one. Nothing here
            // hard-codes how much the goal costs, so the row survives the goal changing.
            boolean cheap = stoneSpent <= held && logsSpent > 0;
            boolean pass = got && holding && alive && cheap;
            return new Result(id(), pass, String.format(java.util.Locale.ROOT,
                    "%s. policy reported %s. cobblestone=%d; health=%.1f; outcrop cells spent=%d"
                            + " of %d; log cells spent=%d of %d; in hand=%s; inventory=%s;"
                            + " end pos=(%.2f,%.2f,%.2f); chain: %s",
                    pass ? "the goal was reached on the cheap route: the outcrop paid for the goal"
                            + " and the tool came from the logs"
                            : "the goal was NOT reached the cheap way: "
                                    + describe(holding, alive, stoneSpent, logsSpent, held),
                    got ? "success" : "failure", held, w.health(), stoneSpent,
                    stoneBefore, logsSpent, logsBefore, w.slotName(w.heldSlot()),
                    w.describeInventory(), w.posX(), w.posY(), w.posZ(), policy.traceSummary()));
        }
        private static String describe(boolean holding, boolean alive, int stoneSpent,
                int logsSpent, int held) {
            List<String> missing = new java.util.ArrayList<>();
            if (!holding) {
                missing.add("the player holds no cobblestone");
            }
            if (!alive) {
                missing.add("the player died");
            }
            if (stoneSpent > held) {
                missing.add((stoneSpent - held) + " outcrop cell(s) were spent beyond the goal"
                        + " itself, so the expensive tool was built even though a cheaper one was"
                        + " available");
            }
            if (logsSpent == 0) {
                missing.add("no log was spent, so no tool was built out of the cheap material");
            }
            return missing.isEmpty() ? "the policy gave up without the world disagreeing"
                    : String.join(" and ", missing);
        }


        /** The stocked world. T23 uses this same shape and changes one thing and nothing else. */
        static SimWorld buildWorld() {
            SimWorld w = new SimWorld().plain(64, "dirt", -20, 20, -20, 20).standOn(0, 64, 0)
                    .facing(0f).atHealth(20.0D);
            w.put(2, 64, 2, "crafting_table");
            w.box(LOG_X0, 64, LOG_Z0, LOG_X1, 64, LOG_Z1, "log");
            w.box(STONE_X0, 64, STONE_Z0, STONE_X1, 64, STONE_Z1, "stone");
            return w;
        }
    }

    // ===== T22 =====

    /**
     * Sequencing under a dependency: an intermediate artefact that must be made, PLACED, OPENED and
     * then used, and which is paid for out of the same pile the goal is paid from.
     *
     * <p><b>The goal, and nothing else.</b> {@code "ladder"}.
     *
     * <p><b>Why a ladder and not a pickaxe.</b> T18's pickaxe chain spends four planks on the
     * bench out of a pile large enough to cover the whole bill twice, so the ORDER of the bench
     * against the ingredients is a detail there. A ladder needs SEVEN sticks -- fourteen planks of
     * demand against a four-plank bench -- and in 1.8.9 the stick recipe yields four per craft, so
     * the bill is only settled by a second pass. The bench is not a step the agent does before the
     * work; it is a cost taken out of the budget the work is paid from, and an agent that reserved
     * the goal's ingredients before paying for its tools is short at the last click. The world is a
     * field of logs with nothing else in it, so the arithmetic is the only thing that can decide
     * the run.
     *
     * <p><b>Scored on the world.</b> The player holds a ladder, a crafting table is STANDING in the
     * world (the bench was placed, not conjured -- {@code SimWorld} has no other way for one to
     * appear), and no crafting-table item is left in the bag, because placing it consumed the stack
     * the way it does on a live client. A ladder in the bag with no table in the world would mean
     * a craft that used a 3x3 grid which never existed, and that is exactly the fabrication this
     * suite is built to refuse.
     */
    static final class T22TheBenchHasToExistFirst implements Task {
        @Override
        public String id() {
            return "T22 the_bench_has_to_exist_first";
        }

        private static final int LOG_X0 = -6;
        private static final int LOG_X1 = 1;
        private static final int LOG_Z0 = 8;
        private static final int LOG_Z1 = 9;
        private static final int WORLD_X0 = -20;
        private static final int WORLD_X1 = 20;
        private static final int WORLD_Z0 = -20;
        private static final int WORLD_Z1 = 20;

        @Override
        public Result run() {
            SimWorld w = new SimWorld().plain(64, "dirt", WORLD_X0, WORLD_X1, WORLD_Z0, WORLD_Z1)
                    .standOn(0, 64, 0).facing(0f).atHealth(20.0D);
            w.box(LOG_X0, 64, LOG_Z0, LOG_X1, 64, LOG_Z1, "log");

            EvalHarness h = new EvalHarness(w);
            GoalPolicy policy = new GoalPolicy(w, h);
            boolean got = policy.obtain("ladder");

            int tablesStanding = countBlocks(w, "crafting_table", WORLD_X0, 64, WORLD_Z0,
                    WORLD_X1, 66, WORLD_Z1);
            int tableItems = w.count("crafting_table");
            int ladders = w.count("ladder");
            int logsLeft = countBlocks(w, "log", LOG_X0, 64, LOG_Z0, LOG_X1, 64, LOG_Z1);
            int logsTotal = (LOG_X1 - LOG_X0 + 1) * (LOG_Z1 - LOG_Z0 + 1);
            boolean alive = w.health() > 0.0D;
            boolean placedAndUsed = tablesStanding == 1 && tableItems == 0 && ladders > 0;
            boolean pass = got && placedAndUsed && alive;
            return new Result(id(), pass, String.format(java.util.Locale.ROOT,
                    "%s. policy reported %s. ladder=%d; crafting tables standing=%d; crafting-table"
                            + " items still in the bag=%d; logs left=%d of %d; health=%.1f;"
                            + " inventory=%s; end pos=(%.2f,%.2f,%.2f); chain: %s",
                    pass ? "the ladder exists and a bench was placed and opened to make it"
                            : "the goal was NOT reached: " + describe(ladders, tablesStanding,
                                    tableItems, alive),
                    got ? "success" : "failure", ladders, tablesStanding, tableItems, logsLeft,
                    logsTotal, w.health(), w.describeInventory(), w.posX(), w.posY(), w.posZ(),
                    policy.traceSummary()));
        }

        private static String describe(int ladders, int tablesStanding, int tableItems,
                boolean alive) {
            List<String> missing = new java.util.ArrayList<>();
            if (ladders == 0) {
                missing.add("the player holds no ladder");
            }
            if (tablesStanding == 0) {
                missing.add("no crafting table ever stood in the world, so no 3x3 grid existed");
            } else if (tableItems > 0) {
                missing.add(tableItems + " crafting-table item(s) are still in the bag, so the bench"
                        + " was never placed");
            }
            if (!alive) {
                missing.add("the player died");
            }
            return missing.isEmpty() ? "the policy gave up without the world disagreeing"
                    : String.join(" and ", missing);
        }
    }

    // ===== T23 =====

    /**
     * Recovery: the goal is reachable, and then it stops being reachable because of something the
     * agent did not do.
     *
     * <p><b>The goal, and nothing else.</b> {@code "cobblestone"} -- the same string T21 is given,
     * against the same world, with exactly one difference: every one of the thirty-six inventory
     * slots already holds a full stack of dirt. That is the controlled comparison. Two tasks, one
     * goal, one world, one variable, so a difference in outcome is attributable to the inventory
     * and to nothing else.
     *
     * <p><b>Why a full inventory is "the item is dropped".</b> {@code SimWorld.pickUp} is the
     * substrate's transcription of a drop entering the inventory: merge into a partial matching
     * stack, else take the first empty slot, else do not take it. With all thirty-six slots holding
     * full stacks there is no merge and no empty slot, so a block breaks correctly, drops
     * correctly, and the item is simply not in the world. The cell goes to air and the inventory
     * does not change. That is a real outcome of a real full inventory on a real client, not a
     * fixture artefact, and it is the one failure a chain that has only ever seen an empty bag
     * will never meet.
     *
     * <p><b>What this task is looking for.</b> Not a green row -- the STEP. A policy that can tell
     * "this world has no logs" from "I have no room for logs" re-plans: make room, drop something,
     * or choose a route that needs fewer stacks. A policy that cannot reads the lost drop as a
     * world that does not drop logs, generalises that to every recipe, and gives up. Both rows are
     * reported; the difference between them is the finding.
     */
    static final class T23TheDropThatNeverLanded implements Task {
        @Override
        public String id() {
            return "T23 the_drop_that_never_landed";
        }

        @Override
        public Result run() {
            SimWorld w = T21CountBeforeYouSpend.buildWorld();
            int freeBefore = fillInventory(w);
            int logsBefore = countBlocks(w, "log", T21CountBeforeYouSpend.LOG_X0, 64,
                    T21CountBeforeYouSpend.LOG_Z0, T21CountBeforeYouSpend.LOG_X1, 64,
                    T21CountBeforeYouSpend.LOG_Z1);

            EvalHarness h = new EvalHarness(w);
            GoalPolicy policy = new GoalPolicy(w, h);
            boolean got = policy.obtain("cobblestone");

            int logsAfter = countBlocks(w, "log", T21CountBeforeYouSpend.LOG_X0, 64,
                    T21CountBeforeYouSpend.LOG_Z0, T21CountBeforeYouSpend.LOG_X1, 64,
                    T21CountBeforeYouSpend.LOG_Z1);
            int freeAfter = freeSlots(w);
            int holding = w.count("cobblestone");
            boolean alive = w.health() > 0.0D;
            boolean pass = got && holding > 0 && alive;
            return new Result(id(), pass, String.format(java.util.Locale.ROOT,
                    "%s. policy reported %s. cobblestone=%d; free inventory slots %d -> %d of 36;"
                            + " log cells removed from the world=%d; health=%.1f; chain: %s",
                    pass ? "the goal was reached with a full bag"
                            : "the goal was NOT reached: " + describe(holding, alive, logsBefore,
                                    logsAfter, freeAfter),
                    got ? "success" : "failure", holding, freeBefore, freeAfter,
                    logsBefore - logsAfter, w.health(), policy.traceSummary()));
        }

        private static String describe(int holding, boolean alive, int logsBefore, int logsAfter,
                int freeAfter) {
            List<String> missing = new java.util.ArrayList<>();
            if (holding == 0) {
                missing.add("the player holds no cobblestone");
            }
            if (!alive) {
                missing.add("the player died");
            }
            if (logsAfter < logsBefore) {
                // The first version of this run dug eight logs for nothing. The block is gone, the
                // drop is not anywhere, and the chain learned the same thing eight times.
                missing.add((logsBefore - logsAfter) + " log(s) were dug out of the world and the"
                        + " bag did not gain one, so the agent destroyed the ground and learned"
                        + " nothing: it could not tell a world with no logs from a bag with no room");
            } else {
                // This is the good failure and it is still a failure. The agent checked the bag,
                // saw that a drop had nowhere to land, and stopped instead of deleting the world.
                // What it cannot do is the one move that would make the goal reachable, because no
                // rule in the policy can put a stack back on the floor: with 0 of 36 slots free
                // there is no cobblestone to be had at any price.
                missing.add("the agent noticed the bag was full and refused to dig rather than"
                        + " deleting blocks it could not carry, which is the right instinct and"
                        + " still not a plan: the goal needs a slot and this policy has no way to"
                        + " make one, so the world has to change before the chain can move again");
            }
            if (freeAfter > 0) {
                missing.add("and the bag had " + freeAfter + " free slot(s) at the end, so a full"
                        + " inventory was not the whole story");
            }
            return String.join(" and ", missing);
        }

        /** Fill every slot with a full stack, the way a player who has been picking up junk is. */
        private static int fillInventory(SimWorld w) {
            int free = freeSlots(w);
            for (int slot = 0; slot < w.inventory().mainInventory.length; slot++) {
                w.give(slot, "dirt", 64);
            }
            return free;
        }
    }

    // ===== T24 =====

    /**
     * The first task that can ask what time it is, and the first one that runs THROUGH a night.
     *
     * <p><b>What was inexpressible before this.</b> Every other task in this suite began at world
     * time 0 and finished inside a few hundred ticks, which is noon on a 24,000-tick day. There
     * was no way to write "the agent got through the night" because there was no night: the
     * substrate had no clock, and the production client -- the thing the suite exists to stand in
     * for -- read {@code World.isDaytime}, which is {@code skylightSubtracted < 4} against a field
     * a client writes once in its constructor and never again. Measured on this tree, a client
     * world built at sunrise held 0 there for its whole life, so at world time 18,000 -- where
     * vanilla's own curve subtracts 11 of 15 -- the only production read of "is it night"
     * answered "no", and nothing in the system could say otherwise. A task asserting survival of
     * the first night would have been asserting a fiction.
     *
     * <p><b>Its shape, and why.</b> A walk is on the order of a hundred ticks and a night is
     * 8,386 of them, so no single route spans one -- and a task that budgeted for it anyway would
     * be measuring its own budget rather than the clock. So this is three steps. A route walked in
     * daylight. Then the night WAITED OUT, asserting that the world really did become night and
     * really did become day again while the agent stood in it, and that
     * {@code ticksUntilDawn()} agrees. Then a SECOND route walked with the clock pinned inside the
     * night, which is the part that is about the darkness rather than about the counter.
     *
     * <p><b>What it asserts, as world facts.</b> Every one is a fact about the world or the body --
     * never {@code ActOutcome.message()}, never the runtime's own opinion: the first walk started
     * in daylight and arrived; the world became night and became day again; the clock was
     * genuinely dark before the second walk; that walk arrived; and the player is alive at the far
     * side.
     *
     * <p><b>The control is inside the task, not beside it.</b> The same world is then driven past
     * dawn with {@code doDaylightCycle} off, and the task asserts the night did NOT arrive and the
     * clock stayed at 0. Without that, "the world became night" and "the fixture says night" are
     * the same row, because a substrate that claims night unconditionally passes the first half
     * every time. The pair is the finding; either half alone is a tautology.
     *
     * <p><b>What this CANNOT show, which is most of what "surviving a night" means.</b> The
     * substrate has a clock and no lighting: nothing spawns in the dark, no zombie burns at
     * dawn, no light level gates a cell, and a torch changes nothing (see {@link
     * SimWorld#KNOWN_GAPS}). The second leg is walked in the dark, but "dark" here means a
     * counter said so and nothing else -- no hazard appeared, no light changed, no mob moved. So
     * this row is evidence that the CONTROLLERS compose under a clock that says it is night, and
     * it is not evidence about danger, shelter, or a weak model's decision about either. There is
     * no model here at all: both routes are the harness's, so nothing in this row says a language
     * model would have chosen to walk them, and the Owner's actual question -- a weak model plus a
     * system prompt getting through the first night -- is not answered by this row and must not be
     * quoted as if it were.
     */
    static final class T24TheNightIsSomethingATaskCanAssert implements Task {
        @Override
        public String id() {
            return "T24 the_night_is_something_a_task_can_assert";
        }

        /**
         * The two legs' goals. Both inside the plain, which spans -24..24, and in different
         * directions so the second walk is a fresh route rather than a retracing of the first.
         */
        private static final int LEG_ONE_GOAL_X = 0;
        private static final int LEG_ONE_GOAL_Z = -18;
        private static final int LEG_TWO_GOAL_X = 18;
        private static final int LEG_TWO_GOAL_Z = -18;

        /** The first tick vanilla's own curve calls night, measured. See DaylightMatchesTheVanillaCurveTest. */
        private static final long DUSK = 13807L;
        private static final long DAWN = 22193L;

        @Override
        public Result run() {
            // ---------- leg one: a route walked in daylight ----------
            SimWorld w = plain().atWorldTime(0L);
            EvalHarness h = new EvalHarness(w);
            h.submit(new RouteIntent(LEG_ONE_GOAL_X, 64, LEG_ONE_GOAL_Z, 64));

            boolean startedInDay = w.isDaytime();
            long startedAt = w.worldTime();
            boolean legOneArrived = h.runUntil(ActSlot.MOVE, 400);
            long endedAfterLegOne = w.worldTime();
            boolean aliveAfterLegOne = w.health() > 0.0D;

            // ---------- the night, waited out rather than assumed ----------
            //
            // A walk takes on the order of a hundred ticks and a night is 8,386 of them, so a
            // single route cannot span one and a task that pretended otherwise would be
            // measuring its own budget. The night is therefore WAITED OUT with the body standing
            // still, and the thing being asserted is that the world really did become night and
            // really did become day again while the agent was in it.
            // Exactly to the tick, not past it: +1 would land on DUSK+1 and the row would report
            // a dusk one tick later than the curve's, which is the kind of off-by-one this suite
            // exists to catch and must not itself commit.
            h.ticks((int) (DUSK - w.worldTime()));
            long atDusk = w.worldTime();
            boolean nightCame = w.isNight();
            int ticksUntilDawn = w.ticksUntilDawn();

            h.ticks((int) (DAWN - w.worldTime()));
            long atDawn = w.worldTime();
            boolean dawnCame = w.isDaytime();

            // ---------- leg two: a route walked in the dark ----------
            //
            // The part that is about the night rather than about the counter. A pin the clock
            // inside the night, walk a fresh route, and check the body arrived: that is a
            // locomotion claim made under darkness, and without the substrate's clock it was not
            // writable at all.
            w.atWorldTime(DUSK + 200L);
            boolean isDarkBeforeLegTwo = w.isNight();
            h.submit(new RouteIntent(LEG_TWO_GOAL_X, 64, LEG_TWO_GOAL_Z, 64));
            boolean legTwoArrived = h.runUntil(ActSlot.MOVE, 400);
            long endedAt = w.worldTime();
            boolean alive = w.health() > 0.0D;
            boolean atGoal = Math.abs(w.posX() - LEG_TWO_GOAL_X) < 1.0D
                    && Math.abs(w.posZ() - LEG_TWO_GOAL_Z) < 1.0D;

            // ---------- the control: same world, clock frozen ----------
            //
            // Without this, "the world became night" and "the fixture says night" are one row.
            SimWorld frozen = plain().atWorldTime(0L).doDaylightCycle(false);
            EvalHarness fh = new EvalHarness(frozen);
            fh.submit(new RouteIntent(LEG_ONE_GOAL_X, 64, LEG_ONE_GOAL_Z, 64));
            fh.runUntil(ActSlot.MOVE, 400);
            fh.ticks((int) (DAWN + 1));
            boolean controlStayedDay = frozen.isDaytime();
            long controlClock = frozen.worldTime();

            boolean pass = startedInDay && legOneArrived && aliveAfterLegOne && nightCame
                    && dawnCame && isDarkBeforeLegTwo && legTwoArrived && alive && atGoal
                    && controlStayedDay && controlClock == 0L;

            return new Result(id(), pass, String.format(java.util.Locale.ROOT,
                    "%s. leg 1 walked 0,64,0 -> %d,64,%d in daylight (clock %d -> %d on arrival,"
                            + " arrived=%s). Then the night was waited out: it became night at"
                            + " clock %d (dusk is %d) and day again at clock %d (dawn is %d), and"
                            + " ticksUntilDawn() reported %d while it was dark. Leg 2 then walked"
                            + " from where leg 1 stopped to %d,64,%d with the clock pinned inside"
                            + " the night (isNight=%s), arrived=%s, and ended at clock %d."
                            + " health=%.1f; end pos=(%.2f,%.2f,%.2f)."
                            + " CONTROL: the same world with doDaylightCycle off ran past dawn and"
                            + " reached worldTime=%d still in daylight=%s -- so the night above was"
                            + " the CLOCK arriving and not the fixture asserting it. %s",
                    pass ? "a night is now a thing this suite can assert about"
                            : "the night did not become an assertable fact: "
                                    + describe(startedInDay, legOneArrived, nightCame, dawnCame,
                                            isDarkBeforeLegTwo, legTwoArrived, alive, atGoal,
                                            controlStayedDay, controlClock),
                    LEG_ONE_GOAL_X, LEG_ONE_GOAL_Z, startedAt, endedAfterLegOne, legOneArrived,
                    atDusk, DUSK, atDawn, DAWN, ticksUntilDawn, LEG_TWO_GOAL_X, LEG_TWO_GOAL_Z,
                    isDarkBeforeLegTwo, legTwoArrived, endedAt, w.health(), w.posX(), w.posY(),
                    w.posZ(), controlClock, controlStayedDay, whatThisCannotShow()));
        }

        /** Which world fact is missing, so a failure names the step rather than the task. */
        private static String describe(boolean startedInDay, boolean legOneArrived,
                boolean nightCame, boolean dawnCame, boolean isDarkBeforeLegTwo,
                boolean legTwoArrived, boolean alive, boolean atGoal, boolean controlStayedDay,
                long controlClock) {
            List<String> missing = new java.util.ArrayList<>();
            if (!startedInDay) {
                missing.add("the world was not in daylight when the first walk started, so the run"
                        + " was not a day-into-night comparison");
            }
            if (!legOneArrived) {
                missing.add("the first MOVE slot never went terminal inside 400 ticks, which is a"
                        + " locomotion result and not a clock one");
            }
            if (!nightCame) {
                missing.add("the world never became night, so no night occurred and this row"
                        + " measured nothing about one");
            }
            if (!dawnCame) {
                missing.add("the world never became day again, so the night did not end either");
            }
            if (!isDarkBeforeLegTwo) {
                missing.add("the clock was pinned to a time the curve calls day, so the second walk"
                        + " was not walked in the dark and the row says nothing about a night");
            }
            if (!legTwoArrived) {
                missing.add("the second MOVE slot never went terminal inside 400 ticks");
            }
            if (!atGoal) {
                missing.add("the body did not reach the far side of the second walk");
            }
            if (!alive) {
                missing.add("the player died");
            }
            if (!controlStayedDay) {
                missing.add("the CONTROL world reported night with the clock pinned, so the"
                        + " fixture is asserting night rather than deriving it and the first half"
                        + " of this row proves nothing");
            }
            if (controlClock != 0L) {
                missing.add("the control clock moved to " + controlClock + " with"
                        + " doDaylightCycle off, so the toggle does not do what the game rule"
                        + " does and the control is not a control");
            }
            return missing.isEmpty() ? "the world disagreed with no named fact; re-read the row"
                    : String.join(" and ", missing);
        }

        /**
         * The limits, printed in the result rather than only in this javadoc.
         *
         * <p>The suite's contract is that a result is evidence and a coverage claim is not. A row
         * that reads "survived the night" in a report and means "walked while a counter advanced"
         * is the failure this suite was built to prevent, so the row says so itself.
         */
        private static String whatThisCannotShow() {
            return "NOT COVERED, and not quotable as if it were: this substrate has a clock and no"
                    + " lighting, so nothing spawns in the dark, no zombie burns at dawn, no light"
                    + " level gates a cell and a torch changes nothing. This row shows the"
                    + " controllers composing across a night boundary; it does not show that the"
                    + " night was survivable, and there is no model in it at all -- the route is"
                    + " the harness's, so nothing here says a weak language model would have"
                    + " chosen it";
        }
    }

    // ===== goal-directed world queries =====

    /** How many cells of the given name stand in an inclusive box, counted off the block grid. */
    private static int countBlocks(SimWorld w, String name, int x0, int y0, int z0, int x1, int y1,
            int z1) {
        int n = 0;
        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    if (name.equals(w.blockAt(bx, by, bz))) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /**
     * How many recorded ticks the player's FEET occupied a cell holding the given block.
     *
     * <p>Read off {@link SimWorld#posTrace()}, which is the world's own record of where the body
     * was on each tick, and not off a controller's report of where it meant to go. A hazard is
     * something that happened to a body, so the body is where the question is asked.
     */
    private static int cellsOccupiedBy(SimWorld w, String name, int x0, int y0, int z0, int x1,
            int y1, int z1) {
        int ticks = 0;
        for (double[] p : w.posTrace()) {
            int bx = (int) Math.floor(p[0]);
            int by = (int) Math.floor(p[1]);
            int bz = (int) Math.floor(p[2]);
            if (bx < x0 || bx > x1 || by < y0 || by > y1 || bz < z0 || bz > z1) {
                continue;
            }
            if (name.equals(w.blockAt(bx, by, bz))) {
                ticks++;
            }
        }
        return ticks;
    }

    /**
     * How many cells of the STRAIGHT line between two points hold the given block.
     *
     * <p>Sampled from the world rather than asserted by the task, so a task can check that its own
     * hazard is still in the way. A detour task whose hazard has quietly been moved out of the line
     * would otherwise keep passing: the agent would still arrive, and "never entered the lava" is
     * also what a world with no lava in it reports. Sampling four times per block of line keeps a
     * one-cell band from being stepped over between samples.
     */
    private static int lineCellsHolding(SimWorld w, String name, int x0, int z0, int x1, int z1,
            int y) {
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(x1 - x0, z1 - z0) * 4.0D));
        int hits = 0;
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            int bx = (int) Math.floor(x0 + (x1 - x0) * t);
            int bz = (int) Math.floor(z0 + (z1 - z0) * t);
            if (name.equals(w.blockAt(bx, y, bz))) {
                hits++;
            }
        }
        return hits;
    }

    /**
     * How many cell boundaries the body crossed, which is the honest length of a walk.
     *
     * <p>A count of crossings rather than a count of ticks, because a tick is not a distance: the
     * speed varies with what is underfoot and how much friction the block has. Crossings are
     * blocks, and blocks are what a detour is measured in.
     */
    private static int cellsWalked(SimWorld w) {
        List<double[]> trace = w.posTrace();
        int steps = 0;
        for (int i = 1; i < trace.size(); i++) {
            double[] a = trace.get(i - 1);
            double[] b = trace.get(i);
            if (Math.floor(a[0]) != Math.floor(b[0]) || Math.floor(a[2]) != Math.floor(b[2])) {
                steps++;
            }
        }
        return steps;
    }

    /** Empty inventory slots, which is the number a dropped item needs and never gets. */
    private static int freeSlots(SimWorld w) {
        int n = 0;
        for (int i = 0; i < w.inventory().mainInventory.length; i++) {
            if (w.inventory().mainInventory[i] == null) {
                n++;
            }
        }
        return n;
    }


    // ===== shared =====

    /** The first recipe the real table offers for an item, or null. */
    private static RecipeView firstRecipe(String item) {
        List<RecipeView> recipes = Craft.recipesFor(item).recipes();
        return recipes.isEmpty() ? null : recipes.get(0);
    }

    /**
     * Drive a craft to a terminal outcome, advancing the window each tick so a pending server
     * resync can land.
     *
     * <p>The window advance is the round trip. {@code CraftController} waits {@code SETTLE_TICKS}
     * before it re-reads, and those ticks are where the server's answer arrives; a task that
     * ticked the controller without ticking the window would be testing a machine that could
     * never learn it had been refused.
     */
    private static CraftOutcome drive(CraftController c, SimCraftWindow win, int maxTicks) {
        CraftOutcome out = null;
        for (int i = 0; i < maxTicks && (out == null || !out.terminal()); i++) {
            out = c.tick(win);
            win.advanceTick();
        }
        return out;
    }
}
