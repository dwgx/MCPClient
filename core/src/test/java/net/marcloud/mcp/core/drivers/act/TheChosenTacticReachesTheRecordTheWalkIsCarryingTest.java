package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.drivers.plan.BlockView;
import net.marcloud.mcp.core.drivers.plan.Planner;
import net.marcloud.mcp.core.drivers.plan.RouteExecutor;
import net.marcloud.mcp.core.drivers.plan.Stance;
import org.junit.Test;

/**
 * What the walk CHOSE has to be readable on the record the walk is carrying, and re-stamping it
 * every tick must not cost the walk anything.
 *
 * <p><b>What this pins, and why a value test was not enough.</b> {@code RouteIntent} has carried a
 * {@link MoveTactic} field since the tactic became a value, and {@code NavController} has published
 * one every tick since the same change -- and nothing ever put the two together. The only caller of
 * {@link RouteIntent#withTactic} was a test, so the field existed, the accessor existed, and the
 * production path carried the goal and nothing else. A test that builds the pair itself proves only
 * that a record can hold a value it was handed.
 *
 * <p><b>So this drives the real thing.</b> {@link MoveApplier} -&gt; {@link RouteExecutor} -&gt;
 * {@link NavController}, over a plan the real {@link Planner} produced, with the body advanced by
 * {@link BodySim} from the axes the runtime actually published. The route factory is the applier's
 * own constructor parameter -- the same injection point {@code McpCore} supplies in production --
 * and the test never calls {@code withTactic} itself. If the production path stopped stamping, this
 * file fails on a null tactic.
 *
 * <p><b>The second half is the trap.</b> {@code withTactic} returns a NEW intent, so per-tick
 * stamping hands the applier a fresh {@code record.intent()} on every tick. The applier binds its
 * machine on "is this still the same walk", and an object-identity reading of that question rebuilds
 * the machine every tick: the reaction draw is re-rolled (so the body never leaves its onset pause),
 * the frozen lane bearing is lost, and the wedge-recovery budget is reset. A walk in that state does
 * not move at all, so the assertions here are about the walk ARRIVING and about the machine being
 * built exactly once.
 */
public final class TheChosenTacticReachesTheRecordTheWalkIsCarryingTest {

    private static final int GROUND = 63;
    private static final int FEET = 64;
    private static final int BLOCKS = 12;
    private static final int MAX_TICKS = 600;

    /** Open ground down the +Z lane, three cells wide either side, so nothing wedges. */
    private static FakeActuator corridor() {
        FakeActuator act = new FakeActuator();
        for (int z = -3; z <= BLOCKS + 3; z++) {
            for (int x = -3; x <= 3; x++) {
                act.putBlock(x, GROUND, z);
            }
        }
        act.setPosition(0.5D, FEET, 0.5D);
        act.onGround = true;
        act.setRotation(0f, 0f);
        return act;
    }

    /** One tick of observation: the tactic production put on the record, and what it must match. */
    private record Observation(MoveTactic stamped, float publishedForward, float publishedStrafe,
                               boolean publishedJump) { }

    /** Everything a whole route tells us. */
    private record Run(SlotRecord end, int binds, int drawnDelay, int drawnDelayAtEnd,
                       int stampedTicks, java.util.List<Observation> seen) { }

    /**
     * Drive the whole route through the production path and watch the record.
     *
     * <p>The axes are read from the RUNTIME rather than from the controller, because the runtime is
     * what the game reads and a comparison against the controller would skip the applier -- the very
     * layer this change is in.
     */
    private static Run drive() {
        FakeActuator act = corridor();
        BlockView world = BodySim.blockView(act);
        Planner.Plan plan = new Planner(world).plan(new Stance(0, FEET, 0), new Stance(0, FEET, BLOCKS));
        assertTrue("premise: the planner must find the straight corridor, or this is not measuring a "
                        + "walk. It said " + plan.failure(), plan.found());
        assertEquals("premise: and it decomposes it one move per block, as production does",
                BLOCKS, plan.moves().size());

        ActRuntime runtime = new ActRuntime();
        int[] binds = {0};
        MoveApplier applier = new MoveApplier(act, runtime, ri -> {
            binds[0]++;
            return new RouteExecutor(plan, ri.blockBudget(), world);
        });

        SlotRecord rec = SlotRecord.submitted(
                new RouteIntent(0, FEET, BLOCKS, 0), 0L, 1L, "submitted");
        java.util.List<Observation> seen = new java.util.ArrayList<>();
        int drawn = -1;
        for (int tick = 1; tick <= MAX_TICKS && !rec.phase().isTerminal(); tick++) {
            rec = applier.apply(rec.stampTick(tick));
            if (drawn < 0) {
                drawn = applier.drawnReactionDelay();
            }
            float forward = runtime.moveForward();
            float strafe = runtime.moveStrafe();
            boolean jump = runtime.jump();
            MoveTactic onRecord = tacticOn(rec);
            if (onRecord != null) {
                seen.add(new Observation(onRecord, forward, strafe, jump));
            }
            BodySim.step(act, forward, strafe);
        }
        return new Run(rec, binds[0], drawn, applier.drawnReactionDelay(), seen.size(), seen);
    }

    /** The tactic production stamped onto the intent the slot is carrying, or null. */
    private static MoveTactic tacticOn(SlotRecord rec) {
        return rec.intent() instanceof RouteIntent route ? route.tactic() : null;
    }

    /**
     * The decision the walk made is on the record production produced, and it is the decision the
     * fingers are acting on this same tick.
     */
    @Test
    public void theChosenTacticIsOnTheRecordTheWalkIsCarrying() {
        Run run = drive();

        assertTrue("the route must have finished for the rest of this to mean anything: "
                        + run.end().phase() + " / " + run.end().message(),
                run.end().phase() == ActPhase.COMPLETE);
        assertTrue("production stamped a tactic onto the record on " + run.stampedTicks()
                        + " tick(s). It cannot be zero: the applier runs a controller on every tick "
                        + "of a walk, and this test never calls withTactic itself, so a null here "
                        + "means the production path is not publishing the decision at all",
                run.stampedTicks() > 0);

        MoveTactic last = tacticOn(run.end());
        assertNotNull("the terminal record is the walk's only summary, and it is the one tick a "
                + "caller reads most. A terminal record with no tactic reports the outcome and "
                + "nothing about what it cost", last);
        assertNotNull("and a tactic whose account of the cost is missing cannot be read at all: "
                        + "givenUp is never null, so a null here is a record that was never built "
                        + "from one", last.givenUp());

        for (Observation o : run.seen()) {
            assertEquals("a tactic whose axes disagree with the axes published on the SAME tick is "
                            + "worse than no record at all -- that is MoveTactic's whole contract. "
                            + "This one says forward " + o.stamped().forward() + " while the body "
                            + "was given " + o.publishedForward() + ": " + o.stamped().describe(),
                    o.publishedForward(), o.stamped().forward(), 1e-6D);
            assertEquals("and the same for strafe: " + o.stamped().describe(),
                    o.publishedStrafe(), o.stamped().strafe(), 1e-6D);
            assertEquals("a jump published this tick and not asked for by the tactic is a record "
                            + "that disagrees with the fingers on the axis that moves the body over "
                            + "a block. " + o.stamped().describe(),
                    o.publishedJump(), o.stamped().jumps());
        }
    }

    /**
     * Re-stamping the intent every tick must not cost the walk its machine, its reaction draw, or
     * its arrival.
     *
     * <p>This is the trap the inherited analysis named: {@code withTactic} returns a new object, so
     * a bind test that asks "same object?" answers "no" on every tick of a routed walk.
     */
    @Test
    public void reStampingTheIntentEveryTickDoesNotRebuildTheWalk() {
        Run run = drive();

        assertEquals("the route machine was built " + run.binds() + " time(s). It must be built "
                        + "exactly once: a per-tick rebuild re-draws the reaction delay, so the "
                        + "applier re-enters its own onset pause forever and the body never takes a "
                        + "step. That is not a subtle degradation, the walk does not move",
                1, run.binds());
        assertEquals("the reaction delay is drawn once per walk and read back unchanged: this is "
                        + "the state that makes walking work, and a per-tick rebuild is exactly "
                        + "what re-rolls it",
                run.drawnDelay(), run.drawnDelayAtEnd());
        assertTrue("premise: a walk that did not pay an onset pause was never started by this "
                        + "harness: " + run.drawnDelay(), run.drawnDelay() >= MoveApplier.REACTION_FLOOR_TICKS);
    }
}
